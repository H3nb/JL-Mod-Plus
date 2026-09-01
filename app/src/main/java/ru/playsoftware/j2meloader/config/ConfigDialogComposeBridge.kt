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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.adaptiveDialogLayout
import ru.playsoftware.j2meloader.ui.rememberLazyListCanScrollForward
import java.text.DecimalFormat
import kotlin.math.roundToInt

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
        @Suppress("UNUSED_PARAMETER") defaultName: String?,
        callbacks: LoadProfileCallbacks,
    ) {
        view.setContent {
            JLModPlusTheme {
                LoadProfileContent(profiles, callbacks)
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
private fun DialogSurface(
    onDismissRequest: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (onDismissRequest != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(onDismissRequest) {
                        detectTapGestures { onDismissRequest() }
                    },
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            val compactWidth = maxWidth < 360.dp
            val compactHeight = maxHeight < 480.dp
            val dialogLayout = adaptiveDialogLayout(maxWidth, maxHeight)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = dialogLayout.modifier
                        // Keep taps inside the surface out of the dismiss layer behind it.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = if (compactWidth) 16.dp else 24.dp,
                            vertical = if (compactHeight) 12.dp else 20.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadProfileContent(
    profiles: List<Profile>,
    callbacks: ConfigDialogComposeBridge.LoadProfileCallbacks,
) {
    DialogSurface(onDismissRequest = callbacks::onDismiss) {
        Text(stringResource(R.string.profile_choose_template), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.profile_choose_template_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.no_data_for_display),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val canScrollForward = rememberLazyListCanScrollForward(listState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(profiles) { _, profile ->
                        val hasConfig = profile.hasConfig() || profile.hasOldConfig()
                        val hasKeyboard = profile.hasKeyLayout()
                        val available = hasConfig || hasKeyboard
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            headlineContent = { Text(profile.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = available) {
                                    if (available) {
                                        callbacks.onConfirm(profile.name, hasConfig, hasKeyboard)
                                    } else {
                                        callbacks.onError()
                                    }
                                },
                        )
                    }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = callbacks::onDismiss) {
                Text(stringResource(android.R.string.cancel))
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
    var touched by rememberSaveable { mutableStateOf(false) }
    var overwriteVisible by rememberSaveable { mutableStateOf(false) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty()

    DialogSurface(onDismissRequest = callbacks::onDismiss) {
        Text(stringResource(R.string.profile_save_template), style = MaterialTheme.typography.titleLarge)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.profile_save_template_summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = callbacks::onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = valid,
                onClick = {
                    if (trimmed in existingConfigNames) {
                        overwriteVisible = true
                    } else {
                        callbacks.onConfirm(trimmed, true, true, false)
                    }
                },
            ) {
                Text(stringResource(R.string.save))
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
                    callbacks.onConfirm(trimmed, true, true, false)
                }) {
                    Text(stringResource(R.string.save))
                }
            },
        )
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
    var draftValues by remember(shader) {
        mutableStateOf(List(4) { index ->
            settings.firstOrNull { it.index == index }?.let { setting ->
                format.format(initial[index].coerceIn(setting.min, setting.max))
            } ?: format.format(initial[index])
        })
    }
    fun normalizedValue(setting: ShaderSettingUi, raw: Float): Float {
        val progress = ((raw - setting.min) / setting.step).roundToInt()
        return (setting.min + progress * setting.step).coerceIn(setting.min, setting.max)
    }

    fun updateValue(index: Int, setting: ShaderSettingUi, raw: Float, syncDraft: Boolean) {
        val next = normalizedValue(setting, raw)
        values = values.copyOf().also { it[index] = next }
        if (syncDraft) {
            draftValues = draftValues.toMutableList().also { it[index] = format.format(next) }
        }
    }

    DialogSurface(onDismissRequest = null) {
        Text(stringResource(R.string.shader_tuning), style = MaterialTheme.typography.titleLarge)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val canScrollForward = rememberLazyListCanScrollForward(listState)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(settings) { _, setting ->
                    val value = values[setting.index].coerceIn(setting.min, setting.max)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val draft = draftValues.getOrNull(setting.index) ?: format.format(value)
                        Text(
                            text = stringResource(
                                R.string.shader_setting,
                                setting.name,
                                format.format(value),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ConfigValueStepper(
                            valueText = draft,
                            onValueTextChange = { text ->
                                draftValues = draftValues.toMutableList().also {
                                    it[setting.index] = text
                                }
                                text.toFloatOrNull()?.let { next ->
                                    updateValue(setting.index, setting, next, syncDraft = false)
                                }
                            },
                            onDecrease = {
                                updateValue(setting.index, setting, value - setting.step, syncDraft = true)
                            },
                            onIncrease = {
                                updateValue(setting.index, setting, value + setting.step, syncDraft = true)
                            },
                            decreaseEnabled = value > setting.min,
                            increaseEnabled = value < setting.max,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
            }
            ScrollableContentHint(
                visible = canScrollForward,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                values = FloatArray(4) { index ->
                    settings.firstOrNull { it.index == index }?.defaultValue ?: 0f
                }
                draftValues = List(4) { index ->
                    settings.firstOrNull { it.index == index }?.let { setting ->
                        format.format(setting.defaultValue.coerceIn(setting.min, setting.max))
                    } ?: format.format(0f)
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
