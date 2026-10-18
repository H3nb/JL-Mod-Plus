// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** Clips preview to the shared ViewPort aspect before fitting it into guest display bounds. */
internal class CameraPreviewContainer(context: Context, private val sourceWidth: Int,
                                      private val sourceHeight: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = min(measuredWidth.toDouble() / sourceWidth, measuredHeight.toDouble() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(0, measuredWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(0, measuredHeight)
        for (index in 0 until childCount) {
            getChildAt(index).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}
