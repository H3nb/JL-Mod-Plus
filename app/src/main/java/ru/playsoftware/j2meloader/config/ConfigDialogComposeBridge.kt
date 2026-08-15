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

package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.text.DecimalFormat
import kotlin.math.roundToInt

private val NoDialogInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

/** Java-facing callbacks keep persistence, toasts, and activity-result ownership in the host. */
object ConfigDialogComposeBridge {
    interface LoadProfileCallbacks {
        fun onDismiss()
        fun onError()
        fun onConfirm(name: String, config: Boolean, keyboard: Boolean)
    }

    interface SaveProfileCallbacks {
        fun onDismiss()
        fun onError()
        fun onConfirm(name: String, config: Boolean, keyboard: Boolean, asDefault: Boolean)
    }

    interface ShaderCallbacks {
        fun onDismiss()
        fun onConfirm(values: FloatArray)
    }

    @JvmStatic
    fun setLoadProfileContent(
        view: androidx.compose.ui.platform.ComposeView,
        profiles: List<Profile>,
        defaultName: String?,
        callbacks: LoadProfileCallbacks,
    ) {
        view.setContent {
            JLModPlusTheme {
                LoadProfileContent(profiles, defaultName, callbacks)
            }
        }
    }

    @JvmStatic
    fun setSaveProfileContent(
        view: androidx.compose.ui.platform.ComposeView,
        existingConfigNames: Set<String>,
        callbacks: SaveProfileCallbacks,
    ) {
        view.setContent {
            JLModPlusTheme {
                SaveProfileContent(existingConfigNames, callbacks)
            }
        }
    }

    @JvmStatic
    fun setShaderContent(
        view: androidx.compose.ui.platform.ComposeView,
        shader: ShaderInfo,
        callbacks: ShaderCallbacks,
    ) {
        view.setContent {
            JLModPlusTheme {
                ShaderContent(shader, callbacks)
            }
        }
    }
}

