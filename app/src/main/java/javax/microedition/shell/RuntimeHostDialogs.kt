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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward

/** Callbacks for host-owned runtime dialogs. MIDP state and rendering remain in Java. */
interface RuntimeHostDialogActions {
    fun onMidletSelected(index: Int)
    fun onMidletCancelled()
    fun onErrorAcknowledged()
    fun onExitConfirmed(openSettings: Boolean)
    fun onHideButtonsConfirmed(states: BooleanArray)
    fun onSaveVirtualKeyboard(updateTarget: String?)
    fun onVirtualKeyboardEditSaved(updateTarget: String?) = Unit
    fun onVirtualKeyboardEditDiscarded() = Unit
    fun onVirtualKeyboardEditContinued() = Unit
    fun onLayoutSelected(index: Int, updateTarget: String?)
    fun onLayoutEditGuideConfirmed(dontShowAgain: Boolean) = Unit
}

internal sealed interface RuntimeHostDialogState {
    data class MidletSelection(val names: List<String>) : RuntimeHostDialogState
    data class Error(val message: String) : RuntimeHostDialogState
    data object ExitConfirmation : RuntimeHostDialogState
    data class HideButtons(val names: List<String>, val checked: BooleanArray) : RuntimeHostDialogState
    data class SaveVirtualKeyboard(
        val updateTarget: String? = null,
    ) : RuntimeHostDialogState
    data class FinishVirtualKeyboardEdit(
        val updateTarget: String? = null,
    ) : RuntimeHostDialogState
    data class LayoutSelection(
        val entries: List<String>,
        val selected: Int,
        val updateTarget: String? = null,
    ) : RuntimeHostDialogState
    data object LayoutEditGuide : RuntimeHostDialogState
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
        is RuntimeHostDialogState.FinishVirtualKeyboardEdit -> FinishVirtualKeyboardEditDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
        is RuntimeHostDialogState.LayoutSelection -> LayoutSelectionDialog(
            state = state,
            actions = actions,
            onDismiss = onDismiss,
        )
        RuntimeHostDialogState.LayoutEditGuide -> LayoutEditGuideDialog(
            actions = actions,
            onDismiss = onDismiss,
        )
    }
}

private data class RuntimeDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

@Composable
private fun runtimeDialogLayout(): RuntimeDialogLayout {
    return RuntimeDialogLayout(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(),
    )
}

@Composable
private fun runtimeDialogListHeight() =
    adaptiveDialogLayout().maxHeight

@Composable
private fun MidletSelectionDialog(
    state: RuntimeHostDialogState.MidletSelection,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    val layout = runtimeDialogLayout()
    val listState = rememberLazyListState()
    val maxListHeight = runtimeDialogListHeight()
    val canScrollForward = rememberLazyListCanScrollForward(listState)
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = {
            onDismiss()
            actions.onMidletCancelled()
        },
        title = { Text(stringResource(R.string.select_dialog_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    state = listState,
                ) {
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
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = null,
    )
}

@Composable
private fun ErrorDialog(
    message: String,
    onAcknowledge: () -> Unit,
) {
    val layout = runtimeDialogLayout()
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onAcknowledge,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.error)) },
        text = {
            val scrollState = rememberScrollState()
            val canScrollForward = rememberScrollCanScrollForward(scrollState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = runtimeDialogListHeight()),
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = runtimeDialogListHeight())
                        .verticalScroll(scrollState),
                )
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
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
    val layout = runtimeDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_logout),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
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
                Button(
                    onClick = { onDismiss(); actions.onExitConfirmed(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.exit))
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
    val layout = runtimeDialogLayout()
    val listState = rememberLazyListState()
    val maxListHeight = runtimeDialogListHeight()
    val canScrollForward = rememberLazyListCanScrollForward(listState)
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hide_buttons)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    state = listState,
                ) {
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
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                )
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
private fun PresetDestinationOptions(
    titleRes: Int,
    updateTarget: String?,
    updatePreset: Boolean,
    onUpdatePresetChanged: (Boolean) -> Unit,
) {
    if (updateTarget == null) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(titleRes))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            PresetDestinationRow(
                label = stringResource(R.string.runtime_preset_this_midlet_only),
                selected = !updatePreset,
                onClick = { onUpdatePresetChanged(false) },
            )
            PresetDestinationRow(
                label = stringResource(R.string.runtime_preset_update_destination, updateTarget),
                selected = updatePreset,
                onClick = { onUpdatePresetChanged(true) },
            )
        }
        Text(
            text = if (updatePreset) {
                stringResource(R.string.runtime_preset_update_explanation, updateTarget)
            } else {
                stringResource(R.string.runtime_preset_local_explanation)
            },
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PresetDestinationRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
    }
}

