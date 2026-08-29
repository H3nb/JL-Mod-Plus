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
    fun resultPageParserRequiresAnExactCompleteShape() {
        assertTrue(MemoryEditorPageParser.parse(longArrayOf(1L, 2L)).isEmpty())
        assertTrue(MemoryEditorPageParser.parse(longArrayOf(-1L)).isEmpty())

        val rows = longArrayOf(
            1L,
            42L, 0x1234L, 0x1200L,
            MemoryEngineContract.TYPE_INT.toLong(),
            MemoryEngineContract.CANDIDATE_STABLE.toLong(),
            3L, 10L, 20L, 30L,
        )
        val parsed = MemoryEditorPageParser.parse(rows)
        assertEquals(1, parsed.size)
        assertEquals(42L, parsed.single().id)
        assertEquals(0x1234L, parsed.single().address)
        assertEquals(3, parsed.single().relocations)
        assertEquals("30", MemoryEditorPageParser.value(parsed.single()))
    }

    @Test
    fun valueFormatterPreservesPrimitiveInterpretation() {
        fun row(type: Int, bits: Long) = MemoryCandidateRow(
            1, 2, 0, type, MemoryEngineContract.CANDIDATE_STABLE, 0, 0, 0, bits,
        )
        assertEquals("-1", MemoryEditorPageParser.value(row(MemoryEngineContract.TYPE_BYTE, 0xff)))
        assertEquals("65535", MemoryEditorPageParser.value(row(MemoryEngineContract.TYPE_CHAR, -1)))
        assertEquals("1.5", MemoryEditorPageParser.value(row(
            MemoryEngineContract.TYPE_FLOAT,
            1.5f.toBits().toLong(),
        )))
        assertEquals("-2.25", MemoryEditorPageParser.value(row(
            MemoryEngineContract.TYPE_DOUBLE,
            (-2.25).toBits(),
        )))
    }

    @Test
    fun resultRowsAreGroupedByRawAddressWithoutLosingTypeAliases() {
        fun row(id: Long, address: Long, type: Int) = MemoryCandidateRow(
            id, address, 0, type, MemoryEngineContract.CANDIDATE_STABLE, 0, 0, 0, 7,
        )
        val groups = groupCandidateRows(listOf(
            row(1, 0x1000, MemoryEngineContract.TYPE_INT),
            row(2, 0x1000, MemoryEngineContract.TYPE_FLOAT),
            row(3, 0x2000, MemoryEngineContract.TYPE_LONG),
        ))

        assertEquals(2, groups.size)
        assertEquals(0x1000, groups.first().address)
        assertEquals(2, groups.first().aliases.size)
        assertEquals(
            listOf(MemoryEngineContract.TYPE_INT),
            commonTypesForSelection(
                listOf(
                    row(1, 0x1000, MemoryEngineContract.TYPE_INT),
                    row(2, 0x1000, MemoryEngineContract.TYPE_FLOAT),
                    row(3, 0x2000, MemoryEngineContract.TYPE_INT),
                ),
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
