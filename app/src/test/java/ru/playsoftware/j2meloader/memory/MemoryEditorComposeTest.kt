/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEditorComposeTest {
    @Test
    fun watchPageParserRequiresOnlyFormattedPresentationFields() {
        assertTrue(MemoryWatchPageParser.parse(null).isEmpty())

        val parsed = MemoryWatchPageParser.parse(
            ids = longArrayOf(42L),
            values = arrayOf("30"),
            initialValues = arrayOf("10"),
            previousValues = arrayOf("20"),
            addresses = arrayOf("0x1234"),
            types = intArrayOf(MemoryEngineContract.TYPE_INT),
            states = intArrayOf(MemoryEngineContract.CANDIDATE_STABLE),
            relocations = intArrayOf(3),
            labels = arrayOf("HP"),
            freezeModes = intArrayOf(-1),
            freezePaused = booleanArrayOf(false),
        )

        assertEquals(1, parsed.size)
        assertEquals(42L, parsed.single().id)
        assertEquals("30", parsed.single().valueText)
        assertEquals("10", parsed.single().initialValueText)
        assertEquals("20", parsed.single().previousValueText)
        assertEquals("0x1234", parsed.single().addressText)
        assertEquals("HP", parsed.single().label)
        assertEquals(3, parsed.single().relocations)
    }

    @Test
    fun resultRowsKeepAliasTypesWithoutRawAddresses() {
        fun row(id: Long, types: IntArray) = MemoryResultRow(
            id = id,
            valueText = "7",
            addressText = "0x1000",
            aliasMask = types.fold(0) { mask, type -> mask or (1 shl type) },
            primaryType = types.first(),
            state = MemoryEngineContract.CANDIDATE_STABLE,
            relocations = 0,
        )
        val rows = listOf(
            row(1, intArrayOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_FLOAT)),
            row(3, intArrayOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_LONG)),
        )

        assertEquals(
            listOf(MemoryEngineContract.TYPE_INT),
            commonTypesForSelection(
                rows,
                setOf(1, 3),
            ),
        )
    }

    @Test
    fun groupParserAcceptsOnlyTwoToEightExplicitTypedValues() {
        val parsed = parseGroup("Dword:100, Word:50, Word unsigned:12")!!
        assertArrayEquals(
            intArrayOf(
                MemoryEngineContract.TYPE_INT,
                MemoryEngineContract.TYPE_SHORT,
                MemoryEngineContract.TYPE_CHAR,
            ),
            parsed.first,
        )
        assertArrayEquals(arrayOf("100", "50", "12"), parsed.second)
        assertNull(parseGroup("Int:100"))
        assertNull(parseGroup("Auto:1, Int:2"))
        assertNull(parseGroup("Int:1, Unknown:2"))
    }

    @Test
    fun engineSessionMetadataMapsWithoutCountHeuristics() {
        assertEquals(
            MemorySessionStage.CANDIDATES,
            memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_CANDIDATES),
        )
        assertEquals(
            MemorySessionStage.UNKNOWN_BASELINE,
            memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE),
        )
        assertEquals(
            MemorySearchMode.GROUP,
            memorySearchModeFromEngine(MemoryEngineContract.SEARCH_MODE_GROUP),
        )
        assertEquals(MemorySessionStage.EMPTY, memorySessionStageFromEngine(-1))
        assertEquals(MemorySearchMode.KNOWN, memorySearchModeFromEngine(-1))
    }

    @Test
    fun newSearchNeverForwardsARelativeRefinePredicate() {
        assertEquals(
            MemoryEngineContract.PREDICATE_BETWEEN,
            newSearchPredicate(MemoryEngineContract.PREDICATE_BETWEEN),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_EQUAL,
            newSearchPredicate(MemoryEngineContract.PREDICATE_CHANGED),
        )
        assertEquals(
            MemoryEngineContract.PREDICATE_EQUAL,
            newSearchPredicate(MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE),
        )
    }
}
