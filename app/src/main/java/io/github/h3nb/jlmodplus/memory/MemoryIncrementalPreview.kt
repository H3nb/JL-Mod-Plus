/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory

import java.math.BigInteger

internal data class MemoryIncrementalPreview(val values: List<String>)

/** Keeps UI preview and backend arithmetic bounded by the displayed selection order. */
internal fun memoryIncrementalPreview(
    targets: List<MemoryEditTarget>,
    type: Int,
    base: String,
    step: String,
): MemoryIncrementalPreview? {
    if (targets.isEmpty() || targets.size > MemoryEngineContract.MAX_RESULT_PAGE_SIZE ||
        targets.any { it.watch || it.type != type } ||
        targets.map(MemoryEditTarget::id).toSet().size != targets.size ||
        base.length > MemoryEngineContract.MAX_IPC_INPUT_CHARS ||
        step.length > MemoryEngineContract.MAX_IPC_INPUT_CHARS
    ) return null
    val bounds = when (type) {
        MemoryEngineContract.TYPE_BYTE ->
            BigInteger.valueOf(Byte.MIN_VALUE.toLong()) to BigInteger.valueOf(Byte.MAX_VALUE.toLong())
        MemoryEngineContract.TYPE_SHORT ->
            BigInteger.valueOf(Short.MIN_VALUE.toLong()) to BigInteger.valueOf(Short.MAX_VALUE.toLong())
        MemoryEngineContract.TYPE_CHAR ->
            BigInteger.ZERO to BigInteger.valueOf(Char.MAX_VALUE.code.toLong())
        MemoryEngineContract.TYPE_INT ->
            BigInteger.valueOf(Int.MIN_VALUE.toLong()) to BigInteger.valueOf(Int.MAX_VALUE.toLong())
        MemoryEngineContract.TYPE_LONG ->
            BigInteger.valueOf(Long.MIN_VALUE) to BigInteger.valueOf(Long.MAX_VALUE)
        else -> return null
    }
    val first = base.trim().toBigIntegerOrNull() ?: return null
    val delta = step.trim().toBigIntegerOrNull() ?: return null
    return MemoryIncrementalPreview(buildList {
        for (index in targets.indices) {
            val value = first + delta * BigInteger.valueOf(index.toLong())
            if (value < bounds.first || value > bounds.second) return null
            add(value.toString())
        }
    })
}
