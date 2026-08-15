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

package javax.microedition.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R

/** Callbacks for host-owned runtime dialogs. MIDP state and rendering remain in Java. */
interface RuntimeHostDialogActions {
    fun onMidletSelected(index: Int)
    fun onMidletCancelled()
    fun onErrorAcknowledged()
    fun onExitConfirmed(openSettings: Boolean)
    fun onHideButtonsConfirmed(states: BooleanArray)
    fun onSaveVirtualKeyboard(saveScreenParams: Boolean)
    fun onLayoutSelected(index: Int)
}

internal sealed interface RuntimeHostDialogState {
    data class MidletSelection(val names: List<String>) : RuntimeHostDialogState
    data class Error(val message: String) : RuntimeHostDialogState
    data object ExitConfirmation : RuntimeHostDialogState
    data class HideButtons(val names: List<String>, val checked: BooleanArray) : RuntimeHostDialogState
    data class SaveVirtualKeyboard(
        val phone: Boolean,
        val keepScreenPreferred: Boolean,
    ) : RuntimeHostDialogState
    data class LayoutSelection(val entries: List<String>, val selected: Int) : RuntimeHostDialogState
}

@Composable
internal fun RuntimeHostDialogs(
    state: RuntimeHostDialogState?,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    when (state) {
        null -> Unit
        is RuntimeHostDialogState.MidletSelection -> MidletSelectionDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
        is RuntimeHostDialogState.Error -> ErrorDialog(
            message = state.message,
            onAcknowledge = {
                onDismiss()
                actions.onErrorAcknowledged()
            },
        )
        RuntimeHostDialogState.ExitConfirmation -> ExitConfirmationDialog(
            actions = actions,
            onDismiss = onDismiss,
        )
        is RuntimeHostDialogState.HideButtons -> HideButtonsDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
        is RuntimeHostDialogState.SaveVirtualKeyboard -> SaveVirtualKeyboardDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
        is RuntimeHostDialogState.LayoutSelection -> LayoutSelectionDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun runtimeDialogListHeight(maxHeight: Int = 420) =
    LocalConfiguration.current.screenHeightDp
        .minus(220)
        .coerceAtLeast(120)
        .coerceAtMost(maxHeight)
        .dp

@Composable
private fun MidletSelectionDialog(
    state: RuntimeHostDialogState.MidletSelection,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp),
        onDismissRequest = {
            onDismiss()
            actions.onMidletCancelled()
        },
        title = { Text(stringResource(R.string.select_dialog_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = runtimeDialogListHeight())) {
                itemsIndexed(state.names) { index, name ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                onDismiss()
                                actions.onMidletSelected(index)
                            },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ErrorDialog(
    message: String,
    onAcknowledge: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.error)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun ExitConfirmationDialog(
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.CONFIRMATION_REQUIRED))
        },
        text = {
            Text(stringResource(R.string.FORCE_CLOSE_CONFIRMATION))
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onDismiss(); actions.onExitConfirmed(true) }) {
                    Text(stringResource(R.string.action_settings))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(onClick = { onDismiss(); actions.onExitConfirmed(false) }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
    )
}

@Composable
private fun HideButtonsDialog(
    state: RuntimeHostDialogState.HideButtons,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    var checked by remember(state) { mutableStateOf(state.checked.copyOf()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hide_buttons)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = runtimeDialogListHeight())) {
                itemsIndexed(state.names) { index, name ->
                    val isChecked = checked.getOrNull(index) == true
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = isChecked, role = Role.Checkbox) {
                                checked = checked.copyOf().also { copy ->
                                    if (index in copy.indices) copy[index] = !isChecked
                                }
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); actions.onHideButtonsConfirmed(checked) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SaveVirtualKeyboardDialog(
    state: RuntimeHostDialogState.SaveVirtualKeyboard,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    var saveScreenParams by remember(state) { mutableStateOf(state.keepScreenPreferred) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.CONFIRMATION_REQUIRED))
        },
        text = {
            Column {
                Text(stringResource(R.string.pref_vk_save_alert))
                if (state.phone) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(stringResource(R.string.opt_save_screen_params))
                        },
                        leadingContent = {
                            Checkbox(checked = saveScreenParams, onCheckedChange = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = saveScreenParams, role = Role.Checkbox) {
                                saveScreenParams = !saveScreenParams
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                actions.onSaveVirtualKeyboard(saveScreenParams)
            }) {
                Text(stringResource(android.R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.no))
            }
        },
    )
}

@Composable
private fun LayoutSelectionDialog(
    state: RuntimeHostDialogState.LayoutSelection,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    var selected by remember(state) { mutableIntStateOf(state.selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_switch)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = runtimeDialogListHeight())) {
                itemsIndexed(state.entries) { index, entry ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(entry) },
                        leadingContent = {
                            RadioButton(
                                selected = selected == index,
                                onClick = { selected = index },
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { selected = index },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); actions.onLayoutSelected(selected) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
