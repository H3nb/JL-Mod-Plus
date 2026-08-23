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

package ru.playsoftware.j2meloader.librarydb

/**
 * Stable, generation-scoped projection of a UI selection. The transient Compose id never crosses
 * this boundary: callers pass repository ids and receive rows in deterministic title/id order.
 */
internal data class LibraryBulkSelectionPlan(
    val generation: Long,
    val requestedAppIds: List<Long>,
    val apps: List<LibraryAppRow>,
    val missingAppIds: Set<Long>,
) {
    val isComplete: Boolean
        get() = missingAppIds.isEmpty() && apps.size == requestedAppIds.size

    val storageKeys: List<String>
        get() = apps.map(LibraryAppRow::storageKey)
}

internal object LibraryBulkSelectionPlanner {
    fun plan(
        generation: Long,
        requestedAppIds: Iterable<Long>,
        availableApps: Iterable<LibraryAppRow>,
    ): LibraryBulkSelectionPlan {
        val requested = requestedAppIds.toSet().toList().sorted()
        val requestedSet = requested.toSet()
        val apps = availableApps
            .filter { it.id in requestedSet }
            .distinctBy(LibraryAppRow::id)
            .sortedWith { left, right ->
                left.title.compareTo(right.title, ignoreCase = true).takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            }
        val found = apps.asSequence().map(LibraryAppRow::id).toSet()
        return LibraryBulkSelectionPlan(
            generation = generation,
            requestedAppIds = requested,
            apps = apps,
            missingAppIds = requestedSet - found,
        )
    }
}

enum class LibraryBulkItemStatus {
    Succeeded,
    Failed,
    Skipped,
}

data class LibraryBulkItemResult(
    val appId: Long,
    val storageKey: String,
    val title: String,
    val status: LibraryBulkItemStatus,
    val detail: String? = null,
)

data class LibraryBulkOperationResult(
    val generation: Long,
    val items: List<LibraryBulkItemResult>,
    val missingAppIds: Set<Long> = emptySet(),
) {
    val succeeded: List<LibraryBulkItemResult>
        get() = items.filter { it.status == LibraryBulkItemStatus.Succeeded }

    val failed: List<LibraryBulkItemResult>
        get() = items.filter { it.status == LibraryBulkItemStatus.Failed }

    val skipped: List<LibraryBulkItemResult>
        get() = items.filter { it.status == LibraryBulkItemStatus.Skipped }

    val hasRetryableItems: Boolean
        get() = failed.isNotEmpty() || missingAppIds.isNotEmpty()
}
