/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config

import androidx.annotation.Nullable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.microedition.lcdui.keyboard.RectSnap
import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard
import javax.microedition.lcdui.keyboard.VirtualKeyboard

/** Validates the on-disk keyboard artifact using the format consumed by VirtualKeyboard. */
internal object KeyboardLayoutValidator {
    private const val SIGNATURE = 0x564B4C00
    private const val CURRENT_VERSION = 4
    private const val EOF = -1
    private const val KEYS = 0
    private const val SCALES = 1
    private const val TYPE = 3
    private const val BASE_VARIANT = 4
    private const val LEGACY_SHARED = 5
    private const val PORTRAIT_OVERRIDE = 6
    private const val LANDSCAPE_OVERRIDE = 7

    private const val TYPE_CUSTOM = 0
    private const val MAX_KEYS = 28
    private const val KEY_RECORD_SIZE_V1 = 20
    private const val KEY_RECORD_SIZE_V2 = 21
    private const val MAX_SCALE_VALUES_V3 = 12
    private const val MAX_LEGACY_SCALE_GROUPS = 6
    private const val MAX_BLOCKS = 1024
    private const val V4_SCALE_COUNT = 12
    private const val V4_SNAPSHOT_LENGTH =
        4 + MAX_KEYS * KEY_RECORD_SIZE_V2 + 4 + V4_SCALE_COUNT * 4 + 2 + 6 * 4

    /** Returns null for a layout accepted by the runtime, or a short diagnostic otherwise. */
    @JvmStatic
    @Nullable
    fun validate(@Nullable file: File?): String? {
        if (file == null || !file.isFile || file.length() <= 0L) {
            return "layout file is missing or empty"
        }
        try {
            DataInputStream(FileInputStream(file)).use { input ->
                if (input.readInt() != SIGNATURE) return "layout signature is invalid"
                val version = input.readInt()
                if (version < 1 || version > CURRENT_VERSION) {
                    return "layout version is unsupported"
                }
                return if (version == 4) validateV4(input) else validateLegacy(input, version)
            }
        } catch (_: IOException) {
            return "layout cannot be read"
        } catch (_: RuntimeException) {
            return "layout cannot be read"
        }
    }

