/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.text.Collator
import java.util.Locale

/** Pure filter/sort rules used by PR1 while the Room projection remains deliberately lightweight. */
object LibraryListProjection {
    fun project(
        rows: List<LibraryAppRow>,
        filter: String,
        sortVariant: Int,
        locale: Locale = Locale.getDefault(),
    ): List<LibraryAppRow> {
        val query = filter.trim()
        val filtered = if (query.isEmpty()) {
            rows
        } else {
            rows.filter { row ->
                row.title.contains(query, ignoreCase = true) ||
                    row.vendor.contains(query, ignoreCase = true)
            }
        }
        if (filtered.size < 2) {
            return filtered
        }

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
        val comparator = Comparator<LibraryAppRow> { left, right ->
            val primary = primaryComparator.compare(left, right)
            if (primary != 0) primary else left.id.compareTo(right.id)
        }

        return filtered.sortedWith(comparator)
    }

    const val SORT_TITLE = 0
    const val SORT_DATE = 1
    const val SORT_VENDOR = 2
}
