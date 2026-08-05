/*
 * Copyright 2026 H3NB
 *
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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R

internal sealed interface HomeDialogState {
    data class Rename(val item: AppItem, val value: String) : HomeDialogState
    data class Delete(val item: AppItem) : HomeDialogState
    data class Sort(val selectedIndex: Int) : HomeDialogState
    data class DirectoryError(val path: String) : HomeDialogState
    data class CreateDirectory(val path: String) : HomeDialogState
    data object Permission : HomeDialogState
}

@Composable
internal fun HomeDialogs(
    dialog: HomeDialogState?,
    onDismiss: () -> Unit,
    onRenameValueChange: (String) -> Unit,
    onRenameConfirm: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onSortSelected: (Int) -> Unit,
    onCreateDirectory: () -> Unit,
    onChooseDirectory: () -> Unit,
    onRetryPermission: () -> Unit,
    onExit: () -> Unit,
) {
    when (dialog) {
        is HomeDialogState.Rename -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.action_context_rename)) },
            text = {
                TextField(
                    value = dialog.value,
                    onValueChange = onRenameValueChange,
                    label = { Text(stringResource(R.string.enter_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = onRenameConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is HomeDialogState.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.message_delete)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is HomeDialogState.Sort -> {
            val entries = stringArrayResource(R.array.pref_app_sort_entries)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.pref_app_sort_title)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        entries.forEachIndexed { index, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = index == dialog.selectedIndex,
                                    onClick = { onSortSelected(index) },
                                )
                                Text(
                                    text = entry,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        is HomeDialogState.DirectoryError -> AlertDialog(
            onDismissRequest = onExit,
            properties = nonCancelableDialogProperties(),
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(R.string.create_apps_dir_failed, dialog.path)) },
            dismissButton = {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.exit))
                }
            },
            confirmButton = {
                TextButton(onClick = onChooseDirectory) {
                    Text(stringResource(R.string.choose))
                }
            },
        )

        is HomeDialogState.CreateDirectory -> AlertDialog(
            onDismissRequest = onExit,
            properties = nonCancelableDialogProperties(),
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.alert_msg_workdir_not_exists, dialog.path)) },
            dismissButton = {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.exit))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = onChooseDirectory) {
                        Text(stringResource(R.string.change))
                    }
                    TextButton(onClick = onCreateDirectory) {
                        Text(stringResource(R.string.create))
                    }
                }
            },
        )

        HomeDialogState.Permission -> AlertDialog(
            onDismissRequest = onExit,
            properties = nonCancelableDialogProperties(),
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.permission_request_failed)) },
            dismissButton = {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.exit))
                }
            },
            confirmButton = {
                TextButton(onClick = onRetryPermission) {
                    Text(stringResource(R.string.retry))
                }
            },
        )

        null -> Unit
    }
}

private fun nonCancelableDialogProperties() = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
)
