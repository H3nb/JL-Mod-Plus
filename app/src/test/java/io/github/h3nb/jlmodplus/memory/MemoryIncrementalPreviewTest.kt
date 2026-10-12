/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryIncrementalPreviewTest {
    private fun targets(type: Int = MemoryEngineContract.TYPE_INT) = listOf(
        MemoryEditTarget(30, type, "7"),
        MemoryEditTarget(10, type, "7"),
        MemoryEditTarget(20, type, "7"),
    )

    @Test
    fun previewPreservesVisibleCandidateOrderForPositiveAndNegativeSteps() {
        assertEquals(
            listOf("100", "102", "104"),
            memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_INT, "100", "2")?.values,
        )
        assertEquals(
            listOf("100", "98", "96"),
            memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_INT, "100", "-2")?.values,
        )
    }

    @Test
    fun previewRejectsOutOfRangeAndMixedTypesWithoutPartialSequence() {
        assertNull(
            memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_INT, "2147483647", "1"),
        )
        assertNull(
            memoryIncrementalPreview(targets(MemoryEngineContract.TYPE_CHAR),
                MemoryEngineContract.TYPE_CHAR, "0", "-1"),
        )
        assertNull(
            memoryIncrementalPreview(
                listOf(
                    MemoryEditTarget(1, MemoryEngineContract.TYPE_INT, "7"),
                    MemoryEditTarget(2, MemoryEngineContract.TYPE_BYTE, "7"),
                ),
                MemoryEngineContract.TYPE_INT, "7", "1",
            ),
        )
        assertNull(memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_FLOAT, "1", "1"))
    }

    @Test
    fun previewRejectsDuplicateCandidatesAndInvalidStep() {
        val duplicate = targets().take(2) + targets().first()
        assertNull(memoryIncrementalPreview(duplicate, MemoryEngineContract.TYPE_INT, "1", "1"))
        assertNull(memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_INT, "1", "abc"))
        assertNull(memoryIncrementalPreview(targets(), MemoryEngineContract.TYPE_INT, "1", ""))
    }
}
