/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory

import io.github.h3nb.jlmodplus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryOperationMessagesTest {
    @Test
    fun missingRuntimeAndMissingUndoHistoryHaveDifferentRecoveryMeaning() {
        assertEquals(
            R.string.memory_editor_no_runtime,
            memoryOperationMessage(MemoryEngineContract.RESULT_NO_SESSION, MemoryEngineContract.REASON_NO_RUNTIME)?.resource,
        )
        assertEquals(
            R.string.memory_editor_no_search_history,
            memoryOperationMessage(MemoryEngineContract.RESULT_NO_SESSION, MemoryEngineContract.REASON_NO_SEARCH_HISTORY)?.resource,
        )
        assertEquals(
            R.string.memory_editor_invalid_request,
            memoryOperationMessage(MemoryEngineContract.RESULT_NO_SESSION, 999)?.resource,
        )
    }

    @Test
    fun inspectorUnconfirmedWriteNeverClaimsThatAnyWriteSucceeded() {
        val message = memoryOperationMessage(MemoryEngineContract.RESULT_PARTIAL_WRITE, unconfirmed = 1)!!
        assertEquals(R.string.memory_editor_write_failure_outcome, message.resource)
        assertEquals(R.string.memory_editor_partial_write, message.explanation)
        assertEquals(listOf(0, 1, 0, 0, 0), message.arguments)
    }

    @Test
    fun interruptedBatchRetainsTheCauseAndIndependentOutcomeCounts() {
        val message = memoryOperationMessage(
            MemoryEngineContract.RESULT_CANCELLED,
            written = 3, unconfirmed = 1, skipped = 2, notAttempted = 4, skippedByType = 5,
        )!!
        assertEquals(R.string.memory_editor_cancelled, message.explanation)
        assertEquals(listOf(3, 1, 2, 4, 5), message.arguments)
    }

    @Test
    fun successfulNoOpAndAllRowsSkippedByTypeRemainDifferent() {
        assertNull(memoryOperationMessage(MemoryEngineContract.RESULT_OK))
        val message = memoryOperationMessage(MemoryEngineContract.RESULT_OK, skippedByType = 3)!!
        assertEquals(R.string.memory_editor_write_outcome, message.resource)
        assertNull(message.explanation)
        assertEquals(listOf(0, 0, 0, 0, 3), message.arguments)
    }
}
