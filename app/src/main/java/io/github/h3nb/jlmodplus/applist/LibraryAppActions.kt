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

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.librarydb.LibraryPlayStatsFormatter
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.availableWindowHeightDp
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp
import io.github.h3nb.jlmodplus.ui.clearNavigationFocusOnTouch
import io.github.h3nb.jlmodplus.ui.isNavigationKeyEvent
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.showNavigationFocusForKey
import kotlinx.coroutines.flow.Flow

@Composable
internal fun LibrarySortMenu(
    expanded: Boolean,
    entries: List<String>,
    selectedSort: Int,
    ascending: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        entries.forEachIndexed { index, entry ->
            val selected = index == selectedSort
            DropdownMenuItem(
                text = {
                    Column {
                        Text(entry)
                        if (selected) {
                            Text(
                                text = stringResource(
                                    if (ascending) {
                                        R.string.pref_app_sort_ascending
                                    } else {
                                        R.string.pref_app_sort_descending
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                        )
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                },
                trailingIcon = if (selected) {
                    {
                        Icon(
                            painter = painterResource(
                                if (ascending) {
                                    R.drawable.ic_arrow_upward
                                } else {
                                    R.drawable.ic_arrow_downward
                                },
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                onClick = { onSelected(index) },
            )
        }
    }
}

private data class DialogActionEntry(
    val label: Int,
    val icon: Int,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppActionsDialog(
    app: LibraryAppUiItem,
    controllerEvents: Flow<LibraryControllerEvent>? = null,
    onControllerKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onDismiss: () -> Unit,
    onShortcut: (() -> Unit)?,
    onSettings: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
    onDetails: (() -> Unit)? = null,
    onAddToCollection: (() -> Unit)? = null,
    onRemoveFromCollection: (() -> Unit)? = null,
    onExportAppBundle: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    iconRatio: LibraryIconRatio = LibraryIconRatio.Square,
    iconShape: LibraryIconShape = LibraryIconShape.Round,
    enhancedIcons: Boolean = true,
) {
    val windowWidth = availableWindowWidthDp()
    val measuredWindowHeight = availableWindowHeightDp()
    val windowHeight = remember(app.databaseId, windowWidth) { measuredWindowHeight }
    val useSheet = windowWidth < 600.dp && windowHeight >= 500.dp
    val compactHeader = windowHeight < 480.dp
    val listState = rememberLazyListState()
    val sheetFocusRequester = remember { FocusRequester() }
    LaunchedEffect(useSheet) {
        if (useSheet) {
            withFrameNanos { }
            sheetFocusRequester.requestFocus()
        }
    }
    var focusedIndex by rememberSaveable(app.databaseId) { mutableIntStateOf(0) }
    var controllerFocusVisible by remember(app.databaseId) { mutableStateOf(false) }
    val dialogControllerKeyEvent = onControllerKeyEvent?.let { handler ->
        { event: KeyEvent ->
            if (isNavigationKeyEvent(event)) controllerFocusVisible = true
            handler(event)
        }
    }

    val entries = buildList {
        if (onSelect != null) {
            add(DialogActionEntry(
                label = R.string.library_action_select,
                icon = R.drawable.ic_check,
                action = onSelect,
            ))
        }
        if (onDetails != null) {
            add(DialogActionEntry(
                label = R.string.library_details_action,
                icon = R.drawable.ic_info,
                action = onDetails,
            ))
        }
        add(DialogActionEntry(
            label = R.string.action_settings,
            icon = R.drawable.ic_settings,
            action = onSettings,
        ))
        if (onAddToCollection != null) {
            add(DialogActionEntry(
                label = R.string.library_bulk_add_collection,
                icon = R.drawable.ic_collections,
                action = onAddToCollection,
            ))
        }
        if (onRemoveFromCollection != null) {
            add(DialogActionEntry(
                label = R.string.library_collection_remove_from_current,
                icon = R.drawable.ic_remove_from_collection,
                action = onRemoveFromCollection,
            ))
        }
        if (onShortcut != null) {
            add(DialogActionEntry(
                label = R.string.action_context_shortcut,
                icon = R.drawable.ic_add_shortcut,
                action = onShortcut,
            ))
        }
        if (onExportAppBundle != null) {
            add(DialogActionEntry(
                label = R.string.library_action_export_bundle,
                icon = R.drawable.ic_file_download,
                action = onExportAppBundle,
            ))
        }
        if (app.canReinstall) {
            add(DialogActionEntry(
                label = R.string.action_reinstall,
                icon = R.drawable.ic_reinstall,
                action = onReinstall,
            ))
        }
        add(DialogActionEntry(
            label = R.string.action_context_delete,
            icon = R.drawable.ic_delete,
            destructive = true,
            action = onDelete,
        ))
    }
    // Adaptive dialogs are narrower than the full landscape window.
    val tileViewportWidth = if (useSheet) {
        windowWidth - 32.dp
    } else {
        adaptiveDialogLayout().width - 48.dp
    }
    val tileMinWidth = if (LocalDensity.current.fontScale >= 1.5f) 240.dp else 148.dp
    val gridColumns = ((tileViewportWidth + 8.dp) / (tileMinWidth + 8.dp))
        .toInt().coerceIn(1, entries.size.coerceAtLeast(1))
    val gridRows = (entries.size + gridColumns - 1) / gridColumns

    val currentEntries by rememberUpdatedState(entries)
    val currentGridColumns by rememberUpdatedState(gridColumns)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(entries.size) {
        focusedIndex = focusedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(controllerEvents) {
        controllerEvents?.collect { event ->
            when (event.command) {
                LibraryControllerCommand.MoveUp, LibraryControllerCommand.MoveLeft,
                LibraryControllerCommand.MoveDown, LibraryControllerCommand.MoveRight -> {
                    val current = currentEntries
                    if (current.isNotEmpty()) {
                        controllerFocusVisible = true
                        val columns = currentGridColumns
                        val delta = when (event.command) {
                            LibraryControllerCommand.MoveUp -> -columns
                            LibraryControllerCommand.MoveDown -> columns
                            LibraryControllerCommand.MoveLeft -> -1
                            else -> 1
                        }
                        focusedIndex = (focusedIndex + delta).coerceIn(0, current.lastIndex)
                        listState.animateScrollToItem(focusedIndex / columns)
                    }
                }
                LibraryControllerCommand.Activate -> {
                    currentEntries.getOrNull(focusedIndex)?.let { entry ->
                        currentOnDismiss()
                        entry.action()
                    }
                }
                LibraryControllerCommand.Back -> currentOnDismiss()
                LibraryControllerCommand.OpenActions,
                LibraryControllerCommand.NextTab,
                LibraryControllerCommand.PreviousTab -> Unit
            }
        }
    }

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (useSheet) {
                    Modifier
                        .focusRequester(sheetFocusRequester)
                        .onPreviewKeyEvent { event ->
                            dialogControllerKeyEvent?.invoke(event.nativeKeyEvent) == true
                        }
                        .focusable()
                } else Modifier)
                .clearNavigationFocusOnTouch { controllerFocusVisible = false }
                .showNavigationFocusForKey { controllerFocusVisible = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (compactHeader) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LibraryIconSlot(
                    app = app,
                    modifier = Modifier.width(if (compactHeader) 44.dp else 60.dp),
                    contentSize = if (compactHeader) 36.dp else 48.dp,
                    iconRatio = iconRatio,
                    iconShape = iconShape,
                    enhancedIcons = enhancedIcons,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.title,
                        style = if (compactHeader) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.titleLarge,
                        maxLines = if (compactHeader) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.library_vendor_version, app.author, app.version),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compactHeader && (app.playCount > 0L || app.totalPlayTimeMs > 0L)) {
                        Text(
                            stringResource(
                                R.string.library_play_stats_summary,
                                app.playCount,
                                LibraryPlayStatsFormatter.duration(app.totalPlayTimeMs),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (onFavorite != null) {
                    IconButton(onClick = {
                        onDismiss()
                        onFavorite()
                    }) {
                        Icon(
                            painter = painterResource(
                                if (app.favorite) R.drawable.ic_star_filled
                                else R.drawable.ic_star,
                            ),
                            contentDescription = stringResource(
                                if (app.favorite) R.string.library_remove_from_favorites_action
                                else R.string.library_add_to_favorites_action,
                            ),
                        )
                    }
                }
            }
            val maxBodyHeight = (windowHeight - if (compactHeader) 144.dp else 196.dp)
                .coerceAtLeast(100.dp)
            val actionListHeight = Modifier.heightIn(max = maxBodyHeight)
            Box(modifier = Modifier.fillMaxWidth().then(actionListHeight)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().then(actionListHeight),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(gridRows) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(gridColumns) { columnIndex ->
                                val index = rowIndex * gridColumns + columnIndex
                                if (index < entries.size) {
                                    val entry = entries[index]
                                    LibrarySheetTile(
                                        title = stringResource(entry.label),
                                        icon = entry.icon,
                                        focused = controllerFocusVisible && focusedIndex == index,
                                        destructive = entry.destructive,
                                        onClick = {
                                            onDismiss()
                                            entry.action()
                                        },
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                ScrollableContentHint(
                    visible = rememberLazyListCanScrollForward(listState),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (useSheet) {
        LibraryModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            body()
        }
    } else {
        AlertDialog(
            textScrollable = false,
            onControllerKeyEvent = dialogControllerKeyEvent,
            onDismissRequest = onDismiss,
            text = body,
            confirmButton = null,
        )
    }
}

