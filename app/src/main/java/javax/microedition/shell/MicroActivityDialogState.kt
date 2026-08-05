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

package javax.microedition.shell

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

class MicroDialogHandle(private val owner: MicroActivityDialogState) {
    fun dismiss() {
        owner.dismiss()
    }
}

class MicroActivityDialogState {
    private sealed interface DialogState {
        data class Message(
            val title: String,
            val message: String,
            val positiveLabel: String?,
            val negativeLabel: String?,
            val neutralLabel: String?,
            val cancelable: Boolean,
            val positiveAction: Runnable?,
            val negativeAction: Runnable?,
            val neutralAction: Runnable?,
            val onCanceled: Runnable?,
            val dismissOnAction: Boolean,
        ) : DialogState

        data class Choice(
            val title: String,
            val entries: Array<String>,
            val selectedIndex: Int,
            val cancelLabel: String?,
            val cancelable: Boolean,
            val onSelected: MicroChoiceAction,
            val onCanceled: Runnable?,
        ) : DialogState

        data class MultiChoice(
            val title: String,
            val entries: Array<String>,
            val checked: BooleanArray,
            val positiveLabel: String,
            val negativeLabel: String?,
            val cancelable: Boolean,
            val onConfirmed: MicroMultiChoiceAction,
            val onCanceled: Runnable?,
        ) : DialogState

        data class CheckboxMessage(
            val title: String,
            val message: String,
            val checkboxLabel: String,
            val initiallyChecked: Boolean,
            val positiveLabel: String,
            val negativeLabel: String?,
            val cancelable: Boolean,
            val onConfirmed: MicroCheckboxAction,
            val onCanceled: Runnable?,
        ) : DialogState

        data class ChoiceActions(
            val title: String,
            val entries: Array<String>,
            val selectedIndex: Int,
            val positiveLabel: String?,
            val neutralLabel: String?,
            val negativeLabel: String?,
            val cancelable: Boolean,
            val neutralRequiresSelection: Boolean,
            val positiveAction: MicroChoiceButtonAction?,
            val neutralAction: MicroChoiceButtonAction?,
            val negativeAction: Runnable?,
            val onCanceled: Runnable?,
        ) : DialogState

        data class TextInputActions(
            val title: String,
            val label: String,
            val initialValue: String,
            val numericOnly: Boolean,
            val positiveLabel: String,
            val neutralLabel: String?,
            val negativeLabel: String?,
            val cancelable: Boolean,
            val onConfirmed: MicroTextInputAction,
            val neutralAction: Runnable?,
            val onCanceled: Runnable?,
        ) : DialogState
    }

    private var dialogState by mutableStateOf<DialogState?>(null)

