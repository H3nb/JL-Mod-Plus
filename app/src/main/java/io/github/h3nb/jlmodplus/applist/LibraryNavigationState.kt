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

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView

/** Explicit metadata-editor return position, independent of a Compose list/grid instance. */
data class LibraryScrollAnchor(
    val generation: Long,
    val stableItemId: Long?,
    /** scrollToItem offset: positive moves the item upward; negative places it below the origin. */
    val offsetPx: Int,
    val fallbackIndex: Int,
    /** Workdir identity for database-local item ids. */
    val libraryScope: String? = null,
)

data class LibraryNavigationState(
    val destination: LibraryDestinationKey = LibraryDestinationKey.Apps,
    val layout: LibraryLayout = LibraryLayout.List,
    val query: String = "",
    val quickView: LibraryQuickView = LibraryQuickView.All,
    val sortVariant: Int = 0,
    val selectedCollectionId: Long? = null,
    /** Stable workdir identity for [selectedCollectionId]; Room ids are local to one Library DB. */
    val selectedCollectionScope: String? = null,
    /** Full-screen Collection membership editor state; belongs to the selected Collection route. */
    val collectionManageApps: Boolean = false,
) {
    companion object {
        /** Keeps route state; native Lazy states own each destination's saved viewport. */
        val Saver: Saver<LibraryNavigationState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.destination.name,
                    state.layout.name,
                    state.query,
                    state.quickView.name,
                    state.sortVariant,
                    state.selectedCollectionId ?: Long.MIN_VALUE,
                    // Reserved legacy anchor slot: keep later route fields at their released indices.
                    emptyList<Any>(),
                    state.selectedCollectionScope.orEmpty(),
                    state.collectionManageApps,
                )
            },
            restore = { saved ->
                // The oldest shape contained only anchors. Those positions no longer belong to
                // route state; restore the default route rather than interpreting anchor entries.
                val routeState = saved.firstOrNull() is String
                val destination = saved.getOrNull(0)?.toString()?.let { value ->
                    // The third destination was previously called Options. Keep restored
                    // activity state on the same page after the visible tab is renamed More.
                    val compatibleValue = if (value == "Options") "More" else value
                    runCatching { LibraryDestinationKey.valueOf(compatibleValue) }.getOrNull()
                } ?: LibraryDestinationKey.Apps
                val layout = saved.getOrNull(1)?.toString()?.let {
                    runCatching { LibraryLayout.valueOf(it) }.getOrNull()
                } ?: LibraryLayout.List
                val query = saved.getOrNull(2) as? String ?: ""
                val quickView = saved.getOrNull(3)?.toString()?.let {
                    runCatching {
                        LibraryQuickView.valueOf(it)
                    }.getOrNull()
                } ?: LibraryQuickView.All
                val sortVariant = (saved.getOrNull(4) as? Number)?.toInt() ?: 0
                val selectedCollectionId = (saved.getOrNull(5) as? Number)?.toLong()
                    ?.takeUnless { it == Long.MIN_VALUE }
                val selectedCollectionScope = if (routeState) {
                    (saved.getOrNull(7) as? String)?.takeIf(String::isNotEmpty)
                } else {
                    null
                }
                val collectionManageApps = routeState && (saved.getOrNull(8) as? Boolean == true)
                LibraryNavigationState(
                    destination = destination,
                    layout = layout,
                    query = query,
                    quickView = quickView,
                    sortVariant = sortVariant,
                    selectedCollectionId = selectedCollectionId,
                    selectedCollectionScope = selectedCollectionScope,
                    collectionManageApps = collectionManageApps && selectedCollectionId != null,
                )
            },
        )
    }
}

/** Resolve an explicit editor-return anchor by item id, with a bounded fallback after removal. */
fun resolveLibraryScrollAnchor(
    anchor: LibraryScrollAnchor,
    availableIds: List<Long>,
): ResolvedLibraryScrollAnchor {
    val anchoredIndex = anchor.stableItemId?.let(availableIds::indexOf)?.takeIf { it >= 0 }
    val index = (anchoredIndex ?: anchor.fallbackIndex).coerceIn(
        0,
        (availableIds.size - 1).coerceAtLeast(0),
    )
    return ResolvedLibraryScrollAnchor(index, anchor.offsetPx)
}

data class ResolvedLibraryScrollAnchor(
    val index: Int,
    val offsetPx: Int,
)

enum class LibraryDestinationKey {
    Apps,
    Collections,
    More,
}
