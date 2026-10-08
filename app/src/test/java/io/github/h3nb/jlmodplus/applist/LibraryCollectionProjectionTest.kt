/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionProjectionTest {
    @Test
    fun completedProjectionBelongsOnlyToItsExactSourceQueryAndSort() {
        val original = listOf(LibraryAppUiItem(1, "First", "Vendor", "1.0", null, true))
        val projection = LibraryCollectionProjection(original, "first", 0, original)

        assertTrue(projection.matches(original, "first", 0))
        assertFalse(projection.matches(original, "second", 0))
        assertFalse(projection.matches(original, "first", 1))
        // A new source must be reprojected even if its items compare equal.
        assertFalse(projection.matches(original.toList().toMutableList(), "first", 0))
    }
}
