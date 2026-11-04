/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.memory

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryEditorModelsTest {
    @Test fun savedReboundHintExpiresWithoutLosingTheCurrentValue() {
        val bound = SavedVariableRuntimeValue(
            MemoryEngineContract.RESULT_OK,
            MemoryEngineContract.TYPE_INT,
            value = "190",
            rebound = true,
        )
        val first = savedValueAfterRefresh(bound, null, 1_000L, 4_000L)
        assertEquals(SavedVariableStatus.REBOUND, first.status)
        assertEquals(5_000L, first.reboundUntilUptimeMs)

        val stable = savedValueAfterRefresh(
            bound.copy(value = "191", rebound = false), first, 3_000L, 4_000L,
        )
        assertEquals(SavedVariableStatus.REBOUND, stable.status)
        assertEquals("191", stable.value)

        val expired = savedValueAfterRefresh(
            bound.copy(value = "192", rebound = false), stable, 5_000L, 4_000L,
        )
        assertEquals(SavedVariableStatus.RESOLVED, expired.status)
        assertEquals(0L, expired.reboundUntilUptimeMs)
        assertEquals("192", expired.value)

        val unavailable = savedValueAfterRefresh(
            SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST),
            first, 3_100L, 4_000L,
        )
        assertEquals(SavedVariableStatus.NOT_READY, unavailable.status)
        assertFalse(unavailable.rebound)
    }

    @Test fun sourceMetadataRequiresACompleteCheckedFieldDescriptor() {
        assertEquals(
            MemoryCandidateSource.UNKNOWN,
            MemoryCandidateSource.checked(MemoryEngineContract.SOURCE_UNKNOWN, "a", "aW", "[I"),
        )
        assertEquals(
            MemoryCandidateSource.UNKNOWN,
            MemoryCandidateSource.checked(MemoryEngineContract.SOURCE_STATIC_FIELD, "", "aW", "[I"),
        )
        val source = MemoryCandidateSource.checked(MemoryEngineContract.SOURCE_STATIC_FIELD, "com.game.a", "aW", "[I")
        assertEquals("a.aW", source.shortFieldName)
        assertEquals("com.game.a", source.declaringClass)
        assertEquals("[I", source.fieldType)
    }

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

    @Test fun knownAndUnknownSearchKeepPredicateFamiliesSeparate() {
        assertArrayEquals(
            intArrayOf(
                MemoryEngineContract.PREDICATE_EQUAL,
                MemoryEngineContract.PREDICATE_NOT_EQUAL,
                MemoryEngineContract.PREDICATE_GREATER,
                MemoryEngineContract.PREDICATE_LESS,
                MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
                MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
                MemoryEngineContract.PREDICATE_BETWEEN,
            ),
            memoryKnownSearchPredicates(),
        )
        assertTrue(
            memoryKnownSearchPredicates().toSet()
                .intersect(memoryUnknownSearchPredicates().toSet())
                .isEmpty(),
        )
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

    @Test fun onlyMagnitudeRelativePredicatesRequireAnInputValue() {
        assertFalse(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_CHANGED))
        assertFalse(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_UNCHANGED))
        assertFalse(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_INCREASED))
        assertFalse(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_DECREASED))
        assertTrue(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_INCREASED_BY))
        assertTrue(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_CHANGED_BY))
        assertTrue(memoryRelativePredicateNeedsValue(MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE))
    }

    @Test fun nextScanPayloadDropsValuesHiddenByTheSelectedPredicate() {
        assertEquals(
            MemoryNextScanInput("", ""),
            memoryNextScanInputForPredicate(
                MemoryEngineContract.PREDICATE_CHANGED,
                "10",
                "20",
            ),
        )
        assertEquals(
            MemoryNextScanInput("10", ""),
            memoryNextScanInputForPredicate(
                MemoryEngineContract.PREDICATE_INCREASED_BY,
                " 10 ",
                "20",
            ),
        )
        assertEquals(
            MemoryNextScanInput("10", "20"),
            memoryNextScanInputForPredicate(
                MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
                " 10 ",
                " 20 ",
            ),
        )
        assertEquals(
            MemoryNextScanInput("10", "20"),
            memoryNextScanInputForPredicate(
                MemoryEngineContract.PREDICATE_BETWEEN,
                " 10 ",
                " 20 ",
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

    @Test fun editorNavigationRestoresOnlyWithinOneRuntime() {
        val firstToken = 91_001L
        val nextToken = 91_002L
        assertEquals(MemoryEditorTab.RESULTS, MemoryEditorSessionNavigation.read(firstToken).tab)

        val saved = MemoryEditorNavigationState(MemoryEditorTab.SAVED, pageOffset = 100)
        MemoryEditorSessionNavigation.remember(firstToken, saved)
        assertEquals(saved, MemoryEditorSessionNavigation.read(firstToken))

        val inspector = MemoryEditorInspectorTarget(0L, "hp", watchAnchor = true)
        val opened = MemoryEditorNavigationState(
            MemoryEditorTab.INSPECTOR, pageOffset = 100, inspectorTarget = inspector,
        )
        MemoryEditorSessionNavigation.remember(firstToken, opened)
        assertEquals(MemoryEditorTab.SAVED, opened.underlyingTab)
        assertEquals(opened, MemoryEditorSessionNavigation.read(firstToken))

        assertEquals(MemoryEditorTab.RESULTS, MemoryEditorSessionNavigation.read(nextToken).tab)
        MemoryEditorSessionNavigation.remember(firstToken, opened)
        assertEquals(MemoryEditorTab.RESULTS, MemoryEditorSessionNavigation.read(nextToken).tab)
        assertEquals(MemoryEditorTab.RESULTS, MemoryEditorSessionNavigation.read(0L).tab)
    }

    @Test fun inspectorNavigationRetainsItsOriginForSafeFallback() {
        val result = MemoryEditorNavigationState(
            MemoryEditorTab.INSPECTOR,
            inspectorTarget = MemoryEditorInspectorTarget(42L, null, watchAnchor = false),
        )
        val watch = result.copy(inspectorTarget = result.inspectorTarget?.copy(watchAnchor = true))
        assertEquals(MemoryEditorTab.RESULTS, result.underlyingTab)
        assertEquals(MemoryEditorTab.WATCH, watch.underlyingTab)
        assertEquals(MemoryEditorTab.WATCH,
            MemoryEditorNavigationState(MemoryEditorTab.WATCH).underlyingTab)
    }

}
