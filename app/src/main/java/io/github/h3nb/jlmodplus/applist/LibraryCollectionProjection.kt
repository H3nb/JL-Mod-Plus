/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import java.text.Collator
import java.util.Locale

internal fun projectCollectionApps(
    rows: List<LibraryAppUiItem>,
    filter: String,
    sortVariant: Int,
    locale: Locale = Locale.getDefault(),
    checkActive: () -> Unit = {},
): List<LibraryAppUiItem> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    val ranked = ArrayList<Pair<Int, LibraryAppUiItem>>()
    for (index in rows.indices) {
        if (index % 128 == 0) checkActive()
        val row = rows[index]
        val rank = if (needle.isEmpty()) 0 else collectionSearchRank(row, needle) ?: continue
        ranked.add(rank to row)
    }
    checkActive()
    if (ranked.size < 2) return ranked.map { it.second }

    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val sortIndex = sortVariant and Int.MAX_VALUE
    val descending = sortVariant < 0
    var comparisons = 0
    val fallback = Comparator<LibraryAppUiItem> { left, right ->
        if (++comparisons % 256 == 0) checkActive()
        val primary = when (sortIndex) {
            1 -> when {
                left.addedAt == null && right.addedAt == null -> 0
                left.addedAt == null -> 1
                right.addedAt == null -> -1
                descending -> right.addedAt.compareTo(left.addedAt)
                else -> left.addedAt.compareTo(right.addedAt)
            }
            2 -> collator.compare(left.author, right.author)
                .let { if (descending) -it else it }
            else -> collator.compare(left.title, right.title)
                .let { if (descending) -it else it }
        }
        if (primary != 0) primary else {
            val secondary = when (sortIndex) {
                1 -> 0
                2 -> collator.compare(left.title, right.title)
                else -> collator.compare(left.author, right.author)
            }
            if (secondary != 0) secondary else left.databaseId.compareTo(right.databaseId)
        }
    }
    val sorted = ranked.sortedWith { left, right ->
        val rankOrder = left.first.compareTo(right.first)
        if (rankOrder != 0) rankOrder else fallback.compare(left.second, right.second)
    }
    checkActive()
    return sorted.map { it.second }
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
