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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.InlineSearchField

/** Library search delegates to the same inline control used by the file picker. */
@Composable
internal fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onDismissInput: () -> Unit,
    placeholder: String? = null,
    onClose: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) = InlineSearchField(
    query = query,
    onQueryChange = onQueryChange,
    modifier = modifier,
    enabled = enabled,
    onFocusChanged = onFocusChanged,
    onDismissInput = onDismissInput,
    placeholder = placeholder,
    onClose = onClose,
    autoFocus = autoFocus,
)

/** A compact view-mode action, paired visually with Sort in the Library toolbar. */
@Composable
internal fun LibraryLayoutButton(
    layout: LibraryLayout,
    onLayoutChange: (LibraryLayout) -> Unit,
    enabled: Boolean = true,
) {
    val nextLayout = if (layout == LibraryLayout.List) LibraryLayout.Grid else LibraryLayout.List
    val label = stringResource(
        if (nextLayout == LibraryLayout.Grid) R.string.library_view_grid
        else R.string.library_view_list,
    )
    Surface(
        onClick = { onLayoutChange(nextLayout) },
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(
                    if (nextLayout == LibraryLayout.Grid) R.drawable.ic_apps
                    else R.drawable.ic_view_list,
                ),
                contentDescription = label,
            )
        }
    }
}

@Composable
internal fun LibrarySortButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = stringResource(R.string.library_sort),
            )
        }
    }
}
