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

/**
 * Immutable, generation-scoped selection state for the Apps destination.
 *
 * Selection uses the Room app id rather than the transient UI id assigned by
 * [AppsListFragment]. A non-null generation means that selection mode is
 * active, even when the selected set is empty (for example after Unselect all).
 */
data class LibrarySelectionState(
    val generation: Long? = null,
    val selectedAppIds: Set<Long> = emptySet(),
) {
    val isActive: Boolean
        get() = generation != null

    val selectedCount: Int
        get() = selectedAppIds.size

    fun enter(activeGeneration: Long, appId: Long): LibrarySelectionState =
        if (generation == activeGeneration) {
            copy(selectedAppIds = selectedAppIds + appId)
        } else {
            LibrarySelectionState(activeGeneration, setOf(appId))
        }

    fun toggle(activeGeneration: Long, appId: Long): LibrarySelectionState {
        if (generation != activeGeneration) return enter(activeGeneration, appId)
        return if (appId in selectedAppIds) {
            copy(selectedAppIds = selectedAppIds - appId)
        } else {
            copy(selectedAppIds = selectedAppIds + appId)
        }
    }

    /** Selects only the current visible projection and preserves hidden selections. */
    fun selectVisible(activeGeneration: Long, visibleAppIds: Iterable<Long>): LibrarySelectionState {
        if (generation != activeGeneration) {
            return LibrarySelectionState(activeGeneration, visibleAppIds.toSet())
        }
        return copy(selectedAppIds = selectedAppIds + visibleAppIds)
    }

    /** Clears only the current visible projection and preserves hidden selections. */
    fun unselectVisible(
        activeGeneration: Long,
        visibleAppIds: Iterable<Long>,
    ): LibrarySelectionState {
        if (generation != activeGeneration) return LibrarySelectionState()
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

    fun clear(): LibrarySelectionState = LibrarySelectionState()

    companion object {
        /** Save only primitive/string values so process recreation cannot retain a mutable collection. */
        val Saver: Saver<LibrarySelectionState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.generation?.toString().orEmpty(),
                    state.selectedAppIds.joinToString(","),
                )
            },
            restore = { values ->
                val generation = values.getOrNull(0)
                    ?.toString()
                    ?.takeIf(String::isNotEmpty)
                    ?.toLongOrNull()
                val selected = values.getOrNull(1)
                    ?.toString()
                    ?.split(',')
                    ?.asSequence()
                    ?.mapNotNull(String::toLongOrNull)
                    ?.toSet()
                    .orEmpty()
                LibrarySelectionState(generation, selected)
            },
        )
    }
}
