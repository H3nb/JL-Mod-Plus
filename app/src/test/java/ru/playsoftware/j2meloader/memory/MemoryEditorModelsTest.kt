/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEditorModelsTest {
    @Test fun watchPageParserRequiresOnlyFormattedPresentationFields() {
        assertTrue(MemoryWatchPageParser.parse(null).isEmpty())
        val parsed = MemoryWatchPageParser.parse(
            ids = longArrayOf(42L), values = arrayOf("30"), initialValues = arrayOf("10"),
            previousValues = arrayOf("20"), addresses = arrayOf("0x1234"),
            types = intArrayOf(MemoryEngineContract.TYPE_INT),
            states = intArrayOf(MemoryEngineContract.CANDIDATE_STABLE), relocations = intArrayOf(3),
            labels = arrayOf("HP"), freezeModes = intArrayOf(-1), freezePaused = booleanArrayOf(false),
        )
        assertEquals(42L, parsed.single().id)
        assertEquals("30", parsed.single().valueText)
        assertEquals("0x1234", parsed.single().addressText)
        assertEquals("HP", parsed.single().label)
    }

    @Test fun engineSessionMetadataMapsWithoutCountHeuristics() {
        assertEquals(MemorySessionStage.CANDIDATES, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_CANDIDATES))
        assertEquals(MemorySessionStage.UNKNOWN_BASELINE, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE))
        assertEquals(MemorySearchMode.GROUP, memorySearchModeFromEngine(MemoryEngineContract.SEARCH_MODE_GROUP))
        assertEquals(MemorySessionStage.EMPTY, memorySessionStageFromEngine(-1))
        assertEquals(MemorySearchMode.KNOWN, memorySearchModeFromEngine(-1))
    }


}
