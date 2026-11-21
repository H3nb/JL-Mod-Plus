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

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Keeps controller navigation on the search results, never on the hidden Browse projection. */
@Composable
internal fun rememberLibraryInlineSearchControllerFocus(
    active: Boolean,
    apps: List<LibraryAppUiItem>,
    layout: LibraryLayout,
    listState: LazyListState,
    gridState: LazyGridState,
    headerHeightPx: Int,
    events: Flow<LibraryControllerEvent>?,
    resolveUiId: (Long) -> Int?,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
): Long? {
    var focusedId by remember { mutableStateOf<Long?>(null) }
    var focusVisible by remember { mutableStateOf(false) }
    val currentApps by rememberUpdatedState(apps)
    val currentLayout by rememberUpdatedState(layout)
    val currentHeaderHeight by rememberUpdatedState(headerHeightPx)
    val currentOpenApp by rememberUpdatedState(onOpenApp)
    val currentOpenActions by rememberUpdatedState(onOpenActions)
    val currentResolveUiId by rememberUpdatedState(resolveUiId)

    LaunchedEffect(active, apps) {
        if (!active) {
            focusVisible = false
        } else if (apps.none { it.databaseId == focusedId }) {
            focusedId = apps.firstOrNull()?.databaseId
        }
    }
    LaunchedEffect(active, events) {
        if (!active) return@LaunchedEffect
        events?.collect { event ->
            val rows = currentApps
            if (rows.isEmpty()) return@collect
            val index = rows.indexOfFirst { it.databaseId == focusedId }.coerceAtLeast(0)
            when (event.command) {
                LibraryControllerCommand.MoveUp,
                LibraryControllerCommand.MoveDown,
                LibraryControllerCommand.MoveLeft,
                LibraryControllerCommand.MoveRight -> {
                    val columns = if (currentLayout == LibraryLayout.Grid) {
                        val cells = gridState.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
                        val firstY = cells.firstOrNull()?.offset?.y
                        if (firstY == null) 1
                        else cells.count { kotlin.math.abs(it.offset.y - firstY) <= 1 }
                            .coerceAtLeast(1)
                    } else 1
                    val delta = when (event.command) {
                        LibraryControllerCommand.MoveUp -> -columns
                        LibraryControllerCommand.MoveDown -> columns
                        LibraryControllerCommand.MoveLeft -> -1
                        else -> 1
                    }
                    val next = (index + delta).coerceIn(0, rows.lastIndex)
                    focusedId = rows[next].databaseId
                    focusVisible = true
                    val target = next + 1 // The Library chrome occupies index zero.
                    if (currentLayout == LibraryLayout.Grid) {
                        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                            gridState.scrollToItem(target, -currentHeaderHeight)
                        }
                    } else if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                        listState.scrollToItem(target, -currentHeaderHeight)
                    }
                }
                LibraryControllerCommand.Activate,
                LibraryControllerCommand.OpenActions -> {
                    val app = rows[index]
                    val id = currentResolveUiId(app.databaseId) ?: app.id.takeIf { it != 0 }
                    if (id != null) {
                        if (event.command == LibraryControllerCommand.Activate) currentOpenApp(id)
                        else currentOpenActions(app.copy(id = id))
                    }
                }
                LibraryControllerCommand.Back,
                LibraryControllerCommand.NextTab,
                LibraryControllerCommand.PreviousTab -> Unit
            }
        }
    }
    return focusedId.takeIf { active && focusVisible }
}
