// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewContainerTest {
    @Test fun sourceFramingIsClippedInsideDifferentlyShapedGuestBoundsAndSurvivesResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for ((sourceWidth, sourceHeight) in listOf(640 to 480, 480 to 640, 320 to 320)) {
                val container = CameraPreviewContainer(instrumentation.targetContext, sourceWidth, sourceHeight)
                container.setBackgroundColor(Color.BLACK)
                val preview = View(instrumentation.targetContext).apply { setBackgroundColor(Color.CYAN) }
                container.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                for ((width, height) in listOf(240 to 320, 320 to 240)) {
                    container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    container.layout(0, 0, width, height)
                    val scale = minOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
                    assertEquals(Math.round(sourceWidth * scale).toInt(), preview.width)
                    assertEquals(Math.round(sourceHeight * scale).toInt(), preview.height)
                    assertEquals((width - preview.width) / 2, preview.left)
                    assertEquals((height - preview.height) / 2, preview.top)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        container.draw(Canvas(bitmap))
                        for (y in 0 until height) for (x in 0 until width) {
                            val inPreview = x in preview.left until preview.right && y in preview.top until preview.bottom
                            assertEquals(if (inPreview) Color.CYAN else Color.BLACK, bitmap.getPixel(x, y))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
