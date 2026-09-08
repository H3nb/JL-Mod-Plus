/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("0x1234", parsed.single().locationText)
        assertEquals("HP", parsed.single().label)
    }

    @Test fun engineSessionMetadataMapsWithoutCountHeuristics() {
        assertEquals(MemorySessionStage.CANDIDATES, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_CANDIDATES))
        assertEquals(MemorySessionStage.UNKNOWN_BASELINE, memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE))
        assertEquals(MemorySearchMode.GROUP, memorySearchModeFromEngine(MemoryEngineContract.SEARCH_MODE_GROUP))
        assertEquals(MemorySessionStage.EMPTY, memorySessionStageFromEngine(-1))
        assertEquals(MemorySearchMode.KNOWN, memorySearchModeFromEngine(-1))
    }

    @Test fun activeSearchIncludesUnknownBaselineForKnownNextScan() {
        assertFalse(memorySessionHasActiveSearch(MemorySessionStage.EMPTY))
        assertTrue(memorySessionHasActiveSearch(MemorySessionStage.UNKNOWN_BASELINE))
        assertTrue(memorySessionHasActiveSearch(MemorySessionStage.CANDIDATES))
    }

    @Test fun unknownSearchOnlyOffersRelativePredicates() {
        assertArrayEquals(
            intArrayOf(
                MemoryEngineContract.PREDICATE_CHANGED,
                MemoryEngineContract.PREDICATE_UNCHANGED,
                MemoryEngineContract.PREDICATE_INCREASED,
                MemoryEngineContract.PREDICATE_DECREASED,
                MemoryEngineContract.PREDICATE_INCREASED_BY,
                MemoryEngineContract.PREDICATE_DECREASED_BY,
                MemoryEngineContract.PREDICATE_CHANGED_BY,
                MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
                MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE,
            ),
            memoryUnknownSearchPredicates(),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_CHANGED,
            memoryUnknownPredicateOrDefault(MemoryEngineContract.PREDICATE_EQUAL),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_INCREASED_BY,
            memoryUnknownPredicateOrDefault(MemoryEngineContract.PREDICATE_INCREASED_BY),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_INCREASED_BY,
            memoryUnknownPredicateForRuntime(
                MemoryEngineContract.PREDICATE_INCREASED_BY,
                newRuntime = false,
            ),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_CHANGED,
            memoryUnknownPredicateForRuntime(
                MemoryEngineContract.PREDICATE_INCREASED_BY,
                newRuntime = true,
            ),
        )
    }

    @Test fun unknownPredicatePreferenceSurvivesControllerRecreationForRuntimeToken() {
        val runtimeA = 0x7A11L
        val runtimeB = 0x7A12L

        MemoryEditorRuntimePreferences.setUnknownPredicate(
            runtimeA,
            MemoryEngineContract.PREDICATE_DECREASED,
        )

        assertEquals(
            MemoryEngineContract.PREDICATE_DECREASED,
            MemoryEditorRuntimePreferences.unknownPredicate(runtimeA),
        )
        // A new controller for the same MIDlet session reads the process-local value again.
        assertEquals(
            MemoryEngineContract.PREDICATE_DECREASED,
            MemoryEditorRuntimePreferences.unknownPredicate(runtimeA),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_CHANGED,
            MemoryEditorRuntimePreferences.unknownPredicate(runtimeB),
        )
    }

    @Test fun managedBaselineCountIsPresentedOnlyForKnownManagedBaseline() {
        val managedBaseline = MemoryEditorUiState(
            sessionStage = MemorySessionStage.UNKNOWN_BASELINE,
            searchScope = MemoryEngineContract.SCOPE_MANAGED_JAVA,
            managedBaselineCount = 184_732L,
        )
        assertEquals(184_732L, managedBaselineCountForPresentation(managedBaseline))
        assertEquals(184_732L, managedBaselineCountForPresentation(
            managedBaseline.copy(searchScope = MemoryEngineContract.SCOPE_MANAGED_JAVA),
        ))
        assertNull(managedBaselineCountForPresentation(
            managedBaseline.copy(managedBaselineCount = 0L),
        ))
        assertNull(managedBaselineCountForPresentation(
            managedBaseline.copy(sessionStage = MemorySessionStage.CANDIDATES),
        ))
    }

}
