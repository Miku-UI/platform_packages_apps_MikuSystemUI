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

package com.miku.android.systemui.volume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.tracing.coroutines.launchInTraced
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderTranslation
import com.android.systemui.volume.dialog.ui.VolumeDialogKeyMotion
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.onEach

@SysUISingleton
class VolumeDialogKeyMotionImpl
@Inject
constructor(
    context: Context,
    private val viewModel: VolumeDialogKeyPressViewModel,
) : VolumeDialogKeyMotion {

    private val shakeAmplitudePx: Float =
        context.resources.getDimension(R.dimen.volume_dialog_key_press_shake_amplitude)

    override fun CoroutineScope.bind(translation: VolumeDialogSliderTranslation) {
        val offsetHolder = FloatValueHolder(0f)
        // animateToFinalPosition skips setFinalPosition while running; keep one SpringForce.
        val spring =
            SpringForce(0f).apply {
                stiffness = PRESS_STIFFNESS
                dampingRatio = PRESS_DAMPING_RATIO
            }
        val offsetAnimation =
            SpringAnimation(offsetHolder)
                .setSpring(spring)
                .setMinimumVisibleChange(0.5f)
                .addUpdateListener { _, value, _ -> translation.keyPressPx = value }

        val shakeAnimator =
            ShakeAnimator(shakeAmplitudePx) { shakeX, shakeY ->
                translation.shakeXPx = shakeX
                translation.shakeYPx = shakeY
            }

        viewModel.pressOffsetPxFlow
            .dropWhile { it == 0f }
            .onEach { targetOffsetPx ->
                val releasing = targetOffsetPx == 0f
                spring.stiffness = if (releasing) RELEASE_STIFFNESS else PRESS_STIFFNESS
                spring.dampingRatio =
                    if (releasing) RELEASE_DAMPING_RATIO else PRESS_DAMPING_RATIO
                offsetAnimation.animateToFinalPosition(targetOffsetPx)
                shakeAnimator.start()
            }
            .launchInTraced("VDKM#pressOffset", this)
    }
}

private class ShakeAnimator(
    private val amplitudePx: Float,
    private val onUpdate: (Float, Float) -> Unit,
) {
    private var animator: ValueAnimator? = null

    fun start() {
        animator?.removeAllListeners()
        animator?.cancel()
        val anim =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SHAKE_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedFraction
                    val decay = exp(-SHAKE_DECAY * t).toFloat()
                    val x = amplitudePx * sin(t * TWO_PI * SHAKE_FREQ_X).toFloat() * decay
                    val y =
                        amplitudePx *
                            SHAKE_Y_RATIO *
                            sin(t * TWO_PI * SHAKE_FREQ_Y + SHAKE_Y_PHASE).toFloat() *
                            decay
                    onUpdate(x, y)
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            onUpdate(0f, 0f)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            onUpdate(0f, 0f)
                        }
                    }
                )
            }
        animator = anim
        anim.start()
    }
}

private const val PRESS_STIFFNESS = 900f
private const val PRESS_DAMPING_RATIO = 0.82f
private const val RELEASE_STIFFNESS = 480f
private const val RELEASE_DAMPING_RATIO = 0.42f

private const val SHAKE_DURATION_MS = 180L
private const val SHAKE_DECAY = 7.5f
private const val SHAKE_FREQ_X = 22f
private const val SHAKE_FREQ_Y = 18f
private const val SHAKE_Y_RATIO = 0.45f
private const val SHAKE_Y_PHASE = 1.1f
private const val TWO_PI = (Math.PI * 2.0).toFloat()
