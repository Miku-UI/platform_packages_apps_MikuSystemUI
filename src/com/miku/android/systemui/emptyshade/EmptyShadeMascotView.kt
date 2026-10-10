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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.R

/**
 * Peeking empty-shade illustration. The thought-bubble text box is laid out in the PSD
 * 「对话框」 inner bounds, as a fraction of the bitmap.
 */
class EmptyShadeMascotView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val imageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.empty_shade_hehe)
        }

    private val textView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(TEXT_COLOR)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setAutoSizeTextTypeUniformWithConfiguration(
                TEXT_SIZE_MIN_SP,
                TEXT_SIZE_MAX_SP,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setText(R.string.empty_shade_hehe_text)
        }

    init {
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(imageView)
        addView(textView)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textView.setText(R.string.empty_shade_hehe_text)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        val drawable = imageView.drawable
        val dw = drawable?.intrinsicWidth?.coerceAtLeast(1) ?: 1
        val dh = drawable?.intrinsicHeight?.coerceAtLeast(1) ?: 1
        val capH =
            if (heightMode == MeasureSpec.UNSPECIFIED || maxH <= 0) {
                Int.MAX_VALUE
            } else {
                (maxH * MAX_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
            }
        val scale = minOf(1f, maxW / dw.toFloat(), capH.toFloat() / dh)
        val w = (dw * scale).toInt().coerceAtLeast(1)
        val h = (dh * scale).toInt().coerceAtLeast(1)
        val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        imageView.measure(exactW, exactH)
        val tw = (w * TEXT_WIDTH).toInt().coerceAtLeast(1)
        val th = (h * TEXT_HEIGHT).toInt().coerceAtLeast(1)
        textView.measure(
            MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(maxW, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val iw = imageView.measuredWidth
        val ih = imageView.measuredHeight
        val imgLeft = (width - iw) / 2
        imageView.layout(imgLeft, 0, imgLeft + iw, ih)
        val textLeft = imgLeft + (iw * TEXT_LEFT).toInt()
        val textTop = (ih * TEXT_TOP).toInt()
        textView.layout(
            textLeft,
            textTop,
            textLeft + textView.measuredWidth,
            textTop + textView.measuredHeight,
        )
    }

    companion object {
        // Inner white rect of the 「对话框」 layer, relative to the 736×699 canvas.
        private const val TEXT_LEFT = 97f / 736f
        private const val TEXT_TOP = 58f / 699f
        private const val TEXT_WIDTH = 536f / 736f
        private const val TEXT_HEIGHT = 113f / 699f
        private const val MAX_HEIGHT_FRACTION = 0.45f
        private const val TEXT_COLOR = 0xFF222222.toInt()
        private const val TEXT_SIZE_MIN_SP = 8
        private const val TEXT_SIZE_MAX_SP = 16
    }
}
