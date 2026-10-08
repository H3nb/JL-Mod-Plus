/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import java.util.Locale
import java.text.Collator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun smartSearchRanksTitleBeforeVendorVersionAndDescription() {
        val searchable = listOf(
            row(10, "Quest", "Quest Studios", version = "Quest build", description = "quest mode"),
            row(11, "Quest Deluxe", "Quest Studios", version = "Quest build", description = "quest mode"),
            row(12, "My Quest", "Quest Studios", version = "Quest build", description = "quest mode"),
            row(13, "Ordinary", "Quest Studios", version = "Quest build", description = "quest mode"),
            row(14, "Ordinary", "Vendor", version = "Quest build", description = "quest mode"),
            row(15, "Ordinary", "Vendor", version = "1.0", description = "Includes quest mode"),
        )

        val result = LibraryListProjection.project(
            rows = searchable,
            filter = "quest",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
        )

        assertEquals(listOf(10L, 11L, 12L, 13L, 14L, 15L), result.map { it.id })
    }

    @Test fun searchTrimsQueryAndUsesRootCaseMappingForUnicodeTitleAndDescription() {
        val searchable = listOf(
            row(10, "İSTANBUL", "Vendor"),
            row(11, "Ordinary", "Vendor", description = "Visit İSTANBUL today"),
            row(12, "Istanbul", "Vendor"),
        )

        val result = LibraryListProjection.project(
            rows = searchable,
            filter = "  İSTANBUL  ",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.forLanguageTag("tr-TR"),
        )

        // ROOT preserves the dotted-I expansion; the selected sort locale must not alter search.
        assertEquals(listOf(10L, 11L), result.map { it.id })
    }

    @Test fun searchRankUsesSelectedSortOnlyAsTieBreakerWithinSameRank() {
        val searchable = listOf(
            row(10, "Zulu Quest", "A Vendor"),
            row(11, "Alpha Quest", "Z Vendor"),
            row(12, "Quest", "Z Vendor"),
        )

        val result = LibraryListProjection.project(
            rows = searchable,
            filter = "quest",
            sortVariant = LibraryListProjection.SORT_VENDOR,
            locale = Locale.US,
        )

        assertEquals(listOf(12L, 10L, 11L), result.map { it.id })
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

    @Test fun recentlyAddedSearchUsesRelevanceThenRecentTime() {
        val recentRows = listOf(
            row(10, "My Quest", "Vendor", addedAt = 500L),
            row(11, "Quest", "Vendor", addedAt = 100L),
            row(12, "Quest Deluxe", "Vendor", addedAt = 300L),
        )
        val result = LibraryListProjection.project(
            rows = recentRows,
            filter = "quest",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
            quickView = LibraryQuickView.RecentlyAdded,
        )
        assertEquals(listOf(11L, 12L, 10L), result.map { it.id })
    }

    @Test fun recentlyPlayedUsesKnownLastPlayedTimeNewestFirstAndExcludesNeverPlayedRows() {
        val playedRows = listOf(
            row(10, "Old", "Vendor", lastPlayedAt = 100L),
            row(11, "Never", "Vendor", lastPlayedAt = null),
            row(12, "Newest", "Vendor", lastPlayedAt = 300L),
            row(13, "Middle", "Vendor", lastPlayedAt = 200L),
            row(14, "Same timestamp newer id", "Vendor", lastPlayedAt = 300L),
        )
        val result = LibraryListProjection.project(
            rows = playedRows,
            filter = "",
            sortVariant = LibraryListProjection.SORT_VENDOR,
            locale = Locale.US,
            quickView = LibraryQuickView.RecentlyPlayed,
        )
        assertEquals(listOf(14L, 12L, 13L, 10L), result.map { it.id })
    }

    @Test fun recentlyPlayedSearchUsesRelevanceThenLastPlayedTime() {
        val playedRows = listOf(
            row(10, "My Quest", "Vendor", lastPlayedAt = 500L),
            row(11, "Quest", "Vendor", lastPlayedAt = 100L),
            row(12, "Quest Deluxe", "Vendor", lastPlayedAt = 300L),
        )
        val result = LibraryListProjection.project(
            rows = playedRows,
            filter = "quest",
            sortVariant = LibraryListProjection.SORT_TITLE,
            locale = Locale.US,
            quickView = LibraryQuickView.RecentlyPlayed,
        )
        assertEquals(listOf(11L, 12L, 10L), result.map { it.id })
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

    @Test fun dateAddedSortUsesKnownDatesAndKeepsLegacyRowsLast() {
        val datedRows = listOf(
            row(10, "Newest", "Vendor", addedAt = 300L),
            row(11, "Legacy A", "Vendor", addedAt = null),
            row(12, "Oldest", "Vendor", addedAt = 100L),
            row(13, "Middle A", "Vendor", addedAt = 200L),
            row(14, "Legacy B", "Vendor", addedAt = null),
            row(15, "Middle B", "Vendor", addedAt = 200L),
        )

        val ascending = LibraryListProjection.project(
            datedRows, "", LibraryListProjection.SORT_DATE, Locale.US)
        val descending = LibraryListProjection.project(
            datedRows, "", LibraryListProjection.SORT_DATE or Int.MIN_VALUE, Locale.US)

        assertEquals(listOf(12L, 13L, 15L, 10L, 11L, 14L), ascending.map { it.id })
        assertEquals(listOf(10L, 13L, 15L, 12L, 11L, 14L), descending.map { it.id })
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

    @Test fun stableBucketsMatchRankThenSortWithLocaleTiesAndQuickViews() {
        val input = listOf(
            row(10, "Quest", "Same", true, 100, 300),
            row(11, "QUEST", "same", false, 100, 300),
            row(12, "Quest Deluxe", "Å Vendor", true, 300, null),
            row(13, "My Quest", "Zulu", false, null, 100),
            row(14, "Ångström", "Quest", true, 200, 200),
            row(15, "Zebra", "Quest", false, null, null),
            row(16, "Alpha", "Ordinary", true, 200, 300, "quest build"),
            row(17, "alpha", "ordinary", false, 200, 300, description = "quest mode"),
            row(18, "İSTANBUL", "Éclair", true, null, 100),
            row(19, "Istanbul", "éclair", false, 300, null),
            row(20, "100% Fun", "Under_score", true, 300, 200),
        )
        for (locale in listOf(Locale.US, Locale.forLanguageTag("sv-SE"), Locale.forLanguageTag("tr-TR"))) {
            for (sort in listOf(0, 1, 2, Int.MIN_VALUE, Int.MIN_VALUE or 1, Int.MIN_VALUE or 2)) {
                for (view in LibraryQuickView.entries) {
                    val ordered = LibraryListProjection.prepare(input, sort, locale, view)
                    for (query in listOf("", " quest ", "a", "İSTANBUL", "%", "_", "absent")) {
                        assertEquals("$locale/$sort/$view/$query",
                            legacyProject(input, query, sort, locale, view).map { it.id },
                            LibraryListProjection.project(ordered, query, view).map { it.id })
                    }
                }
            }
        }
    }

    @Test fun preparationOwnsItsRowsAndUsesFullSourceForInvalidationAndAvailableIds() {
        val input = mutableListOf(row(10, "Zulu", "Vendor", favorite = true),
            row(11, "Alpha", "Vendor"))
        val ordered = LibraryListProjection.prepare(input, 0, Locale.US, LibraryQuickView.Favorites)
        input.clear()
        assertEquals(listOf(11L, 10L), ordered.rows.map { it.id })
        assertEquals(setOf(10L, 11L), ordered.availableAppIds)
        assertEquals(listOf(10L), LibraryListProjection.project(ordered, "", LibraryQuickView.Favorites).map { it.id })
        assertTrue(ordered.matchesOrdering(0, Locale.US, LibraryQuickView.All))
        assertFalse(ordered.matchesOrdering(0, Locale.forLanguageTag("sv-SE"), LibraryQuickView.All))
        assertFalse(ordered.matchesSource(input))
    }

    // Characterization of the previous filter/rank/sort algorithm, independent of bucketing.
    private fun legacyProject(
        rows: List<LibraryAppRow>, query: String, sort: Int, locale: Locale, view: LibraryQuickView,
    ): List<LibraryAppRow> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val ranked = rows.filter { row -> when (view) {
            LibraryQuickView.All -> true
            LibraryQuickView.Favorites -> row.favorite
            LibraryQuickView.RecentlyAdded -> row.addedAt != null
            LibraryQuickView.RecentlyPlayed -> row.lastPlayedAt != null
        } }.mapNotNull { row ->
            val title = row.title.lowercase(Locale.ROOT)
            val rank = when {
                needle.isEmpty() || title == needle -> 0
                title.startsWith(needle) -> 1
                title.contains(needle) -> 2
                row.vendor.lowercase(Locale.ROOT).contains(needle) -> 3
                row.version.lowercase(Locale.ROOT).contains(needle) -> 4
                row.description.lowercase(Locale.ROOT).contains(needle) -> 5
                else -> return@mapNotNull null
            }
            row to rank
        }
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return ranked.sortedWith { left, right ->
            val rank = left.second.compareTo(right.second)
            if (rank != 0) rank else {
                val a = left.first
                val b = right.first
                if (view == LibraryQuickView.RecentlyAdded || view == LibraryQuickView.RecentlyPlayed) {
                    val first = if (view == LibraryQuickView.RecentlyAdded) a.addedAt else a.lastPlayedAt
                    val second = if (view == LibraryQuickView.RecentlyAdded) b.addedAt else b.lastPlayedAt
                    val time = requireNotNull(second).compareTo(requireNotNull(first))
                    if (time != 0) time else b.id.compareTo(a.id)
                } else {
                    val primary = when (sort and Int.MAX_VALUE) {
                        1 -> when {
                            a.addedAt == null && b.addedAt == null -> 0
                            a.addedAt == null -> 1
                            b.addedAt == null -> -1
                            else -> a.addedAt.compareTo(b.addedAt).let { if (sort < 0) -it else it }
                        }
                        2 -> collator.compare(a.vendor, b.vendor).let { if (sort < 0) -it else it }
                        else -> collator.compare(a.title, b.title).let { if (sort < 0) -it else it }
                    }
                    val secondary = if (primary != 0) primary else when (sort and Int.MAX_VALUE) {
                        1 -> 0
                        2 -> collator.compare(a.title, b.title)
                        else -> collator.compare(a.vendor, b.vendor)
                    }
                    if (secondary != 0) secondary else a.id.compareTo(b.id)
                }
            }
        }.map { it.first }
    }

    private fun project(filter: String, sort: Int) =
        LibraryListProjection.project(rows, filter, sort, Locale.US)

    private fun row(
        id: Long,
        title: String,
        vendor: String,
        favorite: Boolean = false,
        addedAt: Long? = null,
        lastPlayedAt: Long? = null,
        version: String = "1.0",
        description: String = "",
    ) = LibraryAppRow(
        id = id,
        storageKey = "app-$id",
        sourceTitle = title,
        sourceVendor = vendor,
        sourceVersion = version,
        title = title,
        vendor = vendor,
        version = version,
        description = description,
        favorite = favorite,
        addedAt = addedAt,
        lastPlayedAt = lastPlayedAt,
        iconRevision = 0,
    )
}
