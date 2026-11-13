/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import org.junit.jupiter.api.Test

class LibraryMappedListTest {
    @Test fun replacementPickerSnapshotDoesNotMaterializeOldCatalogForEquality() {
        var materialized = 0
        val ids = object : AbstractList<Int>() {
            override val size = 50_000
            override fun get(index: Int) = index
        }
        val store = LibraryCollectionsUiStore()
        fun source() = LibraryMappedList(ids) { index: Int ->
            materialized++
            LibraryAppUiItem(index, "Game $index", "Vendor", "1.0", null, true)
        }
        store.publishAllApps(source())
        val firstRevision = store.state.value.allAppsRevision
        store.publishAllApps(source())
        assertEquals(0, materialized)
        assertTrue(store.state.value.allAppsRevision > firstRevision)
    }


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
        val ids = LibraryMappedList(rows) { index: Int -> index.toLong() + 1 }
        assertEquals(49_999, ids.indexOf(50_000L))
        assertEquals(0, mapped)
        assertEquals("Game 0", items[0])
        assertEquals("Game 49999", items[49_999])
        assertEquals(2, mapped)
    }

    @Test fun updatingCollectionMembersDoesNotCompareMappedRows() {
        val store = LibraryCollectionsUiStore()
        store.publishCollections(listOf(
            LibraryCollectionRow(id = 1L, name = "Games", sortOrder = 0, createdAt = 0L, appCount = 20_000),
        ))
        val memberIds = (1L..20_000L).toSet()
        val source = object : AbstractList<Long>() {
            override val size = 20_000
            override fun get(index: Int) = index.toLong() + 1
        }
        var mapped = 0
        fun members() = LibraryMappedList(source) { id: Long ->
            mapped++
            LibraryAppUiItem(id = 0, databaseId = id, title = "Game $id",
                author = "Vendor", version = "1.0", iconPath = null, canReinstall = true)
        }
        store.showMembers(1L, members(), memberIds)
        val firstRevision = store.state.value.members!!.revision
        store.showMembers(1L, members(), memberIds)

        assertTrue(store.state.value.members!!.revision > firstRevision)
        assertEquals(0, mapped)
    }

    @Test fun indexedMappingOnlyMaterializesRequestedMembers() {
        var mapped = 0
        val rows = List(20_000) { it }
        val items = LibraryMappedList(rows) { index: Int, value: Int ->
            mapped++
            index to value
        }
        assertEquals(20_000, items.size)
        assertEquals(0, mapped)
        assertEquals(12 to 12, items[12])
        assertEquals(1, mapped)
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
