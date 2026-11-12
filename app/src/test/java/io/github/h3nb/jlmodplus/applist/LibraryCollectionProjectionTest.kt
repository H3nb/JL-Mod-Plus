/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryCollectionProjectionTest {
    @Test
    fun completedProjectionBelongsOnlyToItsExactSourceQueryAndSort() {
        val original = listOf(LibraryAppUiItem(1, "First", "Vendor", "1.0", null, true))
        val projection = LibraryCollectionProjection(original, "first", 0, original)

        assertTrue(projection.matches(original, "first", 0))
        assertFalse(projection.matches(original, "second", 0))
        assertFalse(projection.matches(original, "first", 1))
        // A new source must be reprojected even if its items compare equal.
        assertFalse(projection.matches(original.toList().toMutableList(), "first", 0))
    }

    @Test fun transientUiIdsDoNotChangeCollectionOrder() {
        val apps = listOf(
            app(uiId = 200, databaseId = 10),
            app(uiId = 1, databaseId = 30),
            app(uiId = 8, databaseId = 20),
        )
        val sorted = projectCollectionApps(apps, "", 0, Locale.US)
        assertEquals(listOf(10L, 20L, 30L), sorted.map { it.databaseId })

        val newest = projectCollectionApps(apps, "", 1 or Int.MIN_VALUE, Locale.US)
        assertEquals(listOf(10L, 20L, 30L), newest.map { it.databaseId })
    }

    @Test fun dateSortUsesInstallTimestampWithUnknownDatesLast() {
        val apps = listOf(
            app(uiId = 1, databaseId = 50, addedAt = 200L),
            app(uiId = 2, databaseId = 10, addedAt = null),
            app(uiId = 3, databaseId = 30, addedAt = 100L),
            app(uiId = 4, databaseId = 20, addedAt = 200L),
        )
        assertEquals(listOf(30L, 20L, 50L, 10L),
            projectCollectionApps(apps, "", 1, Locale.US).map { it.databaseId })
        assertEquals(listOf(20L, 50L, 30L, 10L),
            projectCollectionApps(apps, "", 1 or Int.MIN_VALUE, Locale.US).map { it.databaseId })
    }

    @Test fun cancellationStopsLargeCollectionSearch() {
        val apps = (1..10_000).map { app(uiId = it, databaseId = it.toLong()) }
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            projectCollectionApps(apps, "", 0, Locale.US) {
                if (++checks == 5) throw IllegalStateException("cancelled")
            }
        }
    }

    @Test fun searchRanksBeforeStableTieBreak() {
        val apps = listOf(
            app(uiId = 1, databaseId = 3, title = "Endgame"),
            app(uiId = 5, databaseId = 1, title = "Game"),
            app(uiId = 3, databaseId = 2, title = "Game Plus"),
        )
        val found = projectCollectionApps(apps, "game", 0, Locale.US)
        assertEquals(listOf(1L, 2L, 3L), found.map { it.databaseId })
    }

    private fun app(uiId: Int, databaseId: Long, title: String = "Same", addedAt: Long? = null) =
        LibraryAppUiItem(
            id = uiId,
            databaseId = databaseId,
            title = title,
            author = "Vendor",
            version = "1.0",
            iconPath = null,
            canReinstall = true,
            addedAt = addedAt,
        )
}
