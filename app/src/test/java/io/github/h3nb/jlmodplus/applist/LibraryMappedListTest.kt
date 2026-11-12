/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryMappedListTest {
    @Test fun largeCatalogMapsOnlyIndexedItems() {
        var mapped = 0
        val rows = object : AbstractList<Int>() {
            override val size: Int = 50_000
            override fun get(index: Int): Int = index
        }
        val items = LibraryMappedList(rows) { index: Int ->
            mapped++
            "Game $index"
        }
        assertEquals(50_000, items.size)
        assertEquals(0, mapped)
        assertEquals("Game 0", items[0])
        assertEquals("Game 49999", items[49_999])
        assertEquals(2, mapped)
    }

    @Test fun indexOutOfBoundsPropagatesWithoutMapping() {
        var mapped = 0
        val items = LibraryMappedList(listOf(1)) { value: Int ->
            mapped++
            value
        }
        assertThrows(IndexOutOfBoundsException::class.java) { items[1] }
        assertEquals(0, mapped)
    }
}
