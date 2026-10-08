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

package io.github.h3nb.jlmodplus.applist

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LibraryNavigationStateTest {
    @Test
    fun editorReturnFollowsStableIdAfterReordering() {
        val anchor = LibraryScrollAnchor(3L, stableItemId = 20L, offsetPx = 18, fallbackIndex = 4)

        assertEquals(
            ResolvedLibraryScrollAnchor(index = 1, offsetPx = 18),
            resolveLibraryScrollAnchor(anchor, listOf(10L, 20L, 30L)),
        )
    }

    @Test
    fun removedEditorReturnItemUsesBoundedFallback() {
        val anchor = LibraryScrollAnchor(3L, stableItemId = 20L, offsetPx = -24, fallbackIndex = 9)

        assertEquals(
            ResolvedLibraryScrollAnchor(index = 2, offsetPx = -24),
            resolveLibraryScrollAnchor(anchor, listOf(10L, 11L, 12L)),
        )
        assertEquals(
            ResolvedLibraryScrollAnchor(index = 0, offsetPx = -24),
            resolveLibraryScrollAnchor(anchor.copy(fallbackIndex = -1), listOf(10L, 11L, 12L)),
        )
        assertEquals(
            ResolvedLibraryScrollAnchor(index = 0, offsetPx = -24),
            resolveLibraryScrollAnchor(anchor, emptyList()),
        )
    }

    @Test
    fun editorReturnWithoutStableIdPreservesIndexAndSignedOffset() {
        val anchor = LibraryScrollAnchor(3L, stableItemId = null, offsetPx = -36, fallbackIndex = 1)

        assertEquals(
            ResolvedLibraryScrollAnchor(index = 1, offsetPx = -36),
            resolveLibraryScrollAnchor(anchor, listOf(10L, 20L, 30L)),
        )
    }

    @Test
    fun saverRoundTripPreservesOnlyRouteAndCollectionScope() {
        val state = LibraryNavigationState(
            destination = LibraryDestinationKey.Collections,
            selectedCollectionId = 42L,
            selectedCollectionScope = "/work/library-a",
            collectionManageApps = true,
        )
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(LibraryNavigationState.Saver) { scope.save(state) }

        assertNotNull(saved)
        assertEquals(state, LibraryNavigationState.Saver.restore(saved!!))
        assertEquals(
            listOf("Collections", 42L, "/work/library-a", true),
            saved,
        )
    }

    @Test
    fun saverIgnoresLegacyPresentationAndAnchorsWhileRetainingRoute() {
        val restored = LibraryNavigationState.Saver.restore(
            listOf(
                "Collections", "Grid", "demo", "RecentlyPlayed", -3, 42L,
                listOf(listOf("CollectionAppsList", 9L, 100L, 7, 2, 42L, "/work/library-a")),
                "/work/library-a", true,
            ),
        )

        assertEquals(
            LibraryNavigationState(
                destination = LibraryDestinationKey.Collections,
                selectedCollectionId = 42L,
                selectedCollectionScope = "/work/library-a",
                collectionManageApps = true,
            ),
            restored,
        )
    }

    @Test
    fun saverAcceptsOlderRouteWithoutCollectionScopeOrManageState() {
        val restored = LibraryNavigationState.Saver.restore(
            listOf(
                "Collections", "List", "", "All", 0, 42L,
                listOf(listOf("CollectionsList", 9L, 100L, 7, 2, Long.MIN_VALUE)),
            ),
        )

        assertEquals(
            LibraryNavigationState(
                destination = LibraryDestinationKey.Collections,
                selectedCollectionId = 42L,
            ),
            restored,
        )
    }

    @Test
    fun saverRestoresManageAppsOnlyWithSelectedCollection() {
        val restored = LibraryNavigationState.Saver.restore(
            listOf(
                "Collections", "List", "", "All", 0, Long.MIN_VALUE,
                emptyList<Any>(), "/work/library-a", true,
            ),
        )

        assertEquals(false, restored?.collectionManageApps)
    }

    @Test
    fun saverDiscardsLegacyAnchorOnlyState() {
        assertEquals(
            LibraryNavigationState(),
            LibraryNavigationState.Saver.restore(listOf(listOf("AppsList", 4L, 12L, 3, 1))),
        )
        assertEquals(LibraryNavigationState(), LibraryNavigationState.Saver.restore(emptyList<Any>()))
    }

    @Test
    fun saverMigratesLegacyOptionsDestinationToMore() {
        val restored = LibraryNavigationState.Saver.restore(
            listOf("Options", "List", "", "All", 0, null, emptyList<Any>()),
        )

        assertEquals(LibraryDestinationKey.More, restored?.destination)
    }
}
