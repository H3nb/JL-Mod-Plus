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

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryControllerNavigationTest {
    @Test
    fun stableDatabaseIdSurvivesSortChanges() {
        val apps = listOf(
            app(30L, 3),
            app(10L, 1),
            app(20L, 2),
        )

        assertEquals(
            LibraryControllerFocus(index = 1, databaseId = 10L),
            reconcileLibraryControllerFocus(apps, focusedDatabaseId = 10L, fallbackIndex = 0),
        )
    }

    @Test
    fun removedFocusFallsBackToNearestVisibleIndex() {
        val apps = listOf(app(10L, 1), app(30L, 3))

        assertEquals(
            LibraryControllerFocus(index = 1, databaseId = 30L),
            reconcileLibraryControllerFocus(apps, focusedDatabaseId = 20L, fallbackIndex = 1),
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

    private fun app(databaseId: Long, id: Int): LibraryAppUiItem = LibraryAppUiItem(
        id = id,
        title = "App $id",
        author = "Author",
        version = "1",
        iconPath = null,
        canReinstall = false,
        databaseId = databaseId,
    )
}