    val isDialogVisible: Boolean
        get() = dialogState != null

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showMessage(
        context: Context,
        title: String,
        message: String,
        positiveLabel: String?,
        negativeLabel: String?,
        neutralLabel: String?,
        cancelable: Boolean,
        positiveAction: Runnable?,
        negativeAction: Runnable?,
        neutralAction: Runnable?,
        onCanceled: Runnable? = null,
        dismissOnAction: Boolean = true,
    ): MicroDialogHandle {
        dialogState = DialogState.Message(
            title,
            message,
            positiveLabel,
            negativeLabel,
            neutralLabel,
            cancelable,
            positiveAction,
            negativeAction,
            neutralAction,
            onCanceled,
            dismissOnAction,
        )
        return MicroDialogHandle(this)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showChoice(
        context: Context,
        title: String,
        entries: Array<String>,
        selectedIndex: Int,
        cancelLabel: String?,
        cancelable: Boolean,
        onSelected: MicroChoiceAction,
        onCanceled: Runnable? = null,
    ): MicroDialogHandle {
        dialogState = DialogState.Choice(
            title,
            entries.copyOf(),
            selectedIndex,
            cancelLabel,
            cancelable,
            onSelected,
            onCanceled,
        )
        return MicroDialogHandle(this)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showMultiChoice(
        context: Context,
        title: String,
        entries: Array<String>,
        checked: BooleanArray,
        positiveLabel: String,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: MicroMultiChoiceAction,
        onCanceled: Runnable? = null,
    ): MicroDialogHandle {
        dialogState = DialogState.MultiChoice(
            title,
            entries.copyOf(),
            checked.copyOf(),
            positiveLabel,
            negativeLabel,
            cancelable,
            onConfirmed,
            onCanceled,
        )
        return MicroDialogHandle(this)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showCheckboxMessage(
        context: Context,
        title: String,
        message: String,
        checkboxLabel: String,
        initiallyChecked: Boolean,
        positiveLabel: String,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: MicroCheckboxAction,
        onCanceled: Runnable? = null,
    ): MicroDialogHandle {
        dialogState = DialogState.CheckboxMessage(
            title,
            message,
            checkboxLabel,
            initiallyChecked,
            positiveLabel,
            negativeLabel,
            cancelable,
            onConfirmed,
            onCanceled,
        )
        return MicroDialogHandle(this)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showChoiceActions(
        context: Context,
        title: String,
        entries: Array<String>,
        selectedIndex: Int,
        positiveLabel: String?,
        neutralLabel: String?,
        negativeLabel: String?,
        cancelable: Boolean,
        neutralRequiresSelection: Boolean,
        positiveAction: MicroChoiceButtonAction?,
        neutralAction: MicroChoiceButtonAction?,
        negativeAction: Runnable?,
        onCanceled: Runnable? = null,
    ): MicroDialogHandle {
        dialogState = DialogState.ChoiceActions(
            title,
            entries.copyOf(),
            selectedIndex,
            positiveLabel,
            neutralLabel,
            negativeLabel,
            cancelable,
            neutralRequiresSelection,
            positiveAction,
            neutralAction,
            negativeAction,
            onCanceled,
        )
        return MicroDialogHandle(this)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun showTextInputActions(
        context: Context,
        title: String,
        label: String,
        initialValue: String,
        numericOnly: Boolean,
        positiveLabel: String,
        neutralLabel: String?,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: MicroTextInputAction,
        neutralAction: Runnable?,
        onCanceled: Runnable? = null,
    ): MicroDialogHandle {
        dialogState = DialogState.TextInputActions(
            title,
            label,
            initialValue,
            numericOnly,
            positiveLabel,
            neutralLabel,
            negativeLabel,
            cancelable,
            onConfirmed,
            neutralAction,
            onCanceled,
        )
        return MicroDialogHandle(this)
    }

    fun dismiss() {
        dialogState = null
    }

    @Composable
    fun Render() {
        when (val current = dialogState) {
            null -> Unit
            is DialogState.Message -> MessageDialog(this, current)
            is DialogState.Choice -> ChoiceDialog(this, current)
            is DialogState.MultiChoice -> MultiChoiceDialog(this, current)
            is DialogState.CheckboxMessage -> CheckboxMessageDialog(this, current)
            is DialogState.ChoiceActions -> ChoiceActionsDialog(this, current)
            is DialogState.TextInputActions -> TextInputActionsDialog(this, current)
        }
    }

    @Composable
    private fun MessageDialog(owner: MicroActivityDialogState, dialog: DialogState.Message) {
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                Text(
                    text = dialog.message,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dialog.positiveLabel?.let { label ->
                        TextButton(onClick = {
                            dialog.positiveAction?.run()
                            if (dialog.dismissOnAction) owner.dismiss()
                        }) { Text(label) }
                    }
                    dialog.neutralLabel?.let { label ->
                        TextButton(onClick = {
                            dialog.neutralAction?.run()
                            if (dialog.dismissOnAction) owner.dismiss()
                        }) { Text(label) }
                    }
                    dialog.negativeLabel?.let { label ->
                        TextButton(onClick = {
                            dialog.negativeAction?.run()
                            if (dialog.dismissOnAction) owner.dismiss()
                        }) { Text(label) }
                    }
                }
            },
        )
    }

    @Composable
    private fun ChoiceDialog(owner: MicroActivityDialogState, dialog: DialogState.Choice) {
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(dialog.entries.size) { index ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                owner.dismiss()
                                dialog.onSelected.onSelected(index)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == dialog.selectedIndex,
                                onClick = {
                                    owner.dismiss()
                                    dialog.onSelected.onSelected(index)
                                },
                            )
                            Text(dialog.entries[index], modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                dialog.cancelLabel?.let { label ->
                    TextButton(onClick = owner::dismiss) { Text(label) }
                }
            },
        )
    }

    @Composable
    private fun MultiChoiceDialog(owner: MicroActivityDialogState, dialog: DialogState.MultiChoice) {
        var checked by remember(dialog) { mutableStateOf(dialog.checked.copyOf()) }
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(dialog.entries.size) { index ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                checked = checked.copyOf().also { it[index] = !it[index] }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked.getOrElse(index) { false },
                                onCheckedChange = {
                                    checked = checked.copyOf().also { it[index] = it[index].not() }
                                },
                            )
                            Text(dialog.entries[index], modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    owner.dismiss()
                    dialog.onConfirmed.onConfirmed(checked)
                }) { Text(dialog.positiveLabel) }
            },
            dismissButton = {
                dialog.negativeLabel?.let { label ->
                    TextButton(onClick = owner::dismiss) { Text(label) }
                }
            },
        )
    }

    @Composable
    private fun CheckboxMessageDialog(owner: MicroActivityDialogState, dialog: DialogState.CheckboxMessage) {
        var checked by remember(dialog) { mutableStateOf(dialog.initiallyChecked) }
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                Column {
                    Text(
                        text = dialog.message,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { checked = !checked },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text(dialog.checkboxLabel)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    owner.dismiss()
                    dialog.onConfirmed.onConfirmed(checked)
                }) { Text(dialog.positiveLabel) }
            },
            dismissButton = {
                dialog.negativeLabel?.let { label ->
                    TextButton(onClick = owner::dismiss) { Text(label) }
                }
            },
        )
    }

    @Composable
    private fun ChoiceActionsDialog(owner: MicroActivityDialogState, dialog: DialogState.ChoiceActions) {
        var selected by remember(dialog) { mutableIntStateOf(dialog.selectedIndex) }
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(dialog.entries.size) { index ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selected = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == index, onClick = { selected = index })
                            Text(dialog.entries[index], modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dialog.positiveLabel?.let { label ->
                        TextButton(onClick = {
                            owner.dismiss()
                            dialog.positiveAction?.onAction(selected)
                        }) { Text(label) }
                    }
                    dialog.neutralLabel?.let { label ->
                        TextButton(
                            enabled = dialog.neutralAction != null
                                && (!dialog.neutralRequiresSelection || selected >= 0),
                            onClick = {
                                owner.dismiss()
                                dialog.neutralAction?.onAction(selected)
                            },
                        ) { Text(label) }
                    }
                    dialog.negativeLabel?.let { label ->
                        TextButton(onClick = {
                            owner.dismiss()
                            dialog.negativeAction?.run()
                        }) { Text(label) }
                    }
                }
            },
        )
    }

    @Composable
    private fun TextInputActionsDialog(owner: MicroActivityDialogState, dialog: DialogState.TextInputActions) {
        var value by remember(dialog) { mutableStateOf(dialog.initialValue) }
        AlertDialog(
            onDismissRequest = {
                dialog.onCanceled?.run()
                owner.dismiss()
            },
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = if (dialog.numericOnly) it.filter(Char::isDigit) else it },
                    label = { Text(dialog.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().imePadding(),
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dialog.neutralLabel?.let { label ->
                        TextButton(onClick = {
                            owner.dismiss()
                            dialog.neutralAction?.run()
                        }) { Text(label) }
                    }
                    dialog.negativeLabel?.let { label ->
                        TextButton(onClick = owner::dismiss) { Text(label) }
                    }
                    TextButton(onClick = {
                        if (dialog.onConfirmed.onConfirmed(value)) owner.dismiss()
                    }) { Text(dialog.positiveLabel) }
                }
            },
        )
    }
}

fun interface MicroChoiceAction {
    fun onSelected(index: Int)
}

fun interface MicroChoiceButtonAction {
    fun onAction(index: Int)
}

fun interface MicroMultiChoiceAction {
    fun onConfirmed(checked: BooleanArray)
}

fun interface MicroCheckboxAction {
    fun onConfirmed(checked: Boolean)
}

fun interface MicroTextInputAction {
    fun onConfirmed(value: String): Boolean
}
