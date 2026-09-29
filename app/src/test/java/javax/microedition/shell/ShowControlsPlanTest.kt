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

package javax.microedition.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowControlsPlanTest {
    @Test
    fun canonicalThirtyControlsUseSemanticGroupsWithoutChangingIndexIdentity() {
        val names = canonicalNames() + listOf("Localized D-pad", "Localized Analog")
        val plan = ShowControlsPlan.resolve(names)

        assertTrue(plan.semantic)
        assertEquals(
            listOf(
                listOf(12, 27, 13),
                listOf(25, 28, 26),
                listOf(15, 29, 14),
            ),
            plan.actionRows,
        )
        assertEquals(
            listOf(
                listOf(16, 17, 18),
                listOf(19, 24, 20),
                listOf(21, 22, 23),
            ),
            plan.directionalRows,
        )
        assertEquals(
            listOf(
                listOf(0, 1, 2),
                listOf(3, 4, 5),
                listOf(6, 7, 8),
                listOf(10, 9, 11),
            ),
            plan.numericRows,
        )

        assertEquals(
            listOf(
                listOf("L", "M", "R"),
                listOf("A", "Localized D-pad", "B"),
                listOf("C", "Localized Analog", "D"),
            ),
            labels(names, plan.actionRows),
        )
        assertEquals(
            listOf(
                listOf("↖", "↑", "↗"),
                listOf("←", "F", "→"),
                listOf("↙", "↓", "↘"),
            ),
            labels(names, plan.directionalRows),
        )
        assertEquals(
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#"),
            ),
            labels(names, plan.numericRows),
        )
    }

    @Test
    fun canonicalLegacyControlsKeepGroupedControlGaps() {
        val plan = ShowControlsPlan.resolve(canonicalNames())

        assertTrue(plan.semantic)
        assertNull(plan.actionRows[1][1])
        assertNull(plan.actionRows[2][1])
        assertEquals(25, plan.actionRows[1][0])
        assertEquals(26, plan.actionRows[1][2])
        assertEquals(15, plan.actionRows[2][0])
        assertEquals(14, plan.actionRows[2][2])
    }

    @Test
    fun unknownKeyboardShapeFallsBackToSequentialIndexOrder() {
        val plan = ShowControlsPlan.resolve(listOf("First", "Second", "Third", "Fourth"))

        assertFalse(plan.semantic)
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(3, null, null)),
            plan.actionRows,
        )
        assertTrue(plan.directionalRows.isEmpty())
        assertTrue(plan.numericRows.isEmpty())
    }

    private fun canonicalNames() = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*", "#",
        "L", "R", "D", "C", "↖", "↑", "↗", "←", "→", "↙", "↓", "↘",
        "F", "A", "B", "M",
    )

    private fun labels(names: List<String>, rows: List<List<Int?>>) =
        rows.map { row -> row.map { index -> index?.let(names::get) } }
}
