/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

/** Compact GameGuardian-style query syntax used by the production overlay search field. */
internal sealed interface MemorySearchExpression {
    data class Single(val value: String) : MemorySearchExpression
    data class Group(val values: List<String>, val maxDistance: Int) : MemorySearchExpression
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
 * Supported forms:
 *   500
 *   500;1000
 *   500;1000:128
 *
 * `::` is intentionally recognized and rejected explicitly rather than silently changing its
 * meaning. The current native group kernel does not expose ordered-group semantics yet.
 */
internal fun parseMemorySearchExpression(input: String): MemorySearchExpression {
    val text = input.trim()
    if (text.isEmpty()) return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.EMPTY)
    if ("::" in text) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.ORDERED_GROUP_UNSUPPORTED)
    }
    if (';' !in text) {
        return if (':' in text) {
            MemorySearchExpression.Invalid(MemorySearchExpression.Reason.UNEXPECTED_RANGE)
        } else {
            MemorySearchExpression.Single(text)
        }
    }

    val rangeIndex = text.lastIndexOf(':')
    val groupText: String
    val distance: Int
    if (rangeIndex >= 0) {
        groupText = text.substring(0, rangeIndex).trim()
        val distanceText = text.substring(rangeIndex + 1).trim()
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
    return MemorySearchExpression.Group(values, distance)
}
