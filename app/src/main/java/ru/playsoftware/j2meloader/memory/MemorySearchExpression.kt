/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory

/** Compact group query syntax used by the production overlay search field. */
internal sealed interface MemorySearchExpression {
    data class Single(val value: String) : MemorySearchExpression

    data class Group(val values: List<String>) : MemorySearchExpression

    data class Invalid(val reason: Reason) : MemorySearchExpression

    enum class Reason {
        EMPTY,
        TOO_MANY_VALUES,
        EMPTY_GROUP_VALUE,
        UNEXPECTED_RANGE,
    }
}

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
 *
 * Group terms are resolved against one Managed Java owner. Address-distance modifiers are rejected
 * instead of being silently ignored.
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

    if (':' in text) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.UNEXPECTED_RANGE)
    }

    val values = text.split(';').map(String::trim)
    if (values.size !in 2..MemoryEngineContract.MAX_GROUP_VALUES) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.TOO_MANY_VALUES)
    }
    if (values.any(String::isEmpty)) {
        return MemorySearchExpression.Invalid(MemorySearchExpression.Reason.EMPTY_GROUP_VALUE)
    }
    return MemorySearchExpression.Group(values)
}
