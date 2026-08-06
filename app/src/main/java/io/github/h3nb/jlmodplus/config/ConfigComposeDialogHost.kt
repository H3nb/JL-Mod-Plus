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

package io.github.h3nb.jlmodplus.config

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import kotlin.math.roundToInt

/**
 * State bridge for Java-owned Config callbacks. The Activity owns this state,
 * while the dialog surfaces remain part of the Activity's Compose tree.
 */
class ConfigDialogState {
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
            val onSelected: ComposeChoiceAction,
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
            val positiveAction: ComposeChoiceButtonAction?,
            val neutralAction: ComposeChoiceButtonAction?,
            val negativeAction: Runnable?,
            val onCanceled: Runnable?,
        ) : DialogState

        data class ShaderTuning(
            val title: String,
            val names: Array<String>,
            val minimums: FloatArray,
            val maximums: FloatArray,
            val steps: FloatArray,
            val defaults: FloatArray,
            val initialValues: FloatArray,
            val positiveLabel: String,
            val negativeLabel: String,
            val resetLabel: String,
            val cancelable: Boolean,
            val onConfirmed: ConfigShaderValuesAction,
        ) : DialogState

        data class SaveProfile(
            val title: String,
            val inputLabel: String,
            val initialName: String,
            val configLabel: String,
            val keyboardLabel: String,
            val defaultLabel: String,
            val positiveLabel: String,
            val negativeLabel: String,
            val cancelable: Boolean,
            val onConfirmed: ConfigSaveProfileAction,
        ) : DialogState

        data class LoadProfile(
            val title: String,
            val profileNames: Array<String>,
            val hasConfig: BooleanArray,
            val hasKeyboard: BooleanArray,
            val defaultIndex: Int,
            val configLabel: String,
            val keyboardLabel: String,
            val positiveLabel: String,
            val negativeLabel: String,
            val cancelable: Boolean,
            val onConfirmed: ConfigLoadProfileAction,
        ) : DialogState

        data class ColorPicker(
            val initialColor: Int,
            val positiveLabel: String,
            val negativeLabel: String,
            val onConfirmed: ConfigColorPickerAction,
        ) : DialogState
    }

    private var dialogState by mutableStateOf<DialogState?>(null)

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
    ) {
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
        onSelected: ComposeChoiceAction,
        onCanceled: Runnable? = null,
    ) {
        dialogState = DialogState.Choice(
            title,
            entries.copyOf(),
            selectedIndex,
            cancelLabel,
            cancelable,
            onSelected,
            onCanceled,
        )
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
        positiveAction: ComposeChoiceButtonAction?,
        neutralAction: ComposeChoiceButtonAction?,
        negativeAction: Runnable?,
        onCanceled: Runnable? = null,
    ) {
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
    }

    @Suppress("UNUSED_PARAMETER")
    fun showShaderTuning(
        context: Context,
        title: String,
        names: Array<String>,
        minimums: FloatArray,
        maximums: FloatArray,
        steps: FloatArray,
        defaults: FloatArray,
        initialValues: FloatArray,
        positiveLabel: String,
        negativeLabel: String,
        resetLabel: String,
        cancelable: Boolean,
        onConfirmed: ConfigShaderValuesAction,
    ) {
        dialogState = DialogState.ShaderTuning(
            title,
            names.copyOf(),
            minimums.copyOf(),
            maximums.copyOf(),
            steps.copyOf(),
            defaults.copyOf(),
            initialValues.copyOf(),
            positiveLabel,
            negativeLabel,
            resetLabel,
            cancelable,
            onConfirmed,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun showSaveProfile(
        context: Context,
        title: String,
        inputLabel: String,
        initialName: String,
        configLabel: String,
        keyboardLabel: String,
        defaultLabel: String,
        positiveLabel: String,
        negativeLabel: String,
        cancelable: Boolean,
        onConfirmed: ConfigSaveProfileAction,
    ) {
        dialogState = DialogState.SaveProfile(
            title,
            inputLabel,
            initialName,
            configLabel,
            keyboardLabel,
            defaultLabel,
            positiveLabel,
            negativeLabel,
            cancelable,
            onConfirmed,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun showLoadProfile(
        context: Context,
        title: String,
        profileNames: Array<String>,
        hasConfig: BooleanArray,
        hasKeyboard: BooleanArray,
        defaultIndex: Int,
        configLabel: String,
        keyboardLabel: String,
        positiveLabel: String,
        negativeLabel: String,
        cancelable: Boolean,
        onConfirmed: ConfigLoadProfileAction,
    ) {
        dialogState = DialogState.LoadProfile(
            title,
            profileNames.copyOf(),
            hasConfig.copyOf(),
            hasKeyboard.copyOf(),
            defaultIndex,
            configLabel,
            keyboardLabel,
            positiveLabel,
            negativeLabel,
            cancelable,
            onConfirmed,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun showColorPicker(
        context: Context,
        initialColor: Int,
        positiveLabel: String,
        negativeLabel: String,
        onConfirmed: ConfigColorPickerAction,
    ) {
        dialogState = DialogState.ColorPicker(initialColor, positiveLabel, negativeLabel, onConfirmed)
    }

    fun dismiss() {
        dialogState = null
    }

    @Composable
    internal fun Render() {
        AppComposeTheme {
            when (val current = dialogState) {
                null -> Unit
                is DialogState.Message -> MessageDialog(this, current)
                is DialogState.Choice -> ChoiceDialog(this, current)
                is DialogState.ChoiceActions -> ChoiceActionsDialog(this, current)
                is DialogState.ShaderTuning -> ShaderTuningDialog(this, current)
                is DialogState.SaveProfile -> SaveProfileDialog(this, current)
                is DialogState.LoadProfile -> LoadProfileDialog(this, current)
                is DialogState.ColorPicker -> ColorPickerDialog(this, current)
            }
        }
    }

    @Composable
    private fun MessageDialog(owner: ConfigDialogState, dialog: DialogState.Message) {
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
    private fun ChoiceDialog(owner: ConfigDialogState, dialog: DialogState.Choice) {
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
                    itemsIndexed(dialog.entries.toList()) { index, entry ->
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
                            Text(entry, modifier = Modifier.padding(start = 8.dp))
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
    private fun ChoiceActionsDialog(owner: ConfigDialogState, dialog: DialogState.ChoiceActions) {
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
                    itemsIndexed(dialog.entries.toList()) { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selected = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == index, onClick = { selected = index })
                            Text(entry, modifier = Modifier.padding(start = 8.dp))
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
                        TextButton(
                            enabled = dialog.positiveAction != null,
                            onClick = {
                                owner.dismiss()
                                dialog.positiveAction?.onAction(selected)
                            },
                        ) { Text(label) }
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
    private fun ShaderTuningDialog(owner: ConfigDialogState, dialog: DialogState.ShaderTuning) {
        val specs = remember(dialog.names, dialog.minimums, dialog.maximums, dialog.steps) {
            dialog.names.indices.mapNotNull { index ->
                val name = dialog.names.getOrNull(index).orEmpty()
                if (name.isBlank()) {
                    null
                } else {
                    val min = dialog.minimums.getOrNull(index) ?: 0f
                    val max = dialog.maximums.getOrNull(index) ?: min
                    val step = (dialog.steps.getOrNull(index) ?: 0f)
                        .takeIf { it > 0f } ?: ((max - min) / 100f)
                    ShaderSliderSpec(
                        index,
                        name,
                        min,
                        max,
                        step.coerceAtLeast(Float.MIN_VALUE),
                    )
                }
            }
        }
        var values by remember(dialog) { mutableStateOf(dialog.initialValues.copyOf()) }
        Dialog(
            onDismissRequest = owner::dismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(dialog.title, style = MaterialTheme.typography.headlineSmall)
                    specs.forEach { spec ->
                        val progress = ((values.getOrNull(spec.index) ?: spec.min) - spec.min)
                            .div(spec.step).roundToInt().coerceIn(0, spec.maxProgress)
                        Text("${spec.name}: ${spec.min + progress * spec.step}")
                        Slider(
                            value = progress.toFloat(),
                            onValueChange = { raw ->
                                values = values.copyOf().also { copy ->
                                    copy[spec.index] = spec.min + raw.roundToInt()
                                        .coerceIn(0, spec.maxProgress) * spec.step
                                }
                            },
                            valueRange = 0f..spec.maxProgress.toFloat(),
                            steps = (spec.maxProgress - 1).coerceAtLeast(0),
                        )
                    }
                    DialogActions(
                        positiveLabel = dialog.positiveLabel,
                        negativeLabel = dialog.negativeLabel,
                        onNegative = owner::dismiss,
                        onPositive = {
                            dialog.onConfirmed.onConfirmed(values.copyOf())
                            owner.dismiss()
                        },
                        extraAction = { values = dialog.defaults.copyOf() },
                        extraLabel = dialog.resetLabel,
                    )
                }
            }
        }
    }

    @Composable
    private fun SaveProfileDialog(owner: ConfigDialogState, dialog: DialogState.SaveProfile) {
        var name by remember(dialog) { mutableStateOf(dialog.initialName) }
        var configChecked by remember(dialog) { mutableStateOf(true) }
        var keyboardChecked by remember(dialog) { mutableStateOf(true) }
        var defaultChecked by remember(dialog) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = owner::dismiss,
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.filter { char -> char !in "/\\:*?\"<>|" } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(dialog.inputLabel) },
                    )
                    ConfigCheckboxRow(configChecked, dialog.configLabel) { configChecked = it }
                    ConfigCheckboxRow(keyboardChecked, dialog.keyboardLabel) { keyboardChecked = it }
                    ConfigCheckboxRow(defaultChecked, dialog.defaultLabel) { defaultChecked = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog.onConfirmed.onConfirmed(name, configChecked, keyboardChecked, defaultChecked)) {
                        owner.dismiss()
                    }
                }) { Text(dialog.positiveLabel) }
            },
            dismissButton = {
                TextButton(onClick = owner::dismiss) { Text(dialog.negativeLabel) }
            },
        )
    }

    @Composable
    private fun LoadProfileDialog(owner: ConfigDialogState, dialog: DialogState.LoadProfile) {
        var selectedIndex by remember(dialog) {
            mutableIntStateOf(dialog.defaultIndex.takeIf { it in dialog.profileNames.indices } ?: -1)
        }
        var configChecked by remember(dialog) { mutableStateOf(true) }
        var keyboardChecked by remember(dialog) { mutableStateOf(true) }
        var configEnabled by remember(dialog) { mutableStateOf(true) }
        var keyboardEnabled by remember(dialog) { mutableStateOf(true) }

        fun selectProfile(index: Int) {
            selectedIndex = index
            val config = dialog.hasConfig.getOrNull(index) == true
            val keyboard = dialog.hasKeyboard.getOrNull(index) == true
            configEnabled = config && keyboard
            keyboardEnabled = config && keyboard
            configChecked = config
            keyboardChecked = keyboard
        }

        AlertDialog(
            onDismissRequest = owner::dismiss,
            properties = DialogProperties(
                dismissOnBackPress = dialog.cancelable,
                dismissOnClickOutside = dialog.cancelable,
            ),
            title = { Text(dialog.title) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.heightIn(min = 48.dp, max = 320.dp)) {
                        itemsIndexed(dialog.profileNames.toList()) { index, name ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectProfile(index) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selectedIndex == index, onClick = { selectProfile(index) })
                                Text(name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                    ConfigCheckboxRow(configChecked, dialog.configLabel, configEnabled) { configChecked = it }
                    ConfigCheckboxRow(keyboardChecked, dialog.keyboardLabel, keyboardEnabled) { keyboardChecked = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog.onConfirmed.onConfirmed(selectedIndex, configChecked, keyboardChecked)
                    owner.dismiss()
                }) { Text(dialog.positiveLabel) }
            },
            dismissButton = {
                TextButton(onClick = owner::dismiss) { Text(dialog.negativeLabel) }
            },
        )
    }

    @Composable
    private fun ColorPickerDialog(owner: ConfigDialogState, dialog: DialogState.ColorPicker) {
        Dialog(
            onDismissRequest = owner::dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                ColorPickerDialogContent(
                    initialColor = dialog.initialColor,
                    positiveLabel = dialog.positiveLabel,
                    negativeLabel = dialog.negativeLabel,
                    onConfirmed = { color ->
                        dialog.onConfirmed.onConfirmed(color)
                        owner.dismiss()
                    },
                    onDismiss = owner::dismiss,
                )
            }
        }
    }
}

