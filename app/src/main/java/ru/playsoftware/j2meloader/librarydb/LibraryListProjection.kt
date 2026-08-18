/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.text.Collator
import java.util.Locale

enum class LibraryQuickView {
    All,
    Favorites,
    RecentlyAdded,
}

/** Pure filter/sort rules used while the Room projection remains deliberately lightweight. */
object LibraryListProjection {
    fun project(
        rows: List<LibraryAppRow>,
        filter: String,
        sortVariant: Int,
        locale: Locale = Locale.getDefault(),
        quickView: LibraryQuickView = LibraryQuickView.All,
    ): List<LibraryAppRow> {
        val quickRows = when (quickView) {
            LibraryQuickView.All -> rows
            LibraryQuickView.Favorites -> rows.filter(LibraryAppRow::favorite)
            LibraryQuickView.RecentlyAdded -> rows.filter { it.addedAt != null }
        }
        val query = filter.trim()
        val ranked = if (query.isEmpty()) {
            quickRows.map { RankedRow(it, NO_SEARCH_RANK) }
        } else {
            quickRows.mapNotNull { row ->
                searchRank(row, query)?.let { rank -> RankedRow(row, rank) }
            }
        }
        if (ranked.size < 2) {
            return ranked.map(RankedRow::row)
        }

        val fallbackComparator = if (quickView == LibraryQuickView.RecentlyAdded) {
            Comparator<LibraryAppRow> { left, right ->
                val primary = requireNotNull(right.addedAt).compareTo(requireNotNull(left.addedAt))
                if (primary != 0) primary else right.id.compareTo(left.id)
            }
        } else {
            sortComparator(sortVariant, locale)
        }
        val comparator = Comparator<RankedRow> { left, right ->
            val rankOrder = left.rank.compareTo(right.rank)
            if (rankOrder != 0) rankOrder else fallbackComparator.compare(left.row, right.row)
        }
        return ranked.sortedWith(comparator).map(RankedRow::row)
    }

    private fun searchRank(row: LibraryAppRow, query: String): Int? {
        val needle = query.lowercase(Locale.ROOT)
        val title = row.title.lowercase(Locale.ROOT)
        val vendor = row.vendor.lowercase(Locale.ROOT)
        val version = row.version.lowercase(Locale.ROOT)
        val description = row.description.lowercase(Locale.ROOT)
        return when {
            title == needle -> 0
            title.startsWith(needle) -> 1
            title.contains(needle) -> 2
            vendor.contains(needle) -> 3
            version.contains(needle) -> 4
            description.contains(needle) -> 5
            else -> null
        }
    }

    private fun sortComparator(sortVariant: Int, locale: Locale): Comparator<LibraryAppRow> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.SECONDARY
        }
        val sortIndex = sortVariant and Int.MAX_VALUE
        val descending = sortVariant < 0
        val primaryComparator = when (sortIndex) {
            SORT_DATE -> Comparator<LibraryAppRow> { left, right ->
                val primary = left.id.compareTo(right.id)
                if (descending) -primary else primary
            }
            SORT_VENDOR -> Comparator { left, right ->
                val primary = collator.compare(left.vendor, right.vendor)
                val orderedPrimary = if (descending) -primary else primary
                if (orderedPrimary != 0) orderedPrimary
                else collator.compare(left.title, right.title)
            }
            else -> Comparator { left, right ->
                val primary = collator.compare(left.title, right.title)
                val orderedPrimary = if (descending) -primary else primary
                if (orderedPrimary != 0) orderedPrimary
                else collator.compare(left.vendor, right.vendor)
            }
        }
        // Keep the stable database-id tie-breaker API-23-safe instead of using
        // java.util.Comparator.thenComparingLong(), which was added in API 24.
        return Comparator { left, right ->
            val primary = primaryComparator.compare(left, right)
            if (primary != 0) primary else left.id.compareTo(right.id)
        }
    }

    private data class RankedRow(val row: LibraryAppRow, val rank: Int)

    private const val NO_SEARCH_RANK = 0
    const val SORT_TITLE = 0
    const val SORT_DATE = 1
    const val SORT_VENDOR = 2
}