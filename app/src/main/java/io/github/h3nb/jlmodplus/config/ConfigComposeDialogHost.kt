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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import kotlin.math.roundToInt

/** Compose dialog bridges for config flows that are still entered from Java. */
object ConfigComposeDialogHost {
    @JvmStatic
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
    ): Dialog = createDialog(context, cancelable) { dialog ->
        ShaderTuningDialogContent(
            dialog = dialog,
            title = title,
            names = names,
            minimums = minimums,
            maximums = maximums,
            steps = steps,
            defaults = defaults,
            initialValues = initialValues,
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            resetLabel = resetLabel,
            onConfirmed = onConfirmed,
        )
    }

    @JvmStatic
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
    ): Dialog = createDialog(context, cancelable) { dialog ->
        SaveProfileDialogContent(
            dialog = dialog,
            title = title,
            inputLabel = inputLabel,
            initialName = initialName,
            configLabel = configLabel,
            keyboardLabel = keyboardLabel,
            defaultLabel = defaultLabel,
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            onConfirmed = onConfirmed,
        )
    }

    @JvmStatic
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
    ): Dialog = createDialog(context, cancelable) { dialog ->
        LoadProfileDialogContent(
            dialog = dialog,
            title = title,
            profileNames = profileNames,
            hasConfig = hasConfig,
            hasKeyboard = hasKeyboard,
            defaultIndex = defaultIndex,
            configLabel = configLabel,
            keyboardLabel = keyboardLabel,
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            onConfirmed = onConfirmed,
        )
    }

    @JvmStatic
    fun showColorPicker(
        context: Context,
        initialColor: Int,
        positiveLabel: String,
        negativeLabel: String,
        onConfirmed: ConfigColorPickerAction,
    ): Dialog = createDialog(context, cancelable = true) { dialog ->
        ColorPickerDialogContent(
            initialColor = initialColor,
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            onConfirmed = { color ->
                onConfirmed.onConfirmed(color)
                dialog.dismiss()
            },
            onDismiss = dialog::cancel,
        )
    }

    private fun createDialog(
        context: Context,
        cancelable: Boolean,
        content: @Composable (Dialog) -> Unit,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        content(dialog)
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
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

private data class ShaderSliderSpec(
    val index: Int,
    val name: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val maxProgress: Int,
)

private fun shaderSpecs(
    names: Array<String>,
    minimums: FloatArray,
    maximums: FloatArray,
    steps: FloatArray,
): List<ShaderSliderSpec> = names.indices.mapNotNull { index ->
    val name = names.getOrNull(index).orEmpty()
    if (name.isBlank()) {
        null
    } else {
        val min = minimums.getOrNull(index) ?: 0f
        val max = maximums.getOrNull(index) ?: min
        val step = steps.getOrNull(index)?.takeIf { it > 0f } ?: ((max - min) / 100f)
        ShaderSliderSpec(
            index = index,
            name = name,
            min = min,
            max = max,
            step = step.coerceAtLeast(Float.MIN_VALUE),
            maxProgress = ((max - min) / step.coerceAtLeast(Float.MIN_VALUE)).toInt().coerceAtLeast(0),
        )
    }
}

@Composable
private fun ShaderTuningDialogContent(
    dialog: Dialog,
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
    onConfirmed: ConfigShaderValuesAction,
) {
    val specs = remember(names, minimums, maximums, steps) {
        shaderSpecs(names, minimums, maximums, steps)
    }
    var values by remember { mutableStateOf(initialValues.copyOf()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        specs.forEach { spec ->
            val progress = ((values.getOrNull(spec.index) ?: spec.min) - spec.min)
                .div(spec.step)
                .roundToInt()
                .coerceIn(0, spec.maxProgress)
            Text(
                text = "${spec.name}: ${spec.min + progress * spec.step}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = progress.toFloat(),
                onValueChange = { rawProgress ->
                    val copy = values.copyOf()
                    copy[spec.index] = spec.min + rawProgress.roundToInt()
                        .coerceIn(0, spec.maxProgress) * spec.step
                    values = copy
                },
                valueRange = 0f..spec.maxProgress.toFloat(),
                steps = (spec.maxProgress - 1).coerceAtLeast(0),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                values = defaults.copyOf()
            }) {
                Text(resetLabel)
            }
            TextButton(onClick = { dialog.dismiss() }) {
                Text(negativeLabel)
            }
            TextButton(onClick = {
                onConfirmed.onConfirmed(values.copyOf())
                dialog.dismiss()
            }) {
                Text(positiveLabel)
            }
        }
    }
}

@Composable
private fun SaveProfileDialogContent(
    dialog: Dialog,
    title: String,
    inputLabel: String,
    initialName: String,
    configLabel: String,
    keyboardLabel: String,
    defaultLabel: String,
    positiveLabel: String,
    negativeLabel: String,
    onConfirmed: ConfigSaveProfileAction,
) {
    var name by remember { mutableStateOf(initialName) }
    var configChecked by remember { mutableStateOf(true) }
    var keyboardChecked by remember { mutableStateOf(true) }
    var defaultChecked by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.filter { char -> char !in "/\\:*?\"<>|" } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(inputLabel) },
        )
        ConfigCheckboxRow(configChecked, configLabel) { configChecked = it }
        ConfigCheckboxRow(keyboardChecked, keyboardLabel) { keyboardChecked = it }
        ConfigCheckboxRow(defaultChecked, defaultLabel) { defaultChecked = it }
        DialogActions(
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            onNegative = { dialog.dismiss() },
            onPositive = {
                if (onConfirmed.onConfirmed(name, configChecked, keyboardChecked, defaultChecked)) {
                    dialog.dismiss()
                }
            },
        )
    }
}

