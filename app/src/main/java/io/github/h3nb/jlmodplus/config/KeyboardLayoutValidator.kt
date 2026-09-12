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

package io.github.h3nb.jlmodplus.config

import androidx.annotation.Nullable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Validates the on-disk keyboard artifact using the format consumed by VirtualKeyboard. */
internal object KeyboardLayoutValidator {
    private const val SIGNATURE = 0x564B4C00
    private const val CURRENT_VERSION = 3
    private const val EOF = -1
    private const val KEYS = 0
    private const val SCALES = 1
    private const val TYPE = 3
    private const val MAX_KEYS = 28
    private const val MAX_BLOCKS = 1024

    /** Returns null for a layout accepted by the runtime, or a short diagnostic otherwise. */
    @JvmStatic
    @Nullable
    fun validate(@Nullable file: File?): String? {
        if (file == null || !file.isFile || file.length() <= 0L) {
            return "layout file is missing or empty"
        }
        try {
            DataInputStream(FileInputStream(file)).use { input ->
                if (input.readInt() != SIGNATURE) {
                    return "layout signature is invalid"
                }
                val version = input.readInt()
                if (version < 1 || version > CURRENT_VERSION) {
                    return "layout version is unsupported"
                }
                var hasType = false
                for (blockIndex in 0 until MAX_BLOCKS) {
                    val block = input.readInt()
                    val length = input.readInt()
                    if (length < 0) return "layout block length is invalid"
                    when (block) {
                        EOF -> {
                            if (length != 0) return "layout end block is invalid"
                            return if (hasType) null else "layout type is missing"
                        }
                        TYPE -> {
                            if (length < 1) return "layout type block is empty"
                            val variant = input.readUnsignedByte()
                            if (variant > 6) return "layout type is invalid"
                            skipFully(input, length - 1)
                            hasType = true
                        }
                        KEYS -> {
                            val keyCount = readCount(input, length, if (version >= 2) 21 else 16)
                            if (keyCount < 0 || keyCount > MAX_KEYS) return "layout key count is invalid"
                            skipFully(input, length - 4)
                        }
                        SCALES -> {
                            val scaleCount = readCount(input, length, 4)
                            val maxScales = if (version >= 3) 6 else 12
                            if (scaleCount < 0 || scaleCount > maxScales) {
                                return "layout scale count is invalid"
                            }
                            skipFully(input, length - 4)
                        }
                        else -> skipFully(input, length)
                    }
                }
                return "layout contains too many blocks"
            }
        } catch (_: IOException) {
            return "layout cannot be read"
        } catch (_: RuntimeException) {
            return "layout cannot be read"
        }
    }

    @Throws(IOException::class)
    private fun readCount(input: DataInputStream, length: Int, itemSize: Int): Int {
        if (length < 4) return -1
        val count = input.readInt()
        val expected = 4L + count.toLong() * itemSize.toLong()
        return if (expected == length.toLong()) count else -1
    }

    @Throws(IOException::class)
    private fun skipFully(input: DataInputStream, bytes: Int) {
        if (bytes < 0) throw IOException("negative layout payload")
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) throw IOException("truncated layout payload")
            remaining -= skipped
        }
    }
}
