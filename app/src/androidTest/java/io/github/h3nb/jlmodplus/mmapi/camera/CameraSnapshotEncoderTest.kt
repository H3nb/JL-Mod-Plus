// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.microedition.media.MediaException
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CameraSnapshotEncoderTest {
    @Test
    fun pngPreservesCornersAndCenterWithoutMirroring() {
        val source = asymmetricImage()
        try {
            withDecoded(encode(source, Rect(0, 0, 12, 8), 0, 24, 16)) { result ->
                assertEquals(24, result.width)
                assertEquals(16, result.height)
                assertCorners(result, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                assertEquals(Color.WHITE, result.getPixel(12, 8))
            }
            assertFalse("Encoder must retain its caller-owned source", source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun rightAngleRotationsPreserveAllCornersInTheirExpectedPositions() {
        val source = asymmetricImage()
        try {
            val corners = listOf(
                90 to intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                180 to intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                270 to intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            )
            for ((rotation, expected) in corners) {
                val width = if (rotation == 180) 24 else 16
                val height = if (rotation == 180) 16 else 24
                withDecoded(encode(source, Rect(0, 0, 12, 8), rotation, width, height)) { result ->
                    assertEquals(width, result.width)
                    assertEquals(height, result.height)
                    assertCorners(result, expected[0], expected[1], expected[2], expected[3])
                    assertEquals(Color.WHITE, result.getPixel(width / 2, height / 2))
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun cropExcludesOutsidePixelsBeforeScalingAndRotation() {
        val source = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.MAGENTA)
        val canvas = Canvas(source)
        val paint = Paint().apply { color = Color.CYAN }
        canvas.drawRect(2f, 2f, 10f, 6f, paint)
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val width = if (rotation % 180 == 0) 32 else 16
                val height = if (rotation % 180 == 0) 16 else 32
                withDecoded(encode(source, Rect(2, 2, 10, 6), rotation, width, height)) { result ->
                    for (y in 0 until height) for (x in 0 until width) {
                        assertEquals("Crop bleed at ($x,$y), rotation $rotation", Color.CYAN, result.getPixel(x, y))
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun differingAspectRatiosAddBlackPaddingWithoutCropping() {
        val source = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.CYAN)
        try {
            for (rotation in listOf(0, 90)) {
                withDecoded(encode(source, Rect(0, 0, 8, 4), rotation, 8, 8)) { result ->
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val content = if (rotation == 0) y in 2..5 else x in 2..5
                        assertEquals(if (content) Color.CYAN else Color.BLACK, result.getPixel(x, y))
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun jpegReturnsDecodableExactDimensionsAndExpectedOrientation() {
        val source = asymmetricImage()
        try {
            val options = CameraCaptureOptions.Snapshot(CameraCaptureOptions.Format.JPEG, 80, 120, 100)
            val bytes = CameraSnapshotEncoder.encodeBitmap(source, Rect(0, 0, 12, 8), 90, options)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
            withDecoded(bytes) { result ->
                assertEquals(80, result.width)
                assertEquals(120, result.height)
                assertColorNear(Color.BLUE, result.getPixel(10, 10))
                assertColorNear(Color.RED, result.getPixel(69, 10))
                assertColorNear(Color.YELLOW, result.getPixel(10, 109))
                assertColorNear(Color.GREEN, result.getPixel(69, 109))
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun invalidCropAndRotationAreRejectedWithoutRecyclingSource() {
        val source = asymmetricImage()
        try {
            expectMediaException { encode(source, Rect(-1, 0, 12, 8), 0, 24, 16) }
            expectMediaException { encode(source, Rect(0, 0, 13, 8), 0, 24, 16) }
            expectMediaException { encode(source, Rect(2, 2, 2, 4), 0, 24, 16) }
            expectMediaException { encode(source, Rect(0, 0, 12, 8), 45, 24, 16) }
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    private fun asymmetricImage(): Bitmap {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, 6f, 4f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(6f, 0f, 12f, 4f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, 4f, 6f, 8f, paint)
        paint.color = Color.YELLOW
        canvas.drawRect(6f, 4f, 12f, 8f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(5f, 3f, 7f, 5f, paint)
        return bitmap
    }

    private fun encode(source: Bitmap, crop: Rect, rotation: Int, width: Int, height: Int) =
        CameraSnapshotEncoder.encodeBitmap(source, crop, rotation,
            CameraCaptureOptions.Snapshot(CameraCaptureOptions.Format.PNG, width, height, 90))

    private fun assertCorners(bitmap: Bitmap, tl: Int, tr: Int, bl: Int, br: Int) {
        assertEquals(tl, bitmap.getPixel(2, 2))
        assertEquals(tr, bitmap.getPixel(bitmap.width - 3, 2))
        assertEquals(bl, bitmap.getPixel(2, bitmap.height - 3))
        assertEquals(br, bitmap.getPixel(bitmap.width - 3, bitmap.height - 3))
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(abs(Color.red(expected) - Color.red(actual)) <= 20)
        assertTrue(abs(Color.green(expected) - Color.green(actual)) <= 20)
        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) <= 20)
    }

    private inline fun withDecoded(bytes: ByteArray, check: (Bitmap) -> Unit) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Snapshot must decode", bitmap)
        try {
            check(checkNotNull(bitmap))
        } finally {
            bitmap?.recycle()
        }
    }

    private inline fun expectMediaException(operation: () -> Unit) {
        try {
            operation()
            throw AssertionError("Expected MediaException")
        } catch (_: MediaException) {
            // Expected API-level rejection.
        }
    }
}