@Composable
private fun LoadProfileDialogContent(
    dialog: Dialog,
    title: String,
    profileNames: Array<String>,
    hasConfig: BooleanArray,
    hasKeyboard: BooleanArray,
    defaultIndex: Int,
    configLabel: String,
    keyboardLabel: String,
    positiveLabel: String,
    negativeLabel: String,
    onConfirmed: ConfigLoadProfileAction,
) {
    var selectedIndex by remember { mutableStateOf(defaultIndex.takeIf { it in profileNames.indices } ?: -1) }
    var configChecked by remember { mutableStateOf(true) }
    var keyboardChecked by remember { mutableStateOf(true) }
    var configEnabled by remember { mutableStateOf(true) }
    var keyboardEnabled by remember { mutableStateOf(true) }

    fun selectProfile(index: Int) {
        selectedIndex = index
        val config = hasConfig.getOrNull(index) == true
        val keyboard = hasKeyboard.getOrNull(index) == true
        configEnabled = config && keyboard
        keyboardEnabled = config && keyboard
        configChecked = config
        keyboardChecked = keyboard
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.heightIn(min = 48.dp, max = 320.dp)) {
            items(profileNames.size) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectProfile(index) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedIndex == index,
                        onClick = { selectProfile(index) },
                    )
                    Text(profileNames[index], modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        HorizontalDivider()
        ConfigCheckboxRow(configChecked, configLabel, configEnabled) { configChecked = it }
        ConfigCheckboxRow(keyboardChecked, keyboardLabel, keyboardEnabled) { keyboardChecked = it }
        DialogActions(
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            onNegative = { dialog.dismiss() },
            onPositive = {
                onConfirmed.onConfirmed(selectedIndex, configChecked, keyboardChecked)
                dialog.dismiss()
            },
        )
    }
}

@Composable
private fun ConfigCheckboxRow(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun DialogActions(
    positiveLabel: String,
    negativeLabel: String,
    onNegative: () -> Unit,
    onPositive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onNegative) { Text(negativeLabel) }
        TextButton(onClick = onPositive) { Text(positiveLabel) }
    }
}
