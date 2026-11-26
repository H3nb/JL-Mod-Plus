/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory

import io.github.h3nb.jlmodplus.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    fun revertRemovalFailureExplainsThatGameMemoryMayAlreadyBeRestored() {
        val message = memoryOperationMessage(
            MemoryEngineContract.RESULT_PARTIAL_WRITE,
            MemoryEngineContract.REASON_REVERT_REMOVE_COMMIT_FAILED,
            written = 2,
        )!!
        assertEquals(R.string.memory_editor_revert_remove_commit_failed, message.resource)
    }

    @Test
    fun confirmedInspectorEditWithFailedFreezeHasSpecificRecoveryMessage() {
        val message = memoryOperationMessage(
            MemoryEngineContract.RESULT_PARTIAL_WRITE,
            MemoryEngineContract.REASON_INSPECTOR_FREEZE_START_FAILED,
            written = 1,
        )!!
        assertEquals(R.string.memory_editor_edit_freeze_failed, message.resource)
    }

    @Test
    fun staleOrMissingRecipeSnapshotRequiresRefresh() {
        for (result in listOf(MemoryEngineContract.RESULT_INVALID_REQUEST,
            MemoryEngineContract.RESULT_IDENTITY_UNSAFE)) {
            assertEquals(
                R.string.memory_scripts_refresh_required,
                memoryOperationMessage(result, MemoryEngineContract.REASON_RECIPE_REFRESH_REQUIRED)?.resource,
            )
        }
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
    fun partialRecipeWriteSeparatesConfirmedUnconfirmedAndUntouchedValues() {
        val message = memoryOperationMessage(
            MemoryEngineContract.RESULT_PARTIAL_WRITE,
            written = 2, unconfirmed = 1, notAttempted = 4,
        )!!
        assertEquals(R.string.memory_editor_write_failure_outcome, message.resource)
        assertEquals(R.string.memory_editor_partial_write, message.explanation)
        assertEquals(listOf(2, 1, 0, 4, 0), message.arguments)
    }

    @Test
    fun confirmedWriteHasACompactReadbackMessage() {
        val message = memoryOperationMessage(MemoryEngineContract.RESULT_OK, written = 5)!!
        assertEquals(R.string.memory_editor_write_confirmed, message.resource)
        assertEquals(listOf(5), message.arguments)
        assertNull(message.explanation)
    }

    @Test
    fun successfulNoOpAndAllRowsSkippedByTypeRemainDifferent() {
        assertNull(memoryOperationMessage(MemoryEngineContract.RESULT_OK))
        val message = memoryOperationMessage(MemoryEngineContract.RESULT_OK, skippedByType = 3)!!
        assertEquals(R.string.memory_editor_write_outcome, message.resource)
        assertNull(message.explanation)
        assertEquals(listOf(0, 0, 0, 0, 3), message.arguments)
    }

    @Test
    fun scriptFailuresUseRecoveryForScriptTargetsRatherThanSearchResults() {
        val cases = listOf(
            MemoryEngineContract.RESULT_RESOURCE_LIMIT to R.string.memory_scripts_resource_limit,
            MemoryEngineContract.RESULT_SAFETY_LIMIT to R.string.memory_scripts_resource_limit,
            MemoryEngineContract.RESULT_TARGET_LOST to R.string.memory_editor_memory_unavailable,
            MemoryEngineContract.RESULT_IDENTITY_UNSAFE to R.string.memory_scripts_target_changed,
            MemoryEngineContract.RESULT_UNSUPPORTED to R.string.memory_scripts_operation_unavailable,
        )
        for ((result, resource) in cases) {
            assertEquals(resource, memoryOperationMessage(result, script = true)!!.resource)
        }
        assertEquals(R.string.memory_editor_identity_unsafe,
            memoryOperationMessage(MemoryEngineContract.RESULT_IDENTITY_UNSAFE)!!.resource)
    }

    @Test
    fun scriptFailuresRetainWriteCountsAndSpecificRefreshReason() {
        val outcome = memoryOperationMessage(
            MemoryEngineContract.RESULT_IDENTITY_UNSAFE, written = 1,
            unconfirmed = 2, notAttempted = 3, script = true,
        )!!
        assertEquals(R.string.memory_editor_write_failure_outcome, outcome.resource)
        assertEquals(R.string.memory_scripts_target_changed, outcome.explanation)
        assertEquals(listOf(1, 2, 0, 3, 0), outcome.arguments)
        assertEquals(R.string.memory_scripts_refresh_required, memoryOperationMessage(
            MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
            reason = MemoryEngineContract.REASON_RECIPE_REFRESH_REQUIRED, script = true,
        )!!.resource)
    }

}
