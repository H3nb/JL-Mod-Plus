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

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Lightweight state, retained by the screen rather than the pager's disposable content. */
internal class LibraryViewportState(
    val listState: LazyListState,
    val gridState: LazyGridState,
    val headerOffsetPx: MutableFloatState,
    private val navigationVisibility: MutableState<Boolean>,
    val queryState: MutableState<String>,
) {
    var chromeVisible by navigationVisibility
    var query by queryState

    // Retain the last completed projection while its page is offscreen. Native list state must
    // never be measured against a temporary unfiltered or empty startup projection.
    var collectionProjection by mutableStateOf<LibraryCollectionProjection?>(null)
}

internal class LibraryCollectionProjection(
    val source: List<LibraryAppUiItem>,
    val query: String,
    val sortVariant: Int,
    val apps: List<LibraryAppUiItem>,
) {
    fun matches(source: List<LibraryAppUiItem>, query: String, sortVariant: Int): Boolean =
        this.source === source && this.query == query && this.sortVariant == sortVariant
}

@Composable
internal fun rememberLibraryViewportState(
    libraryScope: String = "",
    collectionId: Long? = null,
    initialQuery: String = "",
): LibraryViewportState = key(libraryScope, collectionId) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val headerOffset = rememberSaveable { mutableFloatStateOf(0f) }
    val navigationVisible = rememberSaveable { mutableStateOf(true) }
    val query = rememberSaveable { mutableStateOf(initialQuery) }
    remember {
        LibraryViewportState(listState, gridState, headerOffset, navigationVisible, query)
    }
}
