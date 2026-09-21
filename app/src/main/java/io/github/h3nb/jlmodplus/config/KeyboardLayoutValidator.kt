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
import javax.microedition.lcdui.keyboard.RectSnap
import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard

/** Validates the on-disk keyboard artifact using the format consumed by VirtualKeyboard. */
internal object KeyboardLayoutValidator {
    private const val SIGNATURE = 0x564B4C00
    private const val CURRENT_VERSION = 3
    private const val EOF = -1
    private const val KEYS = 0
    private const val SCALES = 1
    private const val TYPE = 3
    private const val MAX_KEYS = 28
    private const val KEY_RECORD_SIZE_V1 = 20
    private const val KEY_RECORD_SIZE_V2 = 21
    private const val MAX_SCALE_VALUES_V3 = 12
    private const val MAX_LEGACY_SCALE_GROUPS = 6
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
                            if (variant > VirtualControlsKeyboard.TYPE_ANALOG_STANDARD) {
                                return "layout type is invalid"
                            }
                            skipFully(input, length - 1)
                            hasType = true
                        }
                        KEYS -> {
                            val itemSize = if (version >= 2) {
                                KEY_RECORD_SIZE_V2
                            } else {
                                KEY_RECORD_SIZE_V1
                            }
                            val keyCount = readCount(input, length, itemSize)
                            if (keyCount < 0 || keyCount > MAX_KEYS) {
                                return "layout key count is invalid"
                            }
                            repeat(keyCount) {
                                input.readInt() // key hash
                                if (version >= 2) input.readBoolean()
                                val snapOrigin = input.readInt()
                                val snapMode = input.readInt()
                                val offsetX = input.readFloat()
                                val offsetY = input.readFloat()
                                if (!offsetX.isFinite() || !offsetY.isFinite()) {
                                    return "layout key offset is invalid"
                                }
                                // SCREEN + NO_SNAP is accepted for recovery of already-broken
                                // Custom files. The runtime keeps its safe fallback topology.
                                if (snapMode != RectSnap.NO_SNAP) {
                                    if (snapOrigin < -1 || snapOrigin >= MAX_KEYS ||
                                        !isPersistableSnapMode(snapMode)
                                    ) {
                                        return "layout key snap state is invalid"
                                    }
                                }
                            }
                        }
                        SCALES -> {
                            val scaleCount = readCount(input, length, 4)
                            val maxScales = if (version >= 3) {
                                MAX_SCALE_VALUES_V3
                            } else {
                                MAX_LEGACY_SCALE_GROUPS
                            }
                            if (scaleCount < 0 || scaleCount > maxScales) {
                                return "layout scale count is invalid"
                            }
                            repeat(scaleCount) {
                                val scale = input.readFloat()
                                if (!scale.isFinite() || scale <= 0.0f) {
                                    return "layout scale value is invalid"
                                }
                            }
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

    private fun isPersistableSnapMode(mode: Int): Boolean {
        if (mode == RectSnap.NO_SNAP || (mode and RectSnap.FINE_MASK.inv()) != 0) return false
        val horizontal = mode and RectSnap.HORIZONTAL_MASK
        val vertical = mode and RectSnap.VERTICAL_MASK
        return Integer.bitCount(horizontal) == 1 && Integer.bitCount(vertical) == 1
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