@Composable
private fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun LoadProfileContent(
    profiles: List<Profile>,
    defaultName: String?,
    callbacks: ConfigDialogComposeBridge.LoadProfileCallbacks,
) {
    var selectedIndex by rememberSaveable {
        mutableIntStateOf(profiles.indexOfFirst { it.name == defaultName }.takeIf { it >= 0 } ?: -1)
    }
    val initialProfile = profiles.getOrNull(selectedIndex)
    val initialHasConfig = initialProfile?.let { it.hasConfig() || it.hasOldConfig() } == true
    val initialHasKeyboard = initialProfile?.hasKeyLayout() == true
    var configChecked by rememberSaveable { mutableStateOf(initialHasConfig) }
    var keyboardChecked by rememberSaveable { mutableStateOf(initialHasKeyboard) }
    var configEnabled by rememberSaveable { mutableStateOf(initialHasConfig && initialHasKeyboard) }
    var keyboardEnabled by rememberSaveable { mutableStateOf(initialHasKeyboard && initialHasConfig) }

    DialogSurface {
        Text(stringResource(R.string.load_profile), style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.no_data_for_display),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(profiles) { index, profile ->
                    val hasConfig = profile.hasConfig() || profile.hasOldConfig()
                    val hasKeyboard = profile.hasKeyLayout()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(profile.name) },
                        leadingContent = {
                            RadioButton(selected = selectedIndex == index, onClick = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.RadioButton,
                                onClick = {
                                    selectedIndex = index
                                    // Preserve the legacy dependency: neither artifact can be
                                    // copied unless both artifacts exist in the source profile.
                                    configEnabled = hasConfig && hasKeyboard
                                    keyboardEnabled = hasKeyboard && hasConfig
                                    configChecked = hasConfig
                                    keyboardChecked = hasKeyboard
                                },
                            ),
                    )
                }
            }
        }
        CheckRow(
            title = stringResource(R.string.action_settings),
            checked = configChecked,
            enabled = configEnabled,
            onCheckedChange = { configChecked = it },
        )
        CheckRow(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            checked = keyboardChecked,
            enabled = keyboardEnabled,
            onCheckedChange = { keyboardChecked = it },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = callbacks::onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(onClick = {
                val profile = profiles.getOrNull(selectedIndex)
                if (profile == null) {
                    callbacks.onError()
                } else {
                    callbacks.onConfirm(profile.name, configChecked, keyboardChecked)
                }
            }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun SaveProfileContent(
    existingConfigNames: Set<String>,
    callbacks: ConfigDialogComposeBridge.SaveProfileCallbacks,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var configChecked by rememberSaveable { mutableStateOf(true) }
    var keyboardChecked by rememberSaveable { mutableStateOf(true) }
    var defaultChecked by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var overwriteVisible by rememberSaveable { mutableStateOf(false) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty()

    DialogSurface {
        Text(stringResource(R.string.save_profile), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = {
                touched = true
                name = it.filterNot { ch -> ch in "/\\:*?\"<>|" }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.enter_name)) },
            singleLine = true,
            isError = touched && !valid,
            supportingText = if (touched && !valid) {
                { Text(stringResource(R.string.error_name)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        CheckRow(
            title = stringResource(R.string.action_settings),
            checked = configChecked,
            enabled = true,
            onCheckedChange = { configChecked = it },
        )
        CheckRow(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            checked = keyboardChecked,
            enabled = true,
            onCheckedChange = { keyboardChecked = it },
        )
        CheckRow(
            title = stringResource(R.string.set_as_default),
            checked = defaultChecked,
            enabled = true,
            onCheckedChange = { defaultChecked = it },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = callbacks::onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = valid,
                onClick = {
                    if (trimmed in existingConfigNames) {
                        overwriteVisible = true
                    } else {
                        callbacks.onConfirm(trimmed, configChecked, keyboardChecked, defaultChecked)
                    }
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }

    if (overwriteVisible) {
        AlertDialog(
            onDismissRequest = { overwriteVisible = false },
            text = { Text(stringResource(R.string.alert_rewrite_profile, trimmed)) },
            dismissButton = {
                TextButton(onClick = { overwriteVisible = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    overwriteVisible = false
                    callbacks.onConfirm(trimmed, configChecked, keyboardChecked, defaultChecked)
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun CheckRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private data class ShaderSettingUi(
    val index: Int,
    val name: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val defaultValue: Float,
)

private fun shaderSettings(shader: ShaderInfo): List<ShaderSettingUi> {
    val settings = shader.settings ?: return emptyList()
    return settings.mapIndexedNotNull { index, setting ->
        setting ?: return@mapIndexedNotNull null
        val step = if (setting.step > 0f) setting.step else (setting.max - setting.min) / 100f
        setting.step = step.coerceAtLeast(0.000001f)
        ShaderSettingUi(
            index = index,
            name = setting.name.orEmpty(),
            min = setting.min,
            max = maxOf(setting.max, setting.min + setting.step),
            step = setting.step,
            defaultValue = setting.def,
        )
    }
}

@Composable
private fun ShaderContent(
    shader: ShaderInfo,
    callbacks: ConfigDialogComposeBridge.ShaderCallbacks,
) {
    val settings = remember(shader) { shaderSettings(shader) }
    val initial = remember(shader) {
        FloatArray(4) { index ->
            val setting = settings.firstOrNull { it.index == index }
            shader.values?.getOrNull(index) ?: setting?.defaultValue ?: 0f
        }
    }
    var values by remember(shader) { mutableStateOf(initial) }
    val format = remember { DecimalFormat("#.######") }

    DialogSurface {
        Text(stringResource(R.string.shader_tuning), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(settings) { _, setting ->
                val value = values[setting.index].coerceIn(setting.min, setting.max)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.shader_setting,
                            setting.name,
                            format.format(value),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = value,
                        onValueChange = { next ->
                            val copy = values.copyOf()
                            val progress = ((next - setting.min) / setting.step).roundToInt()
                            copy[setting.index] = (setting.min + progress * setting.step)
                                .coerceIn(setting.min, setting.max)
                            values = copy
                        },
                        valueRange = setting.min..setting.max,
                        steps = (((setting.max - setting.min) / setting.step).roundToInt() - 1)
                            .coerceAtLeast(0),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                values = FloatArray(4) { index ->
                    settings.firstOrNull { it.index == index }?.defaultValue ?: 0f
                }
            }) { Text(stringResource(R.string.reset)) }
            TextButton(onClick = callbacks::onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { callbacks.onConfirm(values.copyOf()) }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}
