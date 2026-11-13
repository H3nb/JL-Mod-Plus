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

package io.github.h3nb.jlmodplus.applist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryControllerNavigationTest {
    @Test
    fun stableDatabaseIdSurvivesSortChanges() {
        val ids = listOf(30L, 10L, 20L)

        assertEquals(
            LibraryControllerFocus(index = 1, databaseId = 10L),
            reconcileLibraryControllerFocus(ids, focusedDatabaseId = 10L, fallbackIndex = 0),
        )
    }

    @Test
    fun removedFocusFallsBackToNearestVisibleIndex() {
        val ids = listOf(10L, 30L)

        assertEquals(
            LibraryControllerFocus(index = 1, databaseId = 30L),
            reconcileLibraryControllerFocus(ids, focusedDatabaseId = 20L, fallbackIndex = 1),
        )
    }

    @Test
    fun unchangedFocusInLargeCatalogUsesOnlyIndexedAccess() {
        var reads = 0
        val ids = object : AbstractList<Long>() {
            override val size = 50_000
            override fun get(index: Int): Long {
                reads++
                return index.toLong() + 1
            }
        }
        assertEquals(
            LibraryControllerFocus(index = 45_000, databaseId = 45_001L),
            reconcileLibraryControllerFocus(ids, focusedDatabaseId = 45_001L, fallbackIndex = 45_000),
        )
        assertTrue(reads <= 2, "Retained focus should not scan 50,000 items")
    }

    @Test
    fun relocatedFocusSearchesDatabaseIdsAfterSort() {
        val ids = (1L..50_000L).toMutableList()
        ids[9] = 45_001L
        ids[45_000] = 10L

        assertEquals(
            LibraryControllerFocus(index = 9, databaseId = 45_001L),
            reconcileLibraryControllerFocus(ids, focusedDatabaseId = 45_001L, fallbackIndex = 45_000),
        )
    }

    @Test
    fun listMovementIsBoundedAndHorizontalDoesNotChangeItem() {
        assertEquals(
            2,
            moveLibraryControllerFocusIndex(
                currentIndex = 1,
                itemCount = 3,
                layout = LibraryLayout.List,
                command = LibraryControllerCommand.MoveDown,
                columnCount = 1,
            ),
        )
        assertEquals(
            0,
            moveLibraryControllerFocusIndex(
                currentIndex = 0,
                itemCount = 3,
                layout = LibraryLayout.List,
                command = LibraryControllerCommand.MoveUp,
                columnCount = 1,
            ),
        )
        assertEquals(
            1,
            moveLibraryControllerFocusIndex(
                currentIndex = 1,
                itemCount = 3,
                layout = LibraryLayout.List,
                command = LibraryControllerCommand.MoveRight,
                columnCount = 1,
            ),
        )
    }

    @Test
    fun gridMovementUsesVisibleColumnCount() {
        assertEquals(
            4,
            moveLibraryControllerFocusIndex(
                currentIndex = 1,
                itemCount = 8,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveDown,
                columnCount = 3,
            ),
        )
        assertEquals(
            0,
            moveLibraryControllerFocusIndex(
                currentIndex = 1,
                itemCount = 8,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveLeft,
                columnCount = 3,
            ),
        )
    }

    @Test
    fun gridMovementStaysWithinRowAndFindsNearestItemWhenLastRowIsShort() {
        assertEquals(
            2,
            moveLibraryControllerFocusIndex(
                currentIndex = 2,
                itemCount = 5,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveRight,
                columnCount = 3,
            ),
        )
        assertEquals(
            4,
            moveLibraryControllerFocusIndex(
                currentIndex = 2,
                itemCount = 5,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveDown,
                columnCount = 3,
            ),
        )
        assertEquals(
            3,
            moveLibraryControllerFocusIndex(
                currentIndex = 3,
                itemCount = 5,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveDown,
                columnCount = 3,
            ),
        )
        assertEquals(
            1,
            moveLibraryControllerFocusIndex(
                currentIndex = 4,
                itemCount = 5,
                layout = LibraryLayout.Grid,
                command = LibraryControllerCommand.MoveUp,
                columnCount = 3,
            ),
        )
    }

}
