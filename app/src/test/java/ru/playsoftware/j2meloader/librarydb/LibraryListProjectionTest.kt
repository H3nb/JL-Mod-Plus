/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryListProjectionTest {
    private val rows = listOf(
        row(1, "Zulu", "Beta"),
        row(2, "alpha", "Zulu"),
        row(3, "Bravo", "Alpha"),
    )

    @Test fun filterMatchesEffectiveTitleOrVendorIgnoringCase() {
        assertEquals(listOf(2L, 3L), project("ALP", 0).map { it.id })
        assertEquals(listOf(2L, 3L), project("alpha", 0).map { it.id })
    }

    @Test fun wildcardCharactersAreTreatedAsLiteralSearchText() {
        val specialRows = listOf(
            row(10, "100% Fun", "Vendor"),
            row(11, "Under_score", "Vendor"),
            row(12, "Ordinary", "Vendor"),
        )
        val percent = LibraryListProjection.project(specialRows, "%", 0, Locale.US)
        val underscore = LibraryListProjection.project(specialRows, "_", 0, Locale.US)

        assertEquals(listOf(10L), percent.map { it.id })
        assertEquals(listOf(11L), underscore.map { it.id })
    }

    @Test fun favoritesViewFiltersBeforeSearchAndPreservesSelectedSort() {
        val favoriteRows = listOf(
            row(10, "Zulu favorite", "Vendor", favorite = true),
            row(11, "Alpha favorite", "Vendor", favorite = true),
            row(12, "Alpha ordinary", "Vendor", favorite = false),
        )
        val result = LibraryListProjection.project(
            rows = favoriteRows,
            filter = "favorite",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
            quickView = LibraryQuickView.Favorites,
        )
        assertEquals(listOf(11L, 10L), result.map { it.id })
    }

    @Test fun recentlyAddedUsesKnownAddedTimeNewestFirstAndExcludesUnknownLegacyRows() {
        val recentRows = listOf(
            row(10, "Old known", "Vendor", addedAt = 100L),
            row(11, "Legacy unknown", "Vendor", addedAt = null),
            row(12, "Newest", "Vendor", addedAt = 300L),
            row(13, "Middle", "Vendor", addedAt = 200L),
            row(14, "Same timestamp newer id", "Vendor", addedAt = 300L),
        )
        val result = LibraryListProjection.project(
            rows = recentRows,
            filter = "",
            sortVariant = LibraryListProjection.SORT_VENDOR,
            locale = Locale.US,
            quickView = LibraryQuickView.RecentlyAdded,
        )
        assertEquals(listOf(14L, 12L, 13L, 10L), result.map { it.id })
    }

    @Test fun titleSortPreservesLegacySecondaryVendorOrdering() {
        val duplicate = row(4, "alpha", "Alpha")
        val result = LibraryListProjection.project(
            rows + duplicate,
            filter = "",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
        )
        assertEquals(listOf(4L, 2L, 3L, 1L), result.map { it.id })
    }

    @Test fun descendingTitleReversesPrimaryButKeepsSecondaryAscending() {
        val result = project("", LibraryListProjection.SORT_TITLE or Int.MIN_VALUE)
        assertEquals(listOf(1L, 3L, 2L), result.map { it.id })
    }

    @Test fun dateSortUsesStableDatabaseIdentityLikeLegacyRowOrdering() {
        assertEquals(listOf(1L, 2L, 3L), project("", LibraryListProjection.SORT_DATE).map { it.id })
        assertEquals(
            listOf(3L, 2L, 1L),
            project("", LibraryListProjection.SORT_DATE or Int.MIN_VALUE).map { it.id },
        )
    }

    @Test fun vendorSortUsesTitleAsSecondaryKey() {
        val result = project("", LibraryListProjection.SORT_VENDOR)
        assertEquals(listOf(3L, 1L, 2L), result.map { it.id })
    }

    @Test fun fiveThousandRowsProjectWithoutChangingInput() {
        val input = (0 until 5_000).map { index ->
            row(index.toLong(), "Game ${5_000 - index}", "Vendor ${index % 20}")
        }
        val snapshot = input.toList()
        val result = LibraryListProjection.project(input, "game", 0, Locale.US)
        assertEquals(5_000, result.size)
        assertEquals(snapshot, input)
    }

    private fun project(filter: String, sort: Int) =
        LibraryListProjection.project(rows, filter, sort, Locale.US)

    private fun row(
        id: Long,
        title: String,
        vendor: String,
        favorite: Boolean = false,
        addedAt: Long? = null,
    ) = LibraryAppRow(
        id = id,
        storageKey = "app-$id",
        sourceTitle = title,
        sourceVendor = vendor,
        sourceVersion = "1.0",
        title = title,
        vendor = vendor,
        version = "1.0",
        description = "",
        favorite = favorite,
        addedAt = addedAt,
        lastPlayedAt = null,
        iconRevision = 0,
    )
}