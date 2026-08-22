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

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Stable surfaces whose scroll anchors must survive destination replacement. */
enum class LibraryNavigationSurface {
    AppsList,
    AppsGrid,
    CollectionsList,
    CollectionAppsList,
    CollectionAppsGrid,
}

/**
 * A return anchor independent from a Compose list/grid instance.
 *
 * [stableItemId] is preferred. [fallbackIndex] is used only when the item was
 * deleted or is filtered out, and [offsetPx] keeps the practical visual
 * position instead of forcing a return to the top.
 */
data class LibraryScrollAnchor(
    val generation: Long,
    val stableItemId: Long?,
    val offsetPx: Int,
    val fallbackIndex: Int,
)

data class LibraryNavigationState(
    val destination: LibraryDestinationKey = LibraryDestinationKey.Apps,
    val layout: LibraryLayout = LibraryLayout.List,
    val query: String = "",
    val quickView: ru.playsoftware.j2meloader.librarydb.LibraryQuickView =
        ru.playsoftware.j2meloader.librarydb.LibraryQuickView.All,
    val sortVariant: Int = 0,
    val selectedCollectionId: Long? = null,
    val anchors: Map<LibraryNavigationSurface, LibraryScrollAnchor> = emptyMap(),
) {
    companion object {
        /**
         * Keeps return anchors across activity recreation without asking Android to parcel
         * Compose implementation details such as LazyListState.
         */
        val Saver: Saver<LibraryNavigationState, Any> = listSaver(
            save = { state ->
                state.anchors.entries.map { (surface, anchor) ->
                    listOf(
                        surface.name,
                        anchor.generation,
                        anchor.stableItemId ?: Long.MIN_VALUE,
                        anchor.offsetPx,
                        anchor.fallbackIndex,
                    )
                }
            },
            restore = { saved ->
                val anchors = saved.mapNotNull { value ->
                    val entry = value as? List<*> ?: return@mapNotNull null
                    val surface = entry.getOrNull(0)?.toString()?.let {
                        runCatching { LibraryNavigationSurface.valueOf(it) }.getOrNull()
                    } ?: return@mapNotNull null
                    val generation = (entry.getOrNull(1) as? Number)?.toLong() ?: return@mapNotNull null
                    val stableId = (entry.getOrNull(2) as? Number)?.toLong()
                        ?.takeUnless { it == Long.MIN_VALUE }
                    val offset = (entry.getOrNull(3) as? Number)?.toInt() ?: return@mapNotNull null
                    val fallbackIndex = (entry.getOrNull(4) as? Number)?.toInt() ?: return@mapNotNull null
                    surface to LibraryScrollAnchor(generation, stableId, offset, fallbackIndex)
                }.toMap()
                LibraryNavigationState(anchors = anchors)
            },
        )
    }

    fun saveAnchor(
        surface: LibraryNavigationSurface,
        anchor: LibraryScrollAnchor,
    ): LibraryNavigationState = copy(anchors = anchors + (surface to anchor))

    fun anchorFor(
        surface: LibraryNavigationSurface,
        activeGeneration: Long,
    ): LibraryScrollAnchor? = anchors[surface]?.takeIf { it.generation == activeGeneration }

    fun resolveAnchor(
        surface: LibraryNavigationSurface,
        activeGeneration: Long,
        availableIds: List<Long>,
    ): ResolvedLibraryScrollAnchor? {
        val anchor = anchorFor(surface, activeGeneration) ?: return null
        val anchoredIndex = anchor.stableItemId?.let(availableIds::indexOf)?.takeIf { it >= 0 }
        val index = (anchoredIndex ?: anchor.fallbackIndex).coerceIn(
            0,
            (availableIds.size - 1).coerceAtLeast(0),
        )
        return ResolvedLibraryScrollAnchor(index, anchor.offsetPx)
    }
}

data class ResolvedLibraryScrollAnchor(
    val index: Int,
    val offsetPx: Int,
)

enum class LibraryDestinationKey {
    Apps,
    Collections,
    Options,
}
