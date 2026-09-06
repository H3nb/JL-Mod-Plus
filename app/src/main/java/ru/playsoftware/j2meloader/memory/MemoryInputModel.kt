/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import java.math.BigInteger

internal enum class MemoryInputKind {
    SIGNED_INTEGER,
    UNSIGNED_INTEGER,
    FLOATING,
    POSITIVE_INTEGER,
}

/** Validation contract shared by the production search/edit/Inspector value fields. */
internal data class MemoryInputSpec(
    val kind: MemoryInputKind,
    val minLong: Long? = null,
    val maxLong: Long? = null,
    val floatingBits: Int = 64,
    val maxChars: Int = 48,
    val nonNegative: Boolean = false,
    val unsignedBits: Int? = null,
) {
    val signed: Boolean get() = kind == MemoryInputKind.SIGNED_INTEGER ||
        (kind == MemoryInputKind.FLOATING && !nonNegative)
    val decimal: Boolean get() = kind == MemoryInputKind.FLOATING
    val exponent: Boolean get() = kind == MemoryInputKind.FLOATING

    companion object {
        fun forType(type: Int): MemoryInputSpec = when (type) {
            MemoryEngineContract.TYPE_BYTE -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong(), maxChars = 4,
            )
            MemoryEngineContract.TYPE_SHORT -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong(), maxChars = 6,
            )
            MemoryEngineContract.TYPE_CHAR -> MemoryInputSpec(
                MemoryInputKind.UNSIGNED_INTEGER, 0L, 0xffffL, maxChars = 5,
            )
            MemoryEngineContract.TYPE_INT -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), maxChars = 11,
            )
            MemoryEngineContract.TYPE_LONG -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER, Long.MIN_VALUE, Long.MAX_VALUE, maxChars = 20,
            )
            MemoryEngineContract.TYPE_FLOAT -> MemoryInputSpec(MemoryInputKind.FLOATING, floatingBits = 32)
            MemoryEngineContract.TYPE_DOUBLE, MemoryEngineContract.TYPE_AUTO ->
                MemoryInputSpec(MemoryInputKind.FLOATING, floatingBits = 64)
            else -> MemoryInputSpec(MemoryInputKind.FLOATING)
        }

        fun positiveInteger(min: Long = 1, max: Long? = null): MemoryInputSpec = MemoryInputSpec(
            MemoryInputKind.POSITIVE_INTEGER, minLong = min, maxLong = max, maxChars = 12,
        )

        fun relativeMagnitudeForType(type: Int): MemoryInputSpec = when (type) {
            MemoryEngineContract.TYPE_BYTE -> MemoryInputSpec(
                MemoryInputKind.POSITIVE_INTEGER, 0L, 0xffL, maxChars = 3,
            )
            MemoryEngineContract.TYPE_SHORT,
            MemoryEngineContract.TYPE_CHAR -> MemoryInputSpec(
                MemoryInputKind.POSITIVE_INTEGER, 0L, 0xffffL, maxChars = 5,
            )
            MemoryEngineContract.TYPE_INT -> MemoryInputSpec(
                MemoryInputKind.POSITIVE_INTEGER, 0L, 0xffffffffL, maxChars = 10,
            )
            MemoryEngineContract.TYPE_LONG -> MemoryInputSpec(
                MemoryInputKind.POSITIVE_INTEGER, minLong = 0L, maxChars = 20, unsignedBits = 64,
            )
            MemoryEngineContract.TYPE_FLOAT -> MemoryInputSpec(
                MemoryInputKind.FLOATING, floatingBits = 32, nonNegative = true,
            )
            else -> MemoryInputSpec(
                MemoryInputKind.FLOATING, floatingBits = 64, nonNegative = true,
            )
        }
    }
}

internal fun MemoryInputSpec.acceptsPartial(text: String): Boolean {
    if (text.length > maxChars) return false
    if (text.isEmpty()) return true
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER -> text.indices.all { index ->
            val char = text[index]
            char.isDigit() || (char == '-' && index == 0)
        }
        MemoryInputKind.UNSIGNED_INTEGER, MemoryInputKind.POSITIVE_INTEGER -> text.all(Char::isDigit)
        MemoryInputKind.FLOATING -> acceptsPartialFloating(text, nonNegative)
    }
}

private fun acceptsPartialFloating(text: String, nonNegative: Boolean): Boolean {
    var seenDot = false
    var seenExponent = false
    var mantissaDigits = 0
    for (index in text.indices) {
        when (val char = text[index]) {
            in '0'..'9' -> if (!seenExponent) mantissaDigits++
            '-' -> if ((index == 0 && nonNegative) ||
                (index != 0 && text[index - 1] != 'e' && text[index - 1] != 'E')
            ) return false
            '+' -> if (index == 0 || (text[index - 1] != 'e' && text[index - 1] != 'E')) return false
            '.' -> {
                if (seenDot || seenExponent) return false
                seenDot = true
            }
            'e', 'E' -> {
                if (seenExponent || mantissaDigits == 0) return false
                seenExponent = true
            }
            else -> return false
        }
    }
    return true
}

internal fun MemoryInputSpec.isComplete(text: String): Boolean {
    if (text.isBlank() || !acceptsPartial(text)) return false
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER, MemoryInputKind.UNSIGNED_INTEGER, MemoryInputKind.POSITIVE_INTEGER -> {
            if (unsignedBits != null) {
                val value = runCatching { BigInteger(text) }.getOrNull() ?: return false
                val maximum = BigInteger.ONE.shiftLeft(unsignedBits).subtract(BigInteger.ONE)
                value.signum() >= 0 && value <= maximum
            } else {
                val value = text.toLongOrNull() ?: return false
                (minLong == null || value >= minLong) && (maxLong == null || value <= maxLong)
            }
        }
        MemoryInputKind.FLOATING -> if (floatingBits == 32) {
            text.toFloatOrNull()?.let { it.isFinite() && (!nonNegative || it >= 0f) } == true
        } else {
            text.toDoubleOrNull()?.let { it.isFinite() && (!nonNegative || it >= 0.0) } == true
        }
    }
}
