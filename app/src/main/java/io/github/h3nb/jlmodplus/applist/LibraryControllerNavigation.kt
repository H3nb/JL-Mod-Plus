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

/** Stable-ID focus bookkeeping shared by the Compose host adapter and its deterministic tests. */
internal data class LibraryControllerFocus(
    val index: Int,
    val databaseId: Long?,
)

internal fun reconcileLibraryControllerFocus(
    apps: List<LibraryAppUiItem>,
    focusedDatabaseId: Long?,
    fallbackIndex: Int,
): LibraryControllerFocus {
    if (apps.isEmpty()) return LibraryControllerFocus(0, null)
    val retainedIndex = focusedDatabaseId?.let { id ->
        apps.indexOfFirst { it.databaseId == id }.takeIf { it >= 0 }
    }
    val index = (retainedIndex ?: fallbackIndex).coerceIn(0, apps.lastIndex)
    return LibraryControllerFocus(index, apps[index].databaseId)
}

internal fun moveLibraryControllerFocusIndex(
    currentIndex: Int,
    itemCount: Int,
    layout: LibraryLayout,
    command: LibraryControllerCommand,
    columnCount: Int,
): Int {
    if (itemCount <= 0) return 0
    val current = currentIndex.coerceIn(0, itemCount - 1)
    val columns = columnCount.coerceAtLeast(1)
    return when (command) {
        LibraryControllerCommand.MoveUp -> if (layout == LibraryLayout.Grid) {
            val row = current / columns
            if (row == 0) current
            else ((row - 1) * columns + (current % columns)).coerceAtMost(itemCount - 1)
        } else {
            (current - 1).coerceAtLeast(0)
        }
        LibraryControllerCommand.MoveDown -> if (layout == LibraryLayout.Grid) {
            val row = current / columns
            val target = (row + 1) * columns + (current % columns)
            val lastRow = (itemCount - 1) / columns
            if (row >= lastRow) current else target.coerceAtMost(itemCount - 1)
        } else {
            (current + 1).coerceAtMost(itemCount - 1)
        }
        LibraryControllerCommand.MoveLeft -> if (layout == LibraryLayout.Grid) {
            if (current % columns == 0) current else current - 1
        } else {
            current
        }
        LibraryControllerCommand.MoveRight -> if (layout == LibraryLayout.Grid) {
            val rowEnd = minOf((current / columns + 1) * columns, itemCount) - 1
            if (current >= rowEnd) current else current + 1
        } else {
            current
        }
        else -> current
    }
}
