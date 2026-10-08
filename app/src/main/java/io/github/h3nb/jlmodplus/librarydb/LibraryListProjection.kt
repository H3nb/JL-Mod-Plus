/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import java.text.Collator
import java.util.Collections
import java.util.Locale

enum class LibraryQuickView {
    All,
    Favorites,
    RecentlyAdded,
    RecentlyPlayed,
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
        return project(prepare(rows, sortVariant, locale, quickView), filter, quickView)
    }

    /** One source ordering; query and Favorites changes do not change its comparator. */
    internal class OrderedRows internal constructor(
        private val source: List<LibraryAppRow>,
        private val sortVariant: Int,
        private val locale: Locale,
        private val orderView: LibraryQuickView,
        val rows: List<LibraryAppRow>,
        val availableAppIds: Set<Long>,
    ) {
        fun matchesSource(source: List<LibraryAppRow>): Boolean = this.source == source

        fun matchesOrdering(
            sortVariant: Int,
            locale: Locale,
            quickView: LibraryQuickView,
        ): Boolean = this.locale == locale && orderView == orderingView(quickView) &&
            (orderView != LibraryQuickView.All || this.sortVariant == sortVariant)
    }

    internal fun prepare(
        rows: List<LibraryAppRow>,
        sortVariant: Int,
        locale: Locale,
        quickView: LibraryQuickView,
        checkActive: () -> Unit = {},
    ): OrderedRows {
        checkActive()
        val source = Collections.unmodifiableList(ArrayList(rows))
        val availableIds = LinkedHashSet<Long>(source.size)
        val ordered = ArrayList<LibraryAppRow>(source.size)
        val orderView = orderingView(quickView)
        for (index in source.indices) {
            if (index % CANCELLATION_CHUNK == 0) checkActive()
            val row = source[index]
            availableIds.add(row.id)
            if (eligible(row, orderView)) ordered.add(row)
        }
        val comparator = when (orderView) {
            LibraryQuickView.RecentlyAdded -> Comparator<LibraryAppRow> { left, right ->
                val primary = requireNotNull(right.addedAt).compareTo(requireNotNull(left.addedAt))
                if (primary != 0) primary else right.id.compareTo(left.id)
            }
            LibraryQuickView.RecentlyPlayed -> Comparator<LibraryAppRow> { left, right ->
                val primary = requireNotNull(right.lastPlayedAt).compareTo(requireNotNull(left.lastPlayedAt))
                if (primary != 0) primary else right.id.compareTo(left.id)
            }
            else -> sortComparator(sortVariant, locale)
        }
        checkActive()
        ordered.sortWith(comparator)
        checkActive()
        return OrderedRows(source, sortVariant, locale, orderView,
            Collections.unmodifiableList(ordered), Collections.unmodifiableSet(availableIds))
    }

    internal fun project(
        ordered: OrderedRows,
        filter: String,
        quickView: LibraryQuickView,
        checkActive: () -> Unit = {},
    ): List<LibraryAppRow> {
        checkActive()
        val query = filter.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) {
            if (quickView != LibraryQuickView.Favorites) return ordered.rows
            val favorites = ArrayList<LibraryAppRow>()
            for (index in ordered.rows.indices) {
                if (index % CANCELLATION_CHUNK == 0) checkActive()
                val row = ordered.rows[index]
                if (row.favorite) favorites.add(row)
            }
            checkActive()
            return Collections.unmodifiableList(favorites)
        }
        val buckets = arrayOfNulls<ArrayList<LibraryAppRow>>(SEARCH_RANKS)
        var count = 0
        for (index in ordered.rows.indices) {
            if (index % CANCELLATION_CHUNK == 0) checkActive()
            val row = ordered.rows[index]
            if (!eligible(row, quickView)) continue
            val rank = searchRank(row, query) ?: continue
            val bucket = buckets[rank] ?: ArrayList<LibraryAppRow>().also { buckets[rank] = it }
            bucket.add(row)
            count++
        }
        val result = ArrayList<LibraryAppRow>(count)
        for (bucket in buckets) {
            checkActive()
            if (bucket != null) result.addAll(bucket)
        }
        return Collections.unmodifiableList(result)
    }

    private fun orderingView(quickView: LibraryQuickView) =
        if (quickView == LibraryQuickView.Favorites) LibraryQuickView.All else quickView

    private fun eligible(row: LibraryAppRow, quickView: LibraryQuickView) = when (quickView) {
        LibraryQuickView.All -> true
        LibraryQuickView.Favorites -> row.favorite
        LibraryQuickView.RecentlyAdded -> row.addedAt != null
        LibraryQuickView.RecentlyPlayed -> row.lastPlayedAt != null
    }

    private fun searchRank(row: LibraryAppRow, needle: String): Int? {
        val title = row.title.lowercase(Locale.ROOT)
        return when {
            title == needle -> 0
            title.startsWith(needle) -> 1
            title.contains(needle) -> 2
            row.vendor.lowercase(Locale.ROOT).contains(needle) -> 3
            row.version.lowercase(Locale.ROOT).contains(needle) -> 4
            row.description.lowercase(Locale.ROOT).contains(needle) -> 5
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
                val leftAddedAt = left.addedAt
                val rightAddedAt = right.addedAt
                when {
                    leftAddedAt == null && rightAddedAt == null -> left.id.compareTo(right.id)
                    leftAddedAt == null -> 1
                    rightAddedAt == null -> -1
                    else -> {
                        val primary = leftAddedAt.compareTo(rightAddedAt)
                        if (descending) -primary else primary
                    }
                }
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

    private const val SEARCH_RANKS = 6
    private const val CANCELLATION_CHUNK = 128
    const val SORT_TITLE = 0
    const val SORT_DATE = 1
    const val SORT_VENDOR = 2
}
