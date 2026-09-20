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

package io.github.h3nb.jlmodplus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward
import io.github.h3nb.jlmodplus.ui.ControllerDialogInputScope
import io.github.h3nb.jlmodplus.ui.ControllerHostCommandHandler
import io.github.h3nb.jlmodplus.input.HostCommand
import android.view.KeyEvent
import android.view.MotionEvent

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

internal sealed interface MainHostDialog {
    data class MidletFailure(val message: String) : MainHostDialog
    data class ProcessExit(val message: String) : MainHostDialog
    data class DirectoryFailure(val message: String) : MainHostDialog
    data class DirectoryMissing(val message: String) : MainHostDialog
    data object PermissionFailure : MainHostDialog
}

internal data class MainHostUiState(
    val dialog: MainHostDialog? = null,
)

/** Compose-only overlay for host notices. MainActivity still owns all side effects and results. */
internal class MainActivityComposeController(
    composeView: ComposeView,
    private val actions: MainHostActions,
    private val dialogKeyDispatcher: (KeyEvent) -> Boolean,
    private val dialogMotionDispatcher: (MotionEvent) -> Boolean,
    private val onControllerTargetChanging: Runnable,
) {
    private var state by mutableStateOf(MainHostUiState())
    private var dialogHostCommandHandler: ControllerHostCommandHandler? = null

    init {
        composeView.id = R.id.main_host_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = dialogKeyDispatcher,
                    onControllerMotionEvent = dialogMotionDispatcher,
                    onControllerHostCommandHandlerChanged = { dialogHostCommandHandler = it },
                ) {
                    MainHostDialogs(state = state, actions = actions)
                }
            }
        }
    }

    fun isDialogVisible(): Boolean = state.dialog != null

    private fun replaceDialog(dialog: MainHostDialog?) {
        if (state.dialog == dialog) return
        // This may run re-entrantly from controller Activate; release old semantics first.
        onControllerTargetChanging.run()
        dialogHostCommandHandler = null
        state = MainHostUiState(dialog)
    }

    fun dismiss() = replaceDialog(null)

    fun showMidletFailure(message: String) =
        replaceDialog(MainHostDialog.MidletFailure(message))

    fun showProcessExit(message: String) =
        replaceDialog(MainHostDialog.ProcessExit(message))

    fun showDirectoryFailure(message: String) =
        replaceDialog(MainHostDialog.DirectoryFailure(message))

    fun showDirectoryMissing(message: String) =
        replaceDialog(MainHostDialog.DirectoryMissing(message))

    fun showPermissionFailure() =
        replaceDialog(MainHostDialog.PermissionFailure)

    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (state.dialog == null) return false
        // The focused Compose control owns the selected action; do not reduce multi-action
        // dialogs to a generic dismiss.
        return dialogHostCommandHandler?.invoke(command, pressed) ?: true
    }

}

private data class MainHostDialogLayout(
    val modifier: androidx.compose.ui.Modifier,
    val properties: DialogProperties,
)

@Composable
private fun mainHostDialogLayout(): MainHostDialogLayout {
    return MainHostDialogLayout(
        modifier = androidx.compose.ui.Modifier,
        properties = DialogProperties(),
    )
}

@Composable
private fun MainHostDialogText(message: String) {
    val maxHeight = adaptiveDialogLayout().maxHeight
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState)
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        Text(
            text = message,
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scrollState),
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        ScrollableContentHint(
            visible = canScrollForward,
            modifier = androidx.compose.ui.Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun MainHostWarningIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_warning),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun MainHostDialogs(
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
        is MainHostDialog.DirectoryFailure -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},
            icon = { MainHostWarningIcon() },
            title = { Text(stringResource(R.string.error)) },
            text = { MainHostDialogText(dialog.message) },
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
        ) }
        is MainHostDialog.DirectoryMissing -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},
            icon = { MainHostWarningIcon() },
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { MainHostDialogText(dialog.message) },
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
        ) }
        MainHostDialog.PermissionFailure -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},
            icon = { MainHostWarningIcon() },
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { MainHostDialogText(stringResource(R.string.permission_request_failed)) },
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
        ) }
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
    val layout = mainHostDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = {},
        icon = { MainHostWarningIcon() },
        title = { Text(title) },
        text = { MainHostDialogText(message) },
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
