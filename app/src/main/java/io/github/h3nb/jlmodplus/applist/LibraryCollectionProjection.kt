/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import java.text.Collator
import java.util.Locale

internal fun projectCollectionApps(
    rows: List<LibraryAppUiItem>,
    filter: String,
    sortVariant: Int,
    locale: Locale = Locale.getDefault(),
): List<LibraryAppUiItem> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    val ranked = rows.mapNotNull { row ->
        val rank = if (needle.isEmpty()) 0 else collectionSearchRank(row, needle) ?: return@mapNotNull null
        rank to row
    }
    if (ranked.size < 2) return ranked.map { it.second }

    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val sortIndex = sortVariant and Int.MAX_VALUE
    val descending = sortVariant < 0
    val fallback = Comparator<LibraryAppUiItem> { left, right ->
        val primary = when (sortIndex) {
            1 -> left.databaseId.compareTo(right.databaseId)
            2 -> collator.compare(left.author, right.author)
            else -> collator.compare(left.title, right.title)
        }
        val ordered = if (descending) -primary else primary
        if (ordered != 0) ordered else left.databaseId.compareTo(right.databaseId)
    }
    return ranked.sortedWith { left, right ->
        val rankOrder = left.first.compareTo(right.first)
        if (rankOrder != 0) rankOrder else fallback.compare(left.second, right.second)
    }.map { it.second }
}

private fun collectionSearchRank(row: LibraryAppUiItem, needle: String): Int? {
    val title = row.title.lowercase(Locale.ROOT)
    return when {
        title == needle -> 0
        title.startsWith(needle) -> 1
        title.contains(needle) -> 2
        row.author.lowercase(Locale.ROOT).contains(needle) -> 3
        row.version.lowercase(Locale.ROOT).contains(needle) -> 4
        row.description.lowercase(Locale.ROOT).contains(needle) -> 5
        else -> null
    }
}
