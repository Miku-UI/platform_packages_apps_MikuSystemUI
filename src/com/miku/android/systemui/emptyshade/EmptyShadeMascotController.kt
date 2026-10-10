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

package com.miku.android.systemui.emptyshade

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.android.app.animation.Interpolators
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackInteractor
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Slides the empty-shade mascot up from the bottom of the shade window. */
@SysUISingleton
class EmptyShadeMascotController
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val shadeWindow: NotificationShadeWindowView,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val notificationStackInteractor: NotificationStackInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val zenModeInteractor: ZenModeInteractor,
    private val seenNotificationsInteractor: SeenNotificationsInteractor,
) : CoreStartable {

    private lateinit var mascot: EmptyShadeMascotView
    private var shown = false
    private var pendingShow = false

    override fun start() {
        inflate()
        scope.launch {
            val shadeFull = shadeInteractor.shadeExpansion.map { it >= 1f }
            val emptyDefault =
                combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    notificationStackInteractor.isShowingOnLockscreen,
                    zenModeInteractor.modesHidingNotifications,
                    seenNotificationsInteractor.hasFilteredOutSeenNotifications,
                ) { hasNotifs, onLockscreen, modes, filteredSeen ->
                    !hasNotifs && !onLockscreen && modes.main == null && !filteredSeen
                }
            val shadeAllows =
                combine(
                    shadeFull,
                    shadeInteractor.qsExpansion,
                    shadeModeInteractor.shadeMode,
                    mediaCarouselInteractor.hasActiveMedia,
                ) { full, qs, mode, hasMedia ->
                    val dual = mode is ShadeMode.Dual
                    val single = mode is ShadeMode.Single
                    full &&
                        !(qs >= QS_EXPANSION_THRESHOLD && single) &&
                        !(hasMedia && dual)
                }
            combine(emptyDefault, shadeAllows, shadeFull) { empty, allows, full ->
                    Pair(empty && allows, full)
                }
                .distinctUntilChanged()
                .collect { (shouldShow, shadeFullyExpanded) ->
                    applyVisibility(shouldShow, shadeFullyExpanded)
                }
        }
    }

    private fun inflate() {
        mascot =
            EmptyShadeMascotView(shadeWindow.context).apply {
                visibility = View.GONE
                alpha = 1f
            }
        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        val shared = shadeWindow.findViewById<View>(R.id.shared_notification_container)
        val index = if (shared != null) shadeWindow.indexOfChild(shared) + 1 else -1
        if (index >= 0) {
            shadeWindow.addView(mascot, index, lp)
        } else {
            shadeWindow.addView(mascot, lp)
        }
    }

    private fun applyVisibility(shouldShow: Boolean, shadeFullyExpanded: Boolean) {
        if (shouldShow) {
            showAnimated()
        } else if (shadeFullyExpanded) {
            hideAnimated()
        } else {
            hideImmediate()
        }
    }

    private fun showAnimated() {
        if (shown) {
            return
        }
        shown = true
        pendingShow = true
        mascot.animate().cancel()
        mascot.visibility = View.VISIBLE
        mascot.alpha = 1f
        val start = Runnable {
            if (!pendingShow || !shown) {
                return@Runnable
            }
            pendingShow = false
            val h = mascot.height.toFloat()
            if (h <= 0f) {
                mascot.translationY = 0f
                return@Runnable
            }
            mascot.translationY = h
            mascot
                .animate()
                .translationY(0f)
                .setDuration(SHOW_MS)
                .setInterpolator(Interpolators.EMPHASIZED_DECELERATE)
                .withEndAction(null)
                .start()
        }
        if (mascot.height > 0 && mascot.isLaidOut) {
            start.run()
        } else {
            mascot.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        mascot.viewTreeObserver.removeOnPreDrawListener(this)
                        start.run()
                        return true
                    }
                }
            )
        }
    }

    private fun hideAnimated() {
        pendingShow = false
        if (!shown && mascot.visibility == View.GONE) {
            return
        }
        shown = false
        mascot.animate().cancel()
        val h = mascot.height.toFloat()
        if (h <= 0f) {
            hideImmediate()
            return
        }
        mascot
            .animate()
            .translationY(h)
            .setDuration(HIDE_MS)
            .setInterpolator(Interpolators.EMPHASIZED_ACCELERATE)
            .withEndAction {
                mascot.visibility = View.GONE
                mascot.translationY = 0f
            }
            .start()
    }

    private fun hideImmediate() {
        pendingShow = false
        shown = false
        mascot.animate().cancel()
        mascot.visibility = View.GONE
        mascot.translationY = 0f
    }

    companion object {
        private const val SHOW_MS = 1000L
        private const val HIDE_MS = 280L
        private const val QS_EXPANSION_THRESHOLD = 0.9f
    }
}
