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

    @Test fun resultRowsKeepAliasTypesWithoutRawAddresses() {
        fun row(id: Long, types: IntArray) = MemoryResultRow(
            id = id, valueText = "7", addressText = "0x1000",
            aliasMask = types.fold(0) { mask, type -> mask or (1 shl type) },
            primaryType = types.first(), state = MemoryEngineContract.CANDIDATE_STABLE, relocations = 0,
        )
        val rows = listOf(
            row(1, intArrayOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_FLOAT)),
            row(3, intArrayOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_LONG)),
        )
        assertEquals(listOf(MemoryEngineContract.TYPE_INT), commonTypesForSelection(rows, setOf(1, 3)))
    }

    @Test fun engineSessionMetadataMapsWithoutCountHeuristics() {
        assertEquals(MemorySessionStage.CANDIDATES, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_CANDIDATES))
        assertEquals(MemorySessionStage.UNKNOWN_BASELINE, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE))
        assertEquals(MemorySearchMode.GROUP, memorySearchModeFromEngine(MemoryEngineContract.SEARCH_MODE_GROUP))
        assertEquals(MemorySessionStage.EMPTY, memorySessionStageFromEngine(-1))
        assertEquals(MemorySearchMode.KNOWN, memorySearchModeFromEngine(-1))
    }

    @Test fun newSearchNeverForwardsARelativeRefinePredicate() {
        assertEquals(MemoryEngineContract.PREDICATE_BETWEEN, newSearchPredicate(MemoryEngineContract.PREDICATE_BETWEEN))
        assertEquals(MemoryEngineContract.PREDICATE_EQUAL, newSearchPredicate(MemoryEngineContract.PREDICATE_CHANGED))
        assertEquals(MemoryEngineContract.PREDICATE_EQUAL, newSearchPredicate(MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE))
    }
}
