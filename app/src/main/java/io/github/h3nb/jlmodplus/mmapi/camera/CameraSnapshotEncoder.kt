// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.microedition.media.MediaException
import kotlin.math.min

/** Worker-only snapshot conversion. The caller retains ownership of the ImageProxy. */
internal object CameraSnapshotEncoder {
    private const val MAX_SOURCE_PIXELS = 4_194_304L
    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024

    @Throws(MediaException::class)
    fun encode(image: ImageProxy, options: CameraCaptureOptions.Snapshot): ByteArray {
        if (image.format != ImageFormat.JPEG || image.planes.size != 1) {
            throw MediaException("Unsupported camera image format")
        }
        val buffer = image.planes[0].buffer.duplicate()
        val length = buffer.remaining()
        if (length <= 0 || length > MAX_SOURCE_BYTES) {
            throw MediaException("Camera image exceeds the supported byte limit")
        }
        var decoded: Bitmap? = null
        try {
            val bytes = ByteArray(length)
            buffer.get(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
            validateSourceDimensions(bounds.outWidth, bounds.outHeight)
            decoded = BitmapFactory.decodeByteArray(bytes, 0, length,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw MediaException("Could not decode camera image")
            return encodeBitmap(decoded, image.cropRect, image.imageInfo.rotationDegrees, options)
        } catch (failure: RuntimeException) {
            throw MediaException("Could not decode camera image: ${failure.message}")
        } catch (_: OutOfMemoryError) {
            throw MediaException("Not enough memory for camera snapshot")
        } finally {
            decoded?.recycle()
        }
    }

    /** Keeps every pixel inside the shared crop; differing output ratios get black padding. */
    @Throws(MediaException::class)
    internal fun encodeBitmap(
        bitmap: Bitmap,
        crop: Rect,
        rotation: Int,
        options: CameraCaptureOptions.Snapshot,
    ): ByteArray {
        validateSourceDimensions(bitmap.width, bitmap.height)
        if (crop.isEmpty || crop.left < 0 || crop.top < 0 ||
            crop.right > bitmap.width || crop.bottom > bitmap.height) {
            throw MediaException("Invalid camera image crop")
        }
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw MediaException("Invalid camera image rotation")
        }
        if (options.width <= 0 || options.height <= 0 || options.width > 1280 ||
            options.height > 1280 || options.width.toLong() * options.height > CameraCaptureOptions.MAX_PIXELS ||
            options.quality !in 1..100) {
            throw MediaException("Invalid snapshot dimensions or quality")
        }
        var output: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            // Canvas filtering can sample outside a drawBitmap source Rect. Give the sampler
            // a bitmap whose boundary is the shared crop, so its edge pixels clamp there.
            cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
            output = Bitmap.createBitmap(options.width, options.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val source = RectF(0f, 0f, crop.width().toFloat(), crop.height().toFloat())
            val matrix = Matrix().apply { setRotate(rotation.toFloat()) }
            val rotated = RectF()
            matrix.mapRect(rotated, source)
            val scale = min(options.width / rotated.width(), options.height / rotated.height())
            matrix.postTranslate(-rotated.left, -rotated.top)
            matrix.postScale(scale, scale)
            val left = (options.width - rotated.width() * scale) / 2f
            val top = (options.height - rotated.height() * scale) / 2f
            matrix.postTranslate(left, top)
            canvas.clipRect(left, top, options.width - left, options.height - top)
            canvas.concat(matrix)
            canvas.drawBitmap(cropped, null, source, Paint(Paint.FILTER_BITMAP_FLAG))
            val format = when (options.format) {
                CameraCaptureOptions.Format.JPEG -> Bitmap.CompressFormat.JPEG
                CameraCaptureOptions.Format.PNG -> Bitmap.CompressFormat.PNG
            }
            val stream = BoundedSnapshotOutput()
            if (!output.compress(format, options.quality, stream)) {
                throw MediaException("Could not encode camera snapshot")
            }
            return stream.toByteArray()
        } catch (failure: IOException) {
            throw MediaException(failure.message)
        } catch (failure: RuntimeException) {
            throw MediaException("Could not encode camera snapshot: ${failure.message}")
        } catch (_: OutOfMemoryError) {
            throw MediaException("Not enough memory for camera snapshot")
        } finally {
            output?.recycle()
            if (cropped !== bitmap) cropped?.recycle()
        }
    }

    private fun validateSourceDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) {
            throw MediaException("Camera image exceeds the supported pixel limit")
        }
    }

    private class BoundedSnapshotOutput : ByteArrayOutputStream(16 * 1024) {
        override fun write(value: Int) {
            checkCapacity(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkCapacity(length)
            super.write(bytes, offset, length)
        }

        private fun checkCapacity(additional: Int) {
            if (additional > MAX_OUTPUT_BYTES - size()) {
                throw IOException("Camera snapshot exceeds the supported byte limit")
            }
        }
    }
}
