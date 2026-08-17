/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.applist

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFastScrollerTest {
    private val locale = Locale.US

    @Test
    fun nameSortBuildsAnchorsInRepositoryOrder() {
        val apps = listOf(
            app(1, "#Tool", "Vendor Z"),
            app(2, "Alpha", "Vendor B"),
            app(3, "Another", "Vendor A"),
            app(4, "Beta", "Vendor C"),
        )

        assertEquals(
            listOf(
                LibraryFastScrollBucket("#", 0),
                LibraryFastScrollBucket("A", 1),
                LibraryFastScrollBucket("B", 3),
            ),
            buildLibraryFastScrollBuckets(apps, sortVariant = 0, locale = locale),
        )
    }

    @Test
    fun descendingNameSortKeepsActualListOrderInsteadOfResortingInUi() {
        val apps = listOf(
            app(1, "Zulu", "Vendor Z"),
            app(2, "Beta", "Vendor B"),
            app(3, "Alpha", "Vendor A"),
            app(4, "1 Tool", "Vendor C"),
        )

        assertEquals(
            listOf("Z", "B", "A", "#"),
            buildLibraryFastScrollBuckets(
                apps,
                sortVariant = Int.MIN_VALUE,
                locale = locale,
            ).map(LibraryFastScrollBucket::label),
        )
    }

    @Test
    fun vendorSortIndexesVendorInsteadOfAppTitle() {
        val apps = listOf(
            app(1, "Zulu App", "Acme"),
            app(2, "Alpha App", "Beta Works"),
            app(3, "Beta App", "Beta Works"),
            app(4, "Gamma App", "Delta"),
        )

        assertEquals(
            listOf(
                LibraryFastScrollBucket("A", 0),
                LibraryFastScrollBucket("B", 1),
                LibraryFastScrollBucket("D", 3),
            ),
            buildLibraryFastScrollBuckets(apps, sortVariant = 2, locale = locale),
        )
    }

    @Test
    fun descendingVendorSortKeepsRepositoryOrder() {
        val apps = listOf(
            app(1, "Alpha App", "Zulu Works"),
            app(2, "Beta App", "Delta Studio"),
            app(3, "Gamma App", "Beta Labs"),
            app(4, "Delta App", "1 Vendor"),
        )

        assertEquals(
            listOf("Z", "D", "B", "#"),
            buildLibraryFastScrollBuckets(
                apps,
                sortVariant = Int.MIN_VALUE or 2,
                locale = locale,
            ).map(LibraryFastScrollBucket::label),
        )
    }

    @Test
    fun dateSortHasNoAlphabetIndexInEitherDirection() {
        val apps = listOf(app(1, "Alpha", "Vendor"))

        listOf(1, Int.MIN_VALUE or 1).forEach { sortVariant ->
            assertTrue(
                buildLibraryFastScrollBuckets(
                    apps,
                    sortVariant = sortVariant,
                    locale = locale,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun unsupportedOrNonLatinLeadingCharactersUseHashBucket() {
        assertEquals("#", libraryFastScrollLabel("  9 Lives", locale))
        assertEquals("#", libraryFastScrollLabel("Éclair", locale))
        assertEquals("#", libraryFastScrollLabel("", locale))
        assertEquals("A", libraryFastScrollLabel("alpha", locale))
    }

    @Test
    fun shortViewportSamplingKeepsFirstAndLastAnchors() {
        val buckets = ('A'..'Z').mapIndexed { index, char ->
            LibraryFastScrollBucket(char.toString(), index)
        }

        val visible = visibleLibraryFastScrollBuckets(buckets, maxSlots = 5)

        assertEquals(5, visible.size)
        assertEquals("A", visible.first().label)
        assertEquals("Z", visible.last().label)
        assertEquals(visible.map { it.appIndex }.distinct(), visible.map { it.appIndex })
    }

    @Test
    fun pointerPositionMapsAcrossEveryRealBucket() {
        assertEquals(0, libraryFastScrollBucketIndexForPosition(0f, 270, 27))
        assertEquals(13, libraryFastScrollBucketIndexForPosition(135f, 270, 27))
        assertEquals(26, libraryFastScrollBucketIndexForPosition(269f, 270, 27))
        assertEquals(-1, libraryFastScrollBucketIndexForPosition(10f, 0, 27))
    }

    private fun app(id: Int, title: String, author: String) = LibraryAppUiItem(
        id = id,
        title = title,
        author = author,
        version = "1.0",
        iconPath = null,
        canReinstall = false,
    )
}