private data class ShaderSliderSpec(
    val index: Int,
    val name: String,
    val min: Float,
    val max: Float,
    val step: Float,
) {
    val maxProgress: Int = ((max - min) / step).toInt().coerceAtLeast(0)
}

@Composable
private fun ConfigCheckboxRow(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun DialogActions(
    positiveLabel: String,
    negativeLabel: String,
    onNegative: () -> Unit,
    onPositive: () -> Unit,
    extraAction: (() -> Unit)? = null,
    extraLabel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
    ) {
        if (extraAction != null && extraLabel != null) {
            TextButton(onClick = extraAction) { Text(extraLabel) }
        }
        TextButton(onClick = onNegative) { Text(negativeLabel) }
        TextButton(onClick = onPositive) { Text(positiveLabel) }
    }
}

fun interface ConfigShaderValuesAction {
    fun onConfirmed(values: FloatArray)
}

fun interface ConfigSaveProfileAction {
    fun onConfirmed(name: String, configChecked: Boolean, keyboardChecked: Boolean, defaultChecked: Boolean): Boolean
}

fun interface ConfigLoadProfileAction {
    fun onConfirmed(index: Int, configChecked: Boolean, keyboardChecked: Boolean)
}

fun interface ConfigColorPickerAction {
    fun onConfirmed(color: Int)
}

fun interface ComposeChoiceAction {
    fun onSelected(index: Int)
}

fun interface ComposeChoiceButtonAction {
    fun onAction(index: Int)
}
