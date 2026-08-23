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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBulkOperationModelsTest {
    @Test
    fun selectionPlanDeduplicatesMissingIdsAndUsesStableTitleIdOrdering() {
        val rows = listOf(
            row(9L, "zeta"),
            row(2L, "Alpha"),
            row(4L, "alpha"),
            row(7L, "hidden"),
        )

        val plan = LibraryBulkSelectionPlanner.plan(
            generation = 12L,
            requestedAppIds = listOf(9L, 4L, 2L, 4L, 99L),
            availableApps = rows,
        )

        assertEquals(listOf(2L, 4L, 9L, 99L), plan.requestedAppIds)
        assertEquals(listOf(2L, 4L, 9L), plan.apps.map(LibraryAppRow::id))
        assertEquals(setOf(99L), plan.missingAppIds)
        assertFalse(plan.isComplete)
        assertEquals(listOf("Alpha", "alpha", "zeta"), plan.apps.map(LibraryAppRow::storageKey))
    }

    @Test
    fun operationResultExposesRetryableFailuresAndMissingTargets() {
        val result = LibraryBulkOperationResult(
            generation = 3L,
            items = listOf(
                LibraryBulkItemResult(1L, "one", "One", LibraryBulkItemStatus.Succeeded),
                LibraryBulkItemResult(2L, "two", "Two", LibraryBulkItemStatus.Failed, "disk"),
                LibraryBulkItemResult(3L, "three", "Three", LibraryBulkItemStatus.Skipped),
            ),
            missingAppIds = setOf(4L),
        )

        assertEquals(1, result.succeeded.size)
        assertEquals(1, result.failed.size)
        assertEquals(1, result.skipped.size)
        assertTrue(result.hasRetryableItems)
    }

    private fun row(id: Long, title: String) = LibraryAppRow(
        id = id,
        storageKey = title,
        sourceTitle = title,
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
        title = title,
        vendor = "Vendor",
        version = "1.0",
        description = "",
        favorite = false,
        addedAt = null,
        lastPlayedAt = null,
        iconRevision = 0L,
    )
}
