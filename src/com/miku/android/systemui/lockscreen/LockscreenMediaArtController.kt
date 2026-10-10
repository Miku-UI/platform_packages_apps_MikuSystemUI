/*
 * Copyright (C) 2026 Miku UI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miku.android.systemui.lockscreen

import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import android.util.MathUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.app.animation.Interpolators
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Blurred album art overlay on the lockscreen. Drawn in the shade window over the wallpaper
 * surface; does not call [android.app.WallpaperManager].
 */
@SysUISingleton
class LockscreenMediaArtController
@Inject
constructor(
    private val shadeWindow: NotificationShadeWindowView,
    private val mediaManager: NotificationMediaManager,
    private val mediaDataManager: MediaDataManager,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val windowController: NotificationShadeWindowController,
    private val secureSettings: SecureSettings,
    dumpManager: DumpManager,
    @Main private val handler: Handler,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
) :
    CoreStartable,
    Dumpable,
    NotificationMediaManager.MediaListener,
    MediaDataManager.Listener,
    StatusBarStateController.StateListener,
    KeyguardStateController.Callback {

    private lateinit var container: FrameLayout
    private lateinit var artView: ImageView

    private var enabled = false
    private var playbackPlaying = false
    private var metadata: MediaMetadata? = null
    private var loadedArtwork: Icon? = null
    private var loadedArtworkKey: String? = null
    private var shown = false
    private var shownBitmap: Bitmap? = null
    private var appliedSourceKey: String? = null
    private val processGeneration = AtomicInteger()

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                readEnabled()
                refresh()
            }
        }

    init {
        dumpManager.registerDumpable(this)
    }

    override fun start() {
        inflateOverlay()
        readEnabled()
        secureSettings.registerContentObserverForUserAsync(
            LOCKSCREEN_MEDIA_METADATA,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        mediaManager.addCallback(this)
        mediaDataManager.addListener(this)
        statusBarStateController.addCallback(this)
        keyguardStateController.addCallback(this)
        refresh()
    }

    private fun inflateOverlay() {
        val context = shadeWindow.context
        artView =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // Enlarge slightly so the baked blur does not show hard edges.
                scaleX = 1.08f
                scaleY = 1.08f
            }
        val dim =
            View(context).apply {
                setBackgroundColor(DIM_COLOR)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        container =
            FrameLayout(context).apply {
                visibility = View.GONE
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false
                isFocusable = false
                addView(
                    artView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    dim,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        val scrimBehind = shadeWindow.findViewById<View>(R.id.scrim_behind)
        val index = if (scrimBehind != null) shadeWindow.indexOfChild(scrimBehind) + 1 else 0
        shadeWindow.addView(container, index, lp)
    }

    private fun readEnabled() {
        enabled = secureSettings.getInt(LOCKSCREEN_MEDIA_METADATA, 0) != 0
    }

    override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
        this.metadata = metadata
        playbackPlaying = NotificationMediaManager.isPlayingState(state)
        refresh()
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
    ) {
        if (oldKey != null && oldKey != key && oldKey == loadedArtworkKey) {
            loadedArtworkKey = key
        }
        if (data.isPlaying == true && data.artwork != null) {
            loadedArtwork = data.artwork
            loadedArtworkKey = key
        } else if (key == loadedArtworkKey || oldKey == loadedArtworkKey) {
            loadedArtwork = if (data.isPlaying == true) data.artwork else null
            if (loadedArtwork == null) {
                loadedArtworkKey = null
            }
        }
        refresh()
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        if (key == loadedArtworkKey) {
            loadedArtwork = null
            loadedArtworkKey = null
            refresh()
        }
    }

    override fun onStateChanged(newState: Int) {
        refresh()
    }

    override fun onKeyguardShowingChanged() {
        refresh()
    }

    override fun onKeyguardFadingAwayChanged() {
        refresh()
    }

    override fun onKeyguardGoingAwayChanged() {
        refresh()
    }

    override fun onLaunchTransitionFadingAwayChanged() {
        refresh()
    }

    private fun refresh() {
        val wantVisible = enabled && playbackPlaying && canShowOnLockscreen() && hasArtwork()
        if (!wantVisible) {
            hide(animate = !isUnlocking())
            return
        }
        val sourceKey = artworkSourceKey()
        if (sourceKey != null && sourceKey == appliedSourceKey) {
            if (shown) {
                return
            }
            if (shownBitmap != null) {
                show()
            }
            return
        }
        requestArtwork(sourceKey)
    }

    private fun canShowOnLockscreen(): Boolean {
        if (statusBarStateController.state == StatusBarState.SHADE) {
            return false
        }
        if (keyguardStateController.isOccluded) {
            return false
        }
        if (isUnlocking()) {
            return false
        }
        return true
    }

    private fun isUnlocking(): Boolean {
        return keyguardStateController.isKeyguardGoingAway ||
            keyguardStateController.isKeyguardFadingAway ||
            keyguardStateController.isLaunchTransitionFadingAway
    }

    private fun hasArtwork(): Boolean {
        return loadedArtwork != null || metadataBitmap() != null
    }

    private fun metadataBitmap(): Bitmap? {
        val meta = metadata ?: return null
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null && !art.isRecycled) {
            return art
        }
        val album = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        return if (album != null && !album.isRecycled) album else null
    }

    private fun artworkSourceKey(): String? {
        val icon = loadedArtwork
        if (icon != null) {
            return "icon:${icon.type}:${icon.hashCode()}:$loadedArtworkKey"
        }
        val bmp = metadataBitmap() ?: return null
        return "bmp:${bmp.width}x${bmp.height}:${bmp.generationId}"
    }

    private fun requestArtwork(sourceKey: String?) {
        val icon = loadedArtwork
        val metaBmp = metadataBitmap()
        val generation = processGeneration.incrementAndGet()
        appliedSourceKey = sourceKey
        bgExecutor.execute {
            val src =
                try {
                    when {
                        icon != null -> iconToBitmap(icon)
                        metaBmp != null && !metaBmp.isRecycled -> metaBmp
                        else -> null
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to decode artwork", t)
                    null
                }
            if (src == null || src.isRecycled) {
                mainExecutor.execute {
                    if (generation == processGeneration.get()) {
                        hide(animate = true)
                    }
                }
                return@execute
            }
            val processed =
                try {
                    processArtwork(src)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to process artwork", t)
                    null
                }
            mainExecutor.execute {
                if (generation != processGeneration.get()) {
                    processed?.recycle()
                    return@execute
                }
                if (processed == null) {
                    hide(animate = true)
                    return@execute
                }
                applyProcessed(processed)
            }
        }
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        if (
            icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_ADAPTIVE_BITMAP
        ) {
            val bmp = icon.bitmap
            return if (bmp != null && !bmp.isRecycled) bmp else null
        }
        val drawable = icon.loadDrawable(shadeWindow.context) ?: return null
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return out
    }

    private fun processArtwork(src: Bitmap): Bitmap {
        val size = Point()
        shadeWindow.display?.getRealSize(size)
        val maxSide = maxOf(size.x, size.y, 1) / DOWNSAMPLE
        val rect = Rect(0, 0, src.width, src.height)
        MathUtils.fitRect(rect, maxOf(maxSide, 1))
        var scaled =
            Bitmap.createScaledBitmap(
                src,
                rect.width().coerceAtLeast(1),
                rect.height().coerceAtLeast(1),
                true,
            )
        if (scaled.config != Bitmap.Config.ARGB_8888) {
            val converted = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (converted != scaled && scaled != src) {
                scaled.recycle()
            }
            scaled = converted
        }
        val blurred = boxBlur(scaled, BLUR_RADIUS)
        if (blurred != scaled && scaled != src) {
            scaled.recycle()
        }
        return blurred
    }

    private fun applyProcessed(bmp: Bitmap) {
        val old = shownBitmap
        shownBitmap = bmp
        artView.setImageBitmap(bmp)
        if (old != null && old != bmp && !old.isRecycled) {
            old.recycle()
        }
        show()
    }

    private fun show() {
        if (!shown) {
            shown = true
            windowController.setBackdropShowing(true)
            container.animate().cancel()
            container.visibility = View.VISIBLE
            container
                .animate()
                .alpha(1f)
                .setDuration(FADE_MS)
                .setInterpolator(Interpolators.ALPHA_IN)
                .withEndAction(null)
                .start()
        }
    }

    private fun hide(animate: Boolean) {
        processGeneration.incrementAndGet()
        appliedSourceKey = null
        if (!shown && container.visibility == View.GONE) {
            return
        }
        shown = false
        windowController.setBackdropShowing(false)
        container.animate().cancel()
        val end = {
            container.visibility = View.GONE
            artView.setImageDrawable(null)
            shownBitmap?.let { if (!it.isRecycled) it.recycle() }
            shownBitmap = null
        }
        if (!animate || container.alpha == 0f) {
            container.alpha = 0f
            end()
            return
        }
        container
            .animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction(end)
            .start()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("LockscreenMediaArtController:")
        pw.println("  enabled=$enabled")
        pw.println("  playbackPlaying=$playbackPlaying")
        pw.println("  shown=$shown")
        pw.println("  loadedArtworkKey=$loadedArtworkKey")
        pw.println("  appliedSourceKey=$appliedSourceKey")
        pw.println("  statusBarState=${statusBarStateController.state}")
        pw.println("  occluded=${keyguardStateController.isOccluded}")
        if (::container.isInitialized) {
            pw.println("  visibility=${container.visibility} alpha=${container.alpha}")
        }
        shownBitmap?.let { pw.println("  bitmap=${it.width}x${it.height}") }
    }

    companion object {
        private const val TAG = "LockscreenMediaArt"
        /** Matches Settings.Secure.LOCKSCREEN_MEDIA_METADATA. */
        private const val LOCKSCREEN_MEDIA_METADATA = "lockscreen_media_metadata"
        private const val DOWNSAMPLE = 8
        private const val BLUR_RADIUS = 8
        private const val FADE_MS = 300L
        private const val DIM_COLOR = 0x99000000.toInt()

        private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
            if (radius < 1) {
                return src
            }
            val w = src.width
            val h = src.height
            val pix = IntArray(w * h)
            src.getPixels(pix, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            blurPass(pix, tmp, w, h, radius)
            blurPass(tmp, pix, h, w, radius)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pix, 0, w, 0, 0, w, h)
            return out
        }

        /** Separable box blur; second call transposes by swapping w/h. */
        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
            val div = radius * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var aSum = 0
                for (i in -radius..radius) {
                    val c = src[row + i.coerceIn(0, w - 1)]
                    aSum += c ushr 24
                    rSum += c shr 16 and 0xFF
                    gSum += c shr 8 and 0xFF
                    bSum += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[x * h + y] =
                        (aSum / div shl 24) or
                            (rSum / div shl 16) or
                            (gSum / div shl 8) or
                            (bSum / div)
                    val cOut = src[row + (x - radius).coerceIn(0, w - 1)]
                    val cIn = src[row + (x + radius + 1).coerceIn(0, w - 1)]
                    aSum += (cIn ushr 24) - (cOut ushr 24)
                    rSum += (cIn shr 16 and 0xFF) - (cOut shr 16 and 0xFF)
                    gSum += (cIn shr 8 and 0xFF) - (cOut shr 8 and 0xFF)
                    bSum += (cIn and 0xFF) - (cOut and 0xFF)
                }
            }
        }
    }
}
