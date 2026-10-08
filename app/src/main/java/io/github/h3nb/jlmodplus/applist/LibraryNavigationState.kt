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

/** Only routing belongs here. App filter/sort/layout are owned by LibraryUiState. */
data class LibraryNavigationState(
    val destination: LibraryDestinationKey = LibraryDestinationKey.Apps,
    val selectedCollectionId: Long? = null,
    /** Stable workdir identity for [selectedCollectionId]; Room ids are local to one Library DB. */
    val selectedCollectionScope: String? = null,
    /** Full-screen membership editor belongs to the selected Collection route. */
    val collectionManageApps: Boolean = false,
) {
    companion object {
        /** Accept the previous nine-slot saved state without retaining duplicated presentation state. */
        val Saver: Saver<LibraryNavigationState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.destination.name,
                    state.selectedCollectionId ?: Long.MIN_VALUE,
                    state.selectedCollectionScope.orEmpty(),
                    state.collectionManageApps,
                )
            },
            restore = { saved ->
                val destination = (saved.firstOrNull() as? String)?.let { value ->
                    val compatible = if (value == "Options") "More" else value
                    runCatching { LibraryDestinationKey.valueOf(compatible) }.getOrNull()
                } ?: LibraryDestinationKey.Apps
                // The previous format placed Layout.name in slot 1; newer state places an id.
                // Older anchor-only state is neither format and restores the default route.
                val legacyRoute = saved.getOrNull(1) is String
                val idSlot = if (legacyRoute) 5 else 1
                val scopeSlot = if (legacyRoute) 7 else 2
                val manageSlot = if (legacyRoute) 8 else 3
                val collectionId = (saved.getOrNull(idSlot) as? Number)?.toLong()
                    ?.takeUnless { it == Long.MIN_VALUE }
                LibraryNavigationState(
                    destination = destination,
                    selectedCollectionId = collectionId,
                    selectedCollectionScope = (saved.getOrNull(scopeSlot) as? String)
                        ?.takeIf(String::isNotEmpty),
                    collectionManageApps = saved.getOrNull(manageSlot) == true &&
                        collectionId != null,
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
