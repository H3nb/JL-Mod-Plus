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

package ru.playsoftware.j2meloader.applist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionStateTest {
    @Test
    fun enterAndToggleUseStableIdsAndKeepModeAfterLastUnselect() {
        val entered = LibrarySelectionState().enter(7L, 42L)
        assertTrue(entered.isActive)
        assertEquals(setOf(42L), entered.selectedAppIds)

        val toggled = entered.toggle(7L, 42L)
        assertTrue(toggled.isActive)
        assertTrue(toggled.selectedAppIds.isEmpty())

        assertEquals(setOf(42L), toggled.toggle(7L, 42L).selectedAppIds)
    }

    @Test
    fun visibleSelectAllAndClearPreserveSelectionsOutsideSearchProjection() {
        val state = LibrarySelectionState(7L, setOf(2L))
        val selected = state.selectVisible(7L, listOf(4L, 6L, 4L))
        assertEquals(setOf(2L, 4L, 6L), selected.selectedAppIds)
        assertTrue(selected.isAllVisibleSelected(listOf(4L, 6L)))

        val cleared = selected.unselectVisible(7L, listOf(4L, 6L))
        assertEquals(setOf(2L), cleared.selectedAppIds)
        assertFalse(cleared.isAllVisibleSelected(listOf(4L, 6L)))
    }

    @Test
    fun generationChangesDiscardPreviousSelection() {
        val state = LibrarySelectionState(7L, setOf(42L))
        val entered = state.enter(8L, 99L)
        assertEquals(8L, entered.generation)
        assertEquals(setOf(99L), entered.selectedAppIds)
        assertEquals(LibrarySelectionState(), entered.retainGeneration(7L))
    }

    @Test
    fun saverRestoresPrimitiveValuesWithoutMutableCollectionState() {
        val state = LibrarySelectionState(9L, linkedSetOf(3L, 8L))
        val restored = LibrarySelectionState.Saver.restore(listOf("9", "3,8"))
        assertEquals(state, restored)
        assertNull(LibrarySelectionState.Saver.restore(listOf("", ""))?.generation)
    }
}
