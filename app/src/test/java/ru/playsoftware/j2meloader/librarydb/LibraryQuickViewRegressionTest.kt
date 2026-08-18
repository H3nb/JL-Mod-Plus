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

class LibraryQuickViewRegressionTest {
    @Test
    fun quickViewsRemainCompatibleWithLiteralSearch() {
        val rows = listOf(
            row(1, "100% Favorite", favorite = true, addedAt = 100L),
            row(2, "100% Ordinary", favorite = false, addedAt = 300L),
            row(3, "Under_score", favorite = true, addedAt = 200L),
            row(4, "Legacy 100%", favorite = true, addedAt = null),
        )

        val favorites = LibraryListProjection.project(
            rows = rows,
            filter = "%",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
            quickView = LibraryQuickView.Favorites,
        )
        assertEquals(listOf(1L, 4L), favorites.map { it.id })

        val recentlyAdded = LibraryListProjection.project(
            rows = rows,
            filter = "%",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
            quickView = LibraryQuickView.RecentlyAdded,
        )
        assertEquals(listOf(2L, 1L), recentlyAdded.map { it.id })
    }

    private fun row(
        id: Long,
        title: String,
        favorite: Boolean,
        addedAt: Long?,
    ) = LibraryAppRow(
        id = id,
        storageKey = "app-$id",
        sourceTitle = title,
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
        title = title,
        vendor = "Vendor",
        version = "1.0",
        description = "",
        favorite = favorite,
        addedAt = addedAt,
        lastPlayedAt = null,
        iconRevision = 0,
    )
}
