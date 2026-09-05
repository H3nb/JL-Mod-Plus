/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory

/** Compact GameGuardian-style query syntax used by the production overlay search field. */
internal sealed interface MemorySearchExpression {
    data class Single(val value: String) : MemorySearchExpression

    /**
     * maxDistance is the engine argument. Positive values mean the established any-order group;
     * negative values mean ordered-group semantics. Keeping the encoding here avoids widening the
     * Binder/native ABI while the public query syntax remains unambiguous (`:` vs `::`).
     */
    data class Group(val values: List<String>, val maxDistance: Int) : MemorySearchExpression {
        val ordered: Boolean get() = maxDistance < 0
        val displayDistance: Int get() = kotlin.math.abs(maxDistance)
    }

    data class Invalid(val reason: Reason) : MemorySearchExpression

    enum class Reason {
        EMPTY,
        TOO_MANY_VALUES,
        EMPTY_GROUP_VALUE,
        INVALID_DISTANCE,
        ORDERED_GROUP_UNSUPPORTED,
        UNEXPECTED_RANGE,
    }
}

internal const val DEFAULT_GROUP_DISTANCE = 128
internal const val MAX_GROUP_DISTANCE = 4096

/**
 * Group Search uses one exact type for every term in the compact expression. When the user leaves
 * the type at Auto, infer the narrowest practical common type instead of silently disabling New
 * Search. Explicit type selections remain authoritative.
 */
internal fun inferMemoryGroupType(values: List<String>): Int? {
    if (values.isEmpty()) return null

    val integers = values.map(String::toLongOrNull)
    if (integers.all { it != null }) {
        return if (integers.all { it!! in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) {
            MemoryEngineContract.TYPE_INT
        } else {
            MemoryEngineContract.TYPE_LONG
        }
    }

    return if (values.all { it.toDoubleOrNull()?.isFinite() == true }) {
        MemoryEngineContract.TYPE_DOUBLE
    } else {
        null
    }
}

/**
 * Supported forms:
 *   500
 *   500;1000
 *   500;1000:128      // any order within the existing symmetric anchor window
 *   500;1000::128     // in order, strictly increasing addresses within a forward window
 *
 * Ordered state is encoded as a negative engine distance internally. User-entered distances remain
 * positive and bounded to 1..4096 bytes.
 */
internal fun parseMemorySearchExpression(input: String): MemorySearchExpression {
    val text = input.trim()
    if (text.isEmpty()) return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.EMPTY)
    if (';' !in text) {
        return if (':' in text) {
            MemorySearchExpression.Invalid(MemorySearchExpression.Reason.UNEXPECTED_RANGE)
        } else {
            MemorySearchExpression.Single(text)
        }
    }

    val orderedIndex = text.lastIndexOf("::")
    val ordered = orderedIndex >= 0
    val rangeIndex = if (ordered) orderedIndex else text.lastIndexOf(':')
    val delimiterLength = if (ordered) 2 else if (rangeIndex >= 0) 1 else 0

    val groupText: String
    val distance: Int
    if (rangeIndex >= 0) {
        groupText = text.substring(0, rangeIndex).trim()
        val distanceText = text.substring(rangeIndex + delimiterLength).trim()
        // A second colon outside the selected delimiter is always malformed rather than silently
        // changing ordered/any-order semantics.
        if (':' in groupText || ':' in distanceText) {
            return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.INVALID_DISTANCE)
        }
        distance = distanceText.toIntOrNull()
            ?.takeIf { it in 1..MAX_GROUP_DISTANCE }
            ?: return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.INVALID_DISTANCE)
    } else {
        groupText = text
        distance = DEFAULT_GROUP_DISTANCE
    }

    val values = groupText.split(';').map(String::trim)
    if (values.size !in 2..MemoryEngineContract.MAX_GROUP_VALUES) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.TOO_MANY_VALUES)
    }
    if (values.any(String::isEmpty)) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.EMPTY_GROUP_VALUE)
    }
    return MemorySearchExpression.Group(values, if (ordered) -distance else distance)
}
