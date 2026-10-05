/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory

import androidx.annotation.StringRes
import io.github.h3nb.jlmodplus.R

/** Presentation mapping only: backend diagnostic prose is deliberately not an input. */
internal data class MemoryOperationMessage(
    @get:StringRes val resource: Int,
    val arguments: List<Int> = emptyList(),
    @get:StringRes val explanation: Int? = null,
)

internal fun memoryOperationMessage(
    result: Int,
    reason: Int = MemoryEngineContract.REASON_NONE,
    written: Int = 0,
    unconfirmed: Int = 0,
    skipped: Int = 0,
    notAttempted: Int = 0,
    skippedByType: Int = 0,
): MemoryOperationMessage? {
    val resource = when (result) {
        MemoryEngineContract.RESULT_OK -> null
        MemoryEngineContract.RESULT_CANCELLED -> R.string.memory_editor_cancelled
        MemoryEngineContract.RESULT_RESOURCE_LIMIT -> R.string.memory_editor_resource_limit
        MemoryEngineContract.RESULT_TARGET_LOST -> R.string.memory_editor_engine_reconnecting
        MemoryEngineContract.RESULT_NO_SESSION -> when (reason) {
            MemoryEngineContract.REASON_NO_RUNTIME -> R.string.memory_editor_no_runtime
            MemoryEngineContract.REASON_NO_SEARCH_HISTORY -> R.string.memory_editor_no_search_history
            else -> R.string.memory_editor_invalid_request
        }
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE -> R.string.memory_editor_identity_unsafe
        MemoryEngineContract.RESULT_SAFETY_LIMIT -> R.string.memory_editor_safety_limit
        MemoryEngineContract.RESULT_UNSUPPORTED -> R.string.memory_editor_unsupported
        MemoryEngineContract.RESULT_PARTIAL_WRITE -> R.string.memory_editor_partial_write
        else -> R.string.memory_editor_invalid_request
    }
    // An unsafe/cancelled write can still have changed values. Keep its counters visible.
    if (written + unconfirmed + skipped + notAttempted + skippedByType > 0) {
        return MemoryOperationMessage(
            if (resource == null) R.string.memory_editor_write_outcome
            else R.string.memory_editor_write_failure_outcome,
            listOf(written, unconfirmed, skipped, notAttempted, skippedByType),
            resource,
        )
    }
    return resource?.let { MemoryOperationMessage(it) }
}
