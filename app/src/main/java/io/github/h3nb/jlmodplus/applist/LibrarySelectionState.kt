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

/**
 * Immutable, generation- and surface-scoped selection state shared by Library app surfaces.
 *
 * Selection uses the Room app id rather than the transient UI id assigned by
 * [AppsListFragment]. A non-null generation means that selection mode is active,
 * even when the selected set is empty (for example after Unselect all). When active,
 * a null [collectionId] identifies Apps; a non-null id identifies that Collection.
 */
data class LibrarySelectionState(
    val generation: Long? = null,
    val selectedAppIds: Set<Long> = emptySet(),
    val collectionId: Long? = null,
) {
    val isActive: Boolean
        get() = generation != null

    val selectedCount: Int
        get() = selectedAppIds.size

    private fun matchesScope(activeGeneration: Long, collectionId: Long?): Boolean =
        generation == activeGeneration && this.collectionId == collectionId

    fun enter(
        activeGeneration: Long,
        appId: Long,
        collectionId: Long? = null,
    ): LibrarySelectionState =
        if (matchesScope(activeGeneration, collectionId)) {
            copy(selectedAppIds = selectedAppIds + appId)
        } else {
            LibrarySelectionState(activeGeneration, setOf(appId), collectionId)
        }

    fun toggle(
        activeGeneration: Long,
        appId: Long,
        collectionId: Long? = null,
    ): LibrarySelectionState {
        if (!matchesScope(activeGeneration, collectionId)) {
            return enter(activeGeneration, appId, collectionId)
        }
        return if (appId in selectedAppIds) {
            copy(selectedAppIds = selectedAppIds - appId)
        } else {
            copy(selectedAppIds = selectedAppIds + appId)
        }
    }

    /** Selects only the current visible projection and preserves hidden selections. */
    fun selectVisible(
        activeGeneration: Long,
        visibleAppIds: Iterable<Long>,
        collectionId: Long? = null,
    ): LibrarySelectionState {
        if (!matchesScope(activeGeneration, collectionId)) {
            return LibrarySelectionState(activeGeneration, visibleAppIds.toSet(), collectionId)
        }
        return copy(selectedAppIds = selectedAppIds + visibleAppIds)
    }

    /** Clears only the current visible projection and preserves hidden selections. */
    fun unselectVisible(
        activeGeneration: Long,
        visibleAppIds: Iterable<Long>,
        collectionId: Long? = null,
    ): LibrarySelectionState {
        if (!matchesScope(activeGeneration, collectionId)) return LibrarySelectionState()
        return copy(selectedAppIds = selectedAppIds - visibleAppIds.toSet())
    }

    fun isAllVisibleSelected(visibleAppIds: Iterable<Long>): Boolean {
        val visible = visibleAppIds.toSet()
        return visible.isNotEmpty() && visible.all(selectedAppIds::contains)
    }

    /**
     * Keeps a selection only for the active generation. A workdir replacement
     * must never allow callbacks from the previous generation to target apps.
     */
    fun retainGeneration(activeGeneration: Long): LibrarySelectionState =
        takeIf { it.generation == activeGeneration } ?: LibrarySelectionState()

    /**
     * Reconciles selection against the authoritative rows for its surface.
     *
     * Filtered/search projections must not be passed here: hidden selections remain valid.
     * Rows absent from the authoritative projection are removed, while rows still present after
     * a failed bulk mutation remain selected so the user can retry.
     */
    fun retainAvailable(
        activeGeneration: Long,
        availableAppIds: Iterable<Long>,
        collectionId: Long? = null,
    ): LibrarySelectionState {
        if (!matchesScope(activeGeneration, collectionId)) return LibrarySelectionState()
        val retained = selectedAppIds.intersect(availableAppIds.toSet())
        return if (selectedAppIds.isNotEmpty() && retained.isEmpty()) {
            LibrarySelectionState()
        } else {
            copy(selectedAppIds = retained)
        }
    }

    fun clear(): LibrarySelectionState = LibrarySelectionState()

    companion object {
        /**
         * Save only primitive/string values so process recreation cannot retain mutable
         * collection state. The first two fields preserve compatibility with the pre-scope saver.
         */
        val Saver: Saver<LibrarySelectionState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.generation?.toString().orEmpty(),
                    state.selectedAppIds.joinToString(","),
                    state.collectionId?.toString().orEmpty(),
                )
            },
            restore = { values ->
                val generation = values.getOrNull(0)
                    ?.takeIf(String::isNotEmpty)
                    ?.toLongOrNull()
                if (generation == null) {
                    LibrarySelectionState()
                } else {
                    val selected = values.getOrNull(1)
                        ?.split(',')
                        ?.asSequence()
                        ?.mapNotNull(String::toLongOrNull)
                        ?.toSet()
                        .orEmpty()
                    val collectionId = values.getOrNull(2)
                        ?.takeIf(String::isNotEmpty)
                        ?.toLongOrNull()
                    LibrarySelectionState(generation, selected, collectionId)
                }
            },
        )
    }
}