    private fun validateLegacy(input: DataInputStream, version: Int): String? {
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
                    val itemSize = if (version >= 2) KEY_RECORD_SIZE_V2 else KEY_RECORD_SIZE_V1
                    val keyCount = readCount(input, length, itemSize)
                    if (keyCount < 0 || keyCount > MAX_KEYS) {
                        return "layout key count is invalid"
                    }
                    repeat(keyCount) {
                        input.readInt()
                        if (version >= 2) input.readBoolean()
                        val snapOrigin = input.readInt()
                        val snapMode = input.readInt()
                        val offsetX = input.readFloat()
                        val offsetY = input.readFloat()
                        if (!offsetX.isFinite() || !offsetY.isFinite()) {
                            return "layout key offset is invalid"
                        }
                        if (snapOrigin < -1 || snapOrigin >= MAX_KEYS) {
                            return "layout key snap origin is invalid"
                        }
                        // Preserve PR #131 recovery support for already-broken legacy NO_SNAP files.
                        if (snapMode != RectSnap.NO_SNAP && !isPersistableSnapMode(snapMode)) {
                            return "layout key snap state is invalid"
                        }
                    }
                }
                SCALES -> {
                    val scaleCount = readCount(input, length, 4)
                    val maxScales =
                        if (version >= 3) MAX_SCALE_VALUES_V3 else MAX_LEGACY_SCALE_GROUPS
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

    private fun validateV4(input: DataInputStream): String? {
        var type: Int? = null
        var baseVariant: Int? = null
        var legacyShared = false
        var portraitOverride = false
        var landscapeOverride = false

        for (blockIndex in 0 until MAX_BLOCKS) {
            val block = input.readInt()
            val length = input.readInt()
            if (length < 0) return "layout block length is invalid"
            when (block) {
                EOF -> {
                    if (length != 0) return "layout end block is invalid"
                    val layoutType = type ?: return "layout type is missing"
                    if (layoutType != TYPE_CUSTOM) {
                        return if (baseVariant != null || legacyShared ||
                            portraitOverride || landscapeOverride) {
                            "non-Custom v4 layout contains Custom state"
                        } else null
                    }
                    if (baseVariant != null && legacyShared) {
                        return "Custom v4 mixes base and legacy fallback"
                    }
                    val portraitRenderable =
                        portraitOverride || baseVariant != null || legacyShared
                    val landscapeRenderable =
                        landscapeOverride || baseVariant != null || legacyShared
                    return if (portraitRenderable && landscapeRenderable) null
                    else "Custom v4 has an orientation without a source"
                }
                TYPE -> {
                    if (type != null || length != 1) return "layout type block is invalid"
                    val value = input.readUnsignedByte()
                    if (value > VirtualControlsKeyboard.TYPE_ANALOG_STANDARD) {
                        return "layout type is invalid"
                    }
                    type = value
                }
                BASE_VARIANT -> {
                    if (baseVariant != null || length != 1) {
                        return "layout base block is invalid"
                    }
                    val value = input.readUnsignedByte()
                    if (value !in 1..VirtualControlsKeyboard.TYPE_ANALOG_STANDARD) {
                        return "layout base type is invalid"
                    }
                    baseVariant = value
                }
                LEGACY_SHARED -> {
                    if (legacyShared) return "layout legacy fallback is duplicated"
                    validateV4Snapshot(input, length)?.let { return it }
                    legacyShared = true
                }
                PORTRAIT_OVERRIDE -> {
                    if (portraitOverride) return "layout portrait override is duplicated"
                    validateV4Snapshot(input, length)?.let { return it }
                    portraitOverride = true
                }
                LANDSCAPE_OVERRIDE -> {
                    if (landscapeOverride) return "layout landscape override is duplicated"
                    validateV4Snapshot(input, length)?.let { return it }
                    landscapeOverride = true
                }
                KEYS, SCALES -> return "legacy layout blocks are invalid in v4"
                else -> skipFully(input, length)
            }
        }
        return "layout contains too many blocks"
    }

    private fun validateV4Snapshot(input: DataInputStream, length: Int): String? {
        if (length != V4_SNAPSHOT_LENGTH) return "layout orientation payload length is invalid"
        val keyCount = input.readInt()
        if (keyCount != MAX_KEYS) return "layout orientation key count is invalid"

        val origins = IntArray(MAX_KEYS)
        val modes = IntArray(MAX_KEYS)
        val seenKeys = BooleanArray(MAX_KEYS)
        repeat(MAX_KEYS) {
            val hash = input.readInt()
            val keyIndex = VirtualKeyboard.persistedKeyIndexForHash(hash)
            if (keyIndex !in 0 until MAX_KEYS || seenKeys[keyIndex]) {
                return "layout orientation key identity is invalid"
            }
            input.readBoolean()
            val origin = input.readInt()
            val mode = input.readInt()
            val x = input.readFloat()
            val y = input.readFloat()
            if (origin < -1 || origin >= MAX_KEYS) {
                return "layout orientation snap origin is invalid"
            }
            if (!isPersistableSnapMode(mode)) {
                return "layout orientation snap mode is invalid"
            }
            if (!x.isFinite() || !y.isFinite()) {
                return "layout orientation snap offset is invalid"
            }
            seenKeys[keyIndex] = true
            origins[keyIndex] = origin
            modes[keyIndex] = mode
        }
        if (seenKeys.any { !it }) return "layout orientation key identity is incomplete"
        if (!hasValidTopology(origins, modes)) {
            return "layout orientation snap topology is invalid"
        }

        val scaleCount = input.readInt()
        if (scaleCount != V4_SCALE_COUNT) return "layout orientation scale count is invalid"
        repeat(scaleCount) {
            val scale = input.readFloat()
            if (!scale.isFinite() || scale <= 0.0f) {
                return "layout orientation scale value is invalid"
            }
        }

        val dpadEnabled = input.readUnsignedByte()
        val analogEnabled = input.readUnsignedByte()
        if (dpadEnabled !in 0..1 || analogEnabled !in 0..1) {
            return "layout grouped enabled flag is invalid"
        }
        repeat(2) {
            val x = input.readFloat()
            val y = input.readFloat()
            val radius = input.readFloat()
            if (!x.isFinite() || !y.isFinite() || !radius.isFinite() ||
                x !in 0.0f..1.0f || y !in 0.0f..1.0f ||
                radius <= 0.0f || radius > 0.5f) {
                return "layout grouped geometry is invalid"
            }
        }
        return null
    }

    private fun hasValidTopology(origins: IntArray, modes: IntArray): Boolean {
        for (index in origins.indices) {
            if (!isPersistableSnapMode(modes[index])) return false
            val origin = origins[index]
            if (origin != -1 && (origin !in origins.indices || origin == index)) return false
        }
        for (start in origins.indices) {
            val visited = BooleanArray(origins.size)
            var current = start
            while (current != -1) {
                if (current !in origins.indices || visited[current]) return false
                visited[current] = true
                current = origins[current]
            }
        }
        return true
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
        if (count < 0) return -1
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
