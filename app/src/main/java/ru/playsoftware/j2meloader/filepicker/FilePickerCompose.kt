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

package ru.playsoftware.j2meloader.filepicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R

/** Event surface for the picker presentation. File-system work stays in the controller. */
interface FilePickerActions {
    fun onNavigateBack()
    fun onOpen(entry: FilePickerEntry)
    fun onConfirmSelection()
    fun onToggleSearch()
    fun onSearchQueryChanged(query: String)
    fun onSortOrderSelected(sortOrder: FilePickerSortOrder)
    fun onGrantPermission()
    fun onRetry()
    fun onShowCreateFolder()
    fun onDismissCreateFolder()
    fun onCreateFolderNameChanged(name: String)
    fun onCreateFolder()
}

private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

/**
 * App-owned picker screen. It deliberately uses only the repository's existing
 * Material 3 and foundation components; no forked picker widgets are embedded.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilePickerScreen(
    state: FilePickerState,
    actions: FilePickerActions,
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                navigationIcon = {
                    IconButton(onClick = actions::onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.file_picker_navigate_back),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(pickerTitle(state.request)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.currentPath,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions::onToggleSearch) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = stringResource(R.string.file_picker_search),
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sort),
                                contentDescription = stringResource(R.string.file_picker_sort),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            SortMenuItem(
                                label = stringResource(R.string.file_picker_sort_type),
                                selected = state.sortOrder == FilePickerSortOrder.TYPE_THEN_NAME,
                                onClick = {
                                    actions.onSortOrderSelected(FilePickerSortOrder.TYPE_THEN_NAME)
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.file_picker_sort_name_ascending),
                                selected = state.sortOrder == FilePickerSortOrder.NAME_ASCENDING,
                                onClick = {
                                    actions.onSortOrderSelected(FilePickerSortOrder.NAME_ASCENDING)
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.file_picker_sort_name_descending),
                                selected = state.sortOrder == FilePickerSortOrder.NAME_DESCENDING,
                                onClick = {
                                    actions.onSortOrderSelected(FilePickerSortOrder.NAME_DESCENDING)
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.file_picker_sort_modified_newest),
                                selected = state.sortOrder == FilePickerSortOrder.MODIFIED_NEWEST,
                                onClick = {
                                    actions.onSortOrderSelected(FilePickerSortOrder.MODIFIED_NEWEST)
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.file_picker_sort_modified_oldest),
                                selected = state.sortOrder == FilePickerSortOrder.MODIFIED_OLDEST,
                                onClick = {
                                    actions.onSortOrderSelected(FilePickerSortOrder.MODIFIED_OLDEST)
                                    sortMenuExpanded = false
                                },
                            )
                        }
                    }
                    if (state.request.allowCreateDirectory) {
                        IconButton(
                            onClick = actions::onShowCreateFolder,
                            enabled = !state.loading && !state.permissionRequired,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.file_picker_create_folder),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.request.mode == FilePickerContract.MODE_DIR ||
                (state.request.mode == FilePickerContract.MODE_FILE_AND_DIR &&
                    !state.request.allowMultiple && state.selectedPaths.isEmpty()) ||
                state.selectedPaths.isNotEmpty()
            ) {
                BottomAppBar(
                    windowInsets = NoWindowInsets,
                ) {
                    Text(
                        text = if (state.request.mode == FilePickerContract.MODE_DIR ||
                            (state.request.mode == FilePickerContract.MODE_FILE_AND_DIR &&
                                state.selectedPaths.isEmpty())
                        ) {
                            stringResource(R.string.file_picker_current_folder)
                        } else {
                            pluralStringResource(
                                R.plurals.file_picker_selected_count,
                                state.selectedPaths.size,
                                state.selectedPaths.size,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = actions::onConfirmSelection,
                        enabled = state.canConfirm,
                    ) {
                        Text(stringResource(R.string.file_picker_choose))
                    }
                }
            }
        },
) { padding ->
        PickerContent(
            state = state,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (state.showCreateFolder) {
        CreateFolderDialog(state = state, actions = actions)
    }
}

@Composable
private fun PickerContent(
    state: FilePickerState,
    actions: FilePickerActions,
    modifier: Modifier,
) {
    Column(modifier) {
        if (state.searchVisible) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = actions::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.file_picker_search)) },
                singleLine = true,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.permissionRequired -> PermissionContent(state, actions, Modifier.fillMaxSize())
                state.loading -> LoadingContent(Modifier.fillMaxSize())
                state.errorMessage != null -> ErrorContent(
                    state.errorMessage,
                    actions,
                    Modifier.fillMaxSize(),
                )
                state.entries.isEmpty() -> EmptyContent(
                    request = state.request,
                    modifier = Modifier.fillMaxSize(),
                    message = if (state.searchQuery.isBlank()) {
                        null
                    } else {
                        stringResource(R.string.file_picker_no_matches)
                    },
                )
                else -> {
                    val visibleEntries = FilePickerRules.filterAndSort(
                        state.entries,
                        state.searchQuery,
                        state.sortOrder,
                    )
                    if (visibleEntries.isEmpty()) {
                        EmptyContent(
                            request = state.request,
                            modifier = Modifier.fillMaxSize(),
                            message = stringResource(R.string.file_picker_no_matches),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                vertical = 8.dp,
                            ),
                        ) {
                            if (state.canGoUp) {
                                item(key = "..:${state.currentPath}") {
                                    ListItem(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(onClick = actions::onNavigateBack)
                                            .semantics { role = Role.Button },
                                        headlineContent = {
                                            Text(stringResource(R.string.file_picker_parent_marker))
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.file_picker_parent_folder))
                                        },
                                        leadingContent = {
                                            PickerEntryIcon(FilePickerEntryKind.DIRECTORY)
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                            items(visibleEntries, key = { it.path }) { entry ->
                                PickerEntryRow(
                                    entry = entry,
                                    selected = state.selectedPaths.contains(entry.path),
                                    allowSelection = state.request.allowsFiles &&
                                        entry.kind == FilePickerEntryKind.FILE,
                                    onClick = { actions.onOpen(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            {
                Text(
                    stringResource(R.string.file_picker_selected_mark),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun PickerEntryRow(
    entry: FilePickerEntry,
    selected: Boolean,
    allowSelection: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (entry.kind == FilePickerEntryKind.FILE) {
            { Text(stringResource(R.string.file_picker_file)) }
        } else if (entry.kind == FilePickerEntryKind.VOLUME) {
            { Text(stringResource(R.string.file_picker_storage_volume)) }
        } else {
            null
        },
        leadingContent = { PickerEntryIcon(entry.kind) },
        trailingContent = if (allowSelection) {
            {
                androidx.compose.material3.Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun PickerEntryIcon(kind: FilePickerEntryKind) {
    val icon = when (kind) {
        FilePickerEntryKind.FILE -> R.drawable.ic_file_picker_file
        FilePickerEntryKind.DIRECTORY -> R.drawable.ic_file_picker_folder
        FilePickerEntryKind.VOLUME -> R.drawable.ic_file_picker_storage
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
    )
}

@Composable
private fun LoadingContent(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(
    request: FilePickerRequest,
    modifier: Modifier,
    message: String? = null,
) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message ?: stringResource(
                if (request.allowsFiles) R.string.file_picker_empty
                else R.string.file_picker_empty_folder,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionContent(
    state: FilePickerState,
    actions: FilePickerActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.file_picker_permission_required),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.errorMessage
                ?: stringResource(R.string.file_picker_permission_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = actions::onGrantPermission) {
            Text(stringResource(R.string.file_picker_grant_access))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    actions: FilePickerActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions::onRetry) {
            Text(stringResource(R.string.file_picker_retry))
        }
    }
}

@Composable
private fun CreateFolderDialog(
    state: FilePickerState,
    actions: FilePickerActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissCreateFolder,
        title = { Text(stringResource(R.string.file_picker_create_folder_title)) },
        text = {
            OutlinedTextField(
                value = state.createFolderName,
                onValueChange = actions::onCreateFolderNameChanged,
                label = { Text(stringResource(R.string.file_picker_folder_name)) },
                supportingText = state.createFolderError?.let { error ->
                    { Text(error, color = MaterialTheme.colorScheme.error) }
                },
                isError = state.createFolderError != null,
                singleLine = true,
            )
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissCreateFolder) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::onCreateFolder,
                enabled = state.createFolderName.isNotBlank() && !state.loading,
            ) {
                Text(stringResource(R.string.create))
            }
        },
    )
}

private fun pickerTitle(request: FilePickerRequest): Int = when (request.mode) {
    FilePickerContract.MODE_DIR -> R.string.file_picker_select_folder
    else -> R.string.file_picker_select_file
}