@Composable
private fun FinishVirtualKeyboardEditDialog(
    state: RuntimeHostDialogState.FinishVirtualKeyboardEdit,
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    var updatePreset by remember(state) { mutableStateOf(false) }
    val layout = runtimeDialogLayout()
    val maxContentHeight = runtimeDialogListHeight()

    fun continueEditing() {
        onDismiss()
        actions.onVirtualKeyboardEditContinued()
    }

    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = ::continueEditing,
        title = { Text(stringResource(R.string.CONFIRMATION_REQUIRED)) },
        text = {
            val scrollState = rememberScrollState()
            val canScrollForward = rememberScrollCanScrollForward(scrollState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(scrollState),
                ) {
                    Text(stringResource(R.string.layout_edit_save_changes))
                    PresetDestinationOptions(
                        titleRes = R.string.runtime_preset_save_destination,
                        updateTarget = state.updateTarget,
                        updatePreset = updatePreset,
                        onUpdatePresetChanged = { updatePreset = it },
                    )
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(onClick = {
                    onDismiss()
                    actions.onVirtualKeyboardEditDiscarded()
                }) {
                    Text(stringResource(R.string.layout_edit_discard))
                }
                TextButton(onClick = ::continueEditing) {
                    Text(stringResource(R.string.layout_edit_continue))
                }
                Button(onClick = {
                    onDismiss()
                    actions.onVirtualKeyboardEditSaved(
                        state.updateTarget.takeIf { updatePreset },
                    )
                }) {
                    Text(stringResource(R.string.save))
                }
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
    var updatePreset by remember(state) { mutableStateOf(false) }
    val layout = runtimeDialogLayout()
    val maxContentHeight = runtimeDialogListHeight()
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.CONFIRMATION_REQUIRED))
        },
        text = {
            val scrollState = rememberScrollState()
            val canScrollForward = rememberScrollCanScrollForward(scrollState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(scrollState),
                ) {
                    Text(stringResource(R.string.pref_vk_save_alert))
                    PresetDestinationOptions(
                        titleRes = R.string.runtime_preset_save_destination,
                        updateTarget = state.updateTarget,
                        updatePreset = updatePreset,
                        onUpdatePresetChanged = { updatePreset = it },
                    )
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                actions.onSaveVirtualKeyboard(
                    state.updateTarget.takeIf { updatePreset },
                )
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
private fun LayoutEditGuideDialog(
    actions: RuntimeHostDialogActions,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    val layout = runtimeDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.runtime_virtual_controls_edit_guide_title))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.runtime_virtual_controls_edit_guide_message))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.runtime_virtual_controls_edit_guide_dont_show_again))
                    },
                    leadingContent = {
                        Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = dontShowAgain, role = Role.Checkbox) {
                            dontShowAgain = !dontShowAgain
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                actions.onLayoutEditGuideConfirmed(dontShowAgain)
            }) {
                Text(stringResource(android.R.string.ok))
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
    var pendingLayout by remember(state) { mutableStateOf<Int?>(null) }
    var updatePreset by remember(state) { mutableStateOf(false) }
    val layout = runtimeDialogLayout()
    val pendingIndex = pendingLayout

    if (pendingIndex == null) {
        val listState = rememberLazyListState()
        val maxListHeight = runtimeDialogListHeight()
        val canScrollForward = rememberLazyListCanScrollForward(listState)
        AlertDialog(
            textScrollable = false,
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.layout_switch)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxListHeight),
                        state = listState,
                    ) {
                        itemsIndexed(state.entries) { index, entry ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(entry) },
                                leadingContent = {
                                    RadioButton(
                                        selected = state.selected == index,
                                        onClick = null,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = state.selected == index,
                                        role = Role.RadioButton,
                                        onClick = {
                                            if (index == state.selected) {
                                                onDismiss()
                                            } else {
                                                updatePreset = false
                                                pendingLayout = index
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                    ScrollableContentHint(
                        visible = canScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            },
            confirmButton = null,
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
        return
    }

    val layoutName = state.entries.getOrNull(pendingIndex) ?: return
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.runtime_layout_apply_title, layoutName))
        },
        text = {
            if (state.updateTarget != null) {
                PresetDestinationOptions(
                    titleRes = R.string.runtime_preset_apply_destination,
                    updateTarget = state.updateTarget,
                    updatePreset = updatePreset,
                    onUpdatePresetChanged = { updatePreset = it },
                )
            } else {
                Text(
                    text = stringResource(R.string.runtime_preset_local_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                actions.onLayoutSelected(
                    pendingIndex,
                    state.updateTarget.takeIf { updatePreset },
                )
            }) {
                Text(stringResource(R.string.runtime_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
