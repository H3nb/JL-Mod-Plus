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

package ru.playsoftware.j2meloader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

/** Actions stay in MainActivity so permission, picker, recovery, and Fragment contracts remain host-owned. */
internal interface MainHostActions {
    fun onViewMidletReports()
    fun onCloseMidletNotice()
    fun onViewProcessReports()
    fun onCloseProcessNotice()
    fun onChooseDirectory()
    fun onCreateDirectory()
    fun onRetryPermission()
    fun onExit()
}

private sealed interface MainHostDialog {
    data class MidletFailure(val message: String) : MainHostDialog
    data class ProcessExit(val message: String) : MainHostDialog
    data class DirectoryFailure(val message: String) : MainHostDialog
    data class DirectoryMissing(val message: String) : MainHostDialog
    data object PermissionFailure : MainHostDialog
}

private data class MainHostUiState(
    val dialog: MainHostDialog? = null,
)

/** Compose-only overlay for host notices. MainActivity still owns all side effects and results. */
internal class MainActivityComposeController(
    composeView: ComposeView,
    private val actions: MainHostActions,
) {
    private var state by mutableStateOf(MainHostUiState())

    init {
        composeView.id = R.id.main_host_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                MainHostDialogs(state = state, actions = actions)
            }
        }
    }

    fun isDialogVisible(): Boolean = state.dialog != null

    fun dismiss() {
        state = MainHostUiState()
    }

    fun showMidletFailure(message: String) {
        state = MainHostUiState(MainHostDialog.MidletFailure(message))
    }

    fun showProcessExit(message: String) {
        state = MainHostUiState(MainHostDialog.ProcessExit(message))
    }

    fun showDirectoryFailure(message: String) {
        state = MainHostUiState(MainHostDialog.DirectoryFailure(message))
    }

    fun showDirectoryMissing(message: String) {
        state = MainHostUiState(MainHostDialog.DirectoryMissing(message))
    }

    fun showPermissionFailure() {
        state = MainHostUiState(MainHostDialog.PermissionFailure)
    }
}

@Composable
private fun MainHostDialogs(
    state: MainHostUiState,
    actions: MainHostActions,
) {
    when (val dialog = state.dialog) {
        is MainHostDialog.MidletFailure -> RecoveryDialog(
            title = stringResource(R.string.midlet_failure_recovery_title),
            message = dialog.message,
            onViewReports = actions::onViewMidletReports,
            onClose = actions::onCloseMidletNotice,
        )
        is MainHostDialog.ProcessExit -> RecoveryDialog(
            title = stringResource(R.string.process_exit_recovery_title),
            message = dialog.message,
            onViewReports = actions::onViewProcessReports,
            onClose = actions::onCloseProcessNotice,
        )
        is MainHostDialog.DirectoryFailure -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.error)) },
            text = { Text(dialog.message) },
            dismissButton = {
                TextButton(onClick = actions::onExit) {
                    Text(stringResource(R.string.exit))
                }
            },
            confirmButton = {
                TextButton(onClick = actions::onChooseDirectory) {
                    Text(stringResource(R.string.choose))
                }
            },
        )
        is MainHostDialog.DirectoryMissing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(dialog.message) },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = actions::onExit) {
                        Text(stringResource(R.string.exit))
                    }
                    TextButton(onClick = actions::onChooseDirectory) {
                        Text(stringResource(R.string.change))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = actions::onCreateDirectory) {
                    Text(stringResource(R.string.create))
                }
            },
        )
        MainHostDialog.PermissionFailure -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.permission_request_failed)) },
            dismissButton = {
                TextButton(onClick = actions::onRetryPermission) {
                    Text(stringResource(R.string.retry))
                }
            },
            confirmButton = {
                TextButton(onClick = actions::onExit) {
                    Text(stringResource(R.string.exit))
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun RecoveryDialog(
    title: String,
    message: String,
    onViewReports: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = {
            TextButton(onClick = onViewReports) {
                Text(stringResource(R.string.view_reports))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
