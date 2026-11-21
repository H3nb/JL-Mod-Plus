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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

// Keep a compact default without clipping the input or sort control at large font scales.
@Composable
private fun librarySearchControlHeight() =
    (52f + 16f * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)).dp

/** Shared search field used by the Apps and Collections surfaces. */
@OptIn(ExperimentalLayoutApi::class)
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
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var fieldFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocus, enabled) {
        if (autoFocus && enabled) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) {
            if (fieldFocused) onDismissInput()
            imeWasVisible = false
        }
    }
    BackHandler(enabled = enabled && onClose != null) {
        if (imeVisible || fieldFocused) {
            keyboard?.hide()
            onDismissInput()
        } else {
            onClose?.invoke()
        }
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .focusRequester(focus)
            // Exclude an inactive page's editor without overriding the active TextField's
            // own Material focus properties (which must remain responsible for touch refocus).
            .then(if (enabled) Modifier else Modifier.focusProperties { canFocus = false })
            .height(librarySearchControlHeight())
            .onFocusChanged {
                fieldFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                // Back can cancel the IME's first show before any visible inset is reported.
                // Handle that key while the field still owns focus; gesture Back continues
                // through the destination's IME-hide observer and BackHandler.
                if (!enabled || event.key != Key.Back) {
                    false
                } else {
                    when (event.type) {
                        KeyEventType.KeyDown -> true
                        KeyEventType.KeyUp -> {
                            if (!event.nativeKeyEvent.isCanceled) {
                                keyboard?.hide()
                                onDismissInput()
                            }
                            true
                        }
                        else -> false
                    }
                }
            },
        textStyle = MaterialTheme.typography.bodyMedium,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onClose == null) ImeAction.Done else ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onDismissInput()
            },
            onSearch = {
                keyboard?.hide()
                onDismissInput()
            },
        ),
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        placeholder = {
            Text(
                text = placeholder ?: stringResource(R.string.library_search_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            if (onClose == null) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                )
            } else {
                IconButton(onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onClose()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = {
                        onQueryChange("")
                        if (onClose != null) {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    },
                    enabled = enabled,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.library_search_clear),
                    )
                }
            }
        } else {
            null
        },
    )
}

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
        modifier = Modifier.size(librarySearchControlHeight()),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        modifier = modifier.size(librarySearchControlHeight()),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
