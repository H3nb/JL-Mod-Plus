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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryNavigationStateTest {
    @Test
    fun stableAnchorWinsOverOldIndexAfterRefresh() {
        val state = LibraryNavigationState().saveAnchor(
            LibraryNavigationSurface.AppsList,
            LibraryScrollAnchor(3L, stableItemId = 20L, offsetPx = 18, fallbackIndex = 4),
        )

        val resolved = state.resolveAnchor(
            LibraryNavigationSurface.AppsList,
            activeGeneration = 3L,
            availableIds = listOf(10L, 20L, 30L),
        )

        assertEquals(ResolvedLibraryScrollAnchor(index = 1, offsetPx = 18), resolved)
    }

    @Test
    fun deletedAnchorUsesNearestSafeFallbackAndStaleGenerationIsIgnored() {
        val state = LibraryNavigationState().saveAnchor(
            LibraryNavigationSurface.AppsGrid,
            LibraryScrollAnchor(3L, stableItemId = 20L, offsetPx = 5, fallbackIndex = 9),
        )

        assertEquals(
            ResolvedLibraryScrollAnchor(index = 2, offsetPx = 5),
            state.resolveAnchor(
                LibraryNavigationSurface.AppsGrid,
                activeGeneration = 3L,
                availableIds = listOf(10L, 11L, 12L),
            ),
        )
        assertNull(
            state.resolveAnchor(
                LibraryNavigationSurface.AppsGrid,
                activeGeneration = 4L,
                availableIds = listOf(10L, 11L, 12L),
            ),
        )
    }

    @Test
    fun listAndGridAnchorsRemainIndependent() {
        val state = LibraryNavigationState()
            .saveAnchor(
                LibraryNavigationSurface.AppsList,
                LibraryScrollAnchor(1L, 10L, offsetPx = 1, fallbackIndex = 0),
            )
            .saveAnchor(
                LibraryNavigationSurface.AppsGrid,
                LibraryScrollAnchor(1L, 30L, offsetPx = 2, fallbackIndex = 2),
            )

        assertEquals(10L, state.anchors[LibraryNavigationSurface.AppsList]?.stableItemId)
        assertEquals(30L, state.anchors[LibraryNavigationSurface.AppsGrid]?.stableItemId)
    }
}
