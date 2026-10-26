/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.filepicker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.h3nb.jlmodplus.util.SvgRasterizer
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun isPickerImage(name: String): Boolean =
    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> true
        else -> false
    }

private val pickerDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

/** Session-owned local image decoding. Never affects what the picker may select. */
internal class FilePickerImageLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile private var closed = false

    suspend fun load(path: String, requestedSizePx: Int): Bitmap? = withContext(pickerDecodeDispatcher) {
        if (closed || requestedSizePx < 1) return@withContext null
        try {
            val file = File(path)
            if (!file.isFile || !isPickerImage(file.name)) return@withContext null
            val size = requestedSizePx.coerceAtMost(1024)
            val length = file.length()
            val svg = SvgRasterizer.isSvg(file)
            // Source-size gates limit preview work, not the caller's file-selection contract.
            val maxSource = if (svg) SvgRasterizer.MAX_SOURCE_BYTES.toLong() else 64L * 1024 * 1024
            if (length <= 0 || length > maxSource) return@withContext null
            val key = "${file.absolutePath}:${file.lastModified()}:$length:$size"
            synchronized(cache) { cache.get(key) }?.let { return@withContext it }

            val bitmap = if (svg) SvgRasterizer.read(file).renderWithin(size, size)
            else decodeRaster(file, size)
            if (bitmap != null && !closed) {
                synchronized(cache) { if (!closed) cache.put(key, bitmap) }
            }
            bitmap
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun close() {
        closed = true
        synchronized(cache) { cache.evictAll() }
    }

    private fun decodeRaster(file: File, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetSize ||
            bounds.outHeight / sampleSize > targetSize) {
            if (sampleSize >= (1 shl 29)) return null
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
