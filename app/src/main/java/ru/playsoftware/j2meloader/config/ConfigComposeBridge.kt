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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import kotlin.math.roundToInt

/** Host bridge; ConfigActivity remains the owner of persistence and platform-sensitive flows. */
class ConfigComposeController(
    composeView: ComposeView,
    initialState: ConfigUiState,
    private val events: ConfigFormEvents,
) {
    private var state by mutableStateOf(initialState)
    private var colorPicker by mutableStateOf<ColorPickerRequest?>(null)

    init {
        composeView.id = R.id.config_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    state = state,
                    events = events,
                    colorPicker = colorPicker,
                    onColorPickerDismiss = { colorPicker = null },
                    onColorPicked = { field, value ->
                        colorPicker = null
                        events.onColorPicked(field, value)
                    },
                )
            }
        }
    }

    fun update(nextState: ConfigUiState) {
        state = nextState
    }

    fun showColorPicker(field: ConfigFormEvents.ColorField, initialHex: String) {
        colorPicker = ColorPickerRequest(field, initialHex)
    }
}

data class ColorPickerRequest(
    val field: ConfigFormEvents.ColorField,
    val initialHex: String,
)

/**
 * ConfigActivity's host inset listener already applies system-bar and IME padding to the
 * content frame. Compose therefore does not add a second inset pass here.
 */
private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@Composable
fun ConfigScreen(
    state: ConfigUiState,
    events: ConfigFormEvents,
    modifier: Modifier = Modifier,
    colorPicker: ColorPickerRequest? = null,
    onColorPickerDismiss: () -> Unit = {},
    onColorPicked: (ConfigFormEvents.ColorField, String) -> Unit = { _, _ -> },
) {
    var form by remember(state.form) { mutableStateOf(state.form) }
    var lockAspect by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val updateForm: (ConfigFormState) -> Unit = { next ->
        form = next
        events.onFormChanged(next)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenSection(
                form = form,
                state = state,
                lockAspect = lockAspect,
                onLockAspectChanged = { checked ->
                    val width = form.screenWidth.toIntOrNull()
                    val height = form.screenHeight.toIntOrNull()
                    lockAspect = checked && width != null && height != null && width > 0 && height > 0
                },
                onFormChanged = updateForm,
                events = events,
            )
            FontSection(form = form, state = state, onFormChanged = updateForm)
            InputSection(form = form, state = state, onFormChanged = updateForm, events = events)
            EmulationSection(form = form, onFormChanged = updateForm)
            AudioSection(form = form, state = state, onFormChanged = updateForm)
            SystemSection(form = form, onFormChanged = updateForm, events = events)
        }
    }

    colorPicker?.let { request ->
        ConfigColorPickerDialog(
            initialHex = request.initialHex,
            onDismissRequest = onColorPickerDismiss,
            onConfirm = { value -> onColorPicked(request.field, value) },
        )
    }
}

@Composable
private fun ScreenSection(
    form: ConfigFormState,
    state: ConfigUiState,
    lockAspect: Boolean,
    onLockAspectChanged: (Boolean) -> Unit,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
) {
    ConfigCard(title = stringResource(R.string.PREF_SCREEN_OPTIONS)) {
        var presetsExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                IconButton(onClick = { presetsExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_list),
                        contentDescription = stringResource(R.string.SIZE_PRESETS),
                    )
                }
                DropdownMenu(
                    expanded = presetsExpanded,
                    onDismissRequest = { presetsExpanded = false },
                ) {
                    state.screenPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.toString()) },
                            onClick = {
                                presetsExpanded = false
                                onFormChanged(
                                    form.toBuilder()
                                        .screenWidth(preset.width.toString())
                                        .screenHeight(preset.height.toString())
                                        .build(),
                                )
                            },
                        )
                    }
                }
            }
            CompactTextField(
                value = form.screenWidth,
                label = stringResource(R.string.PREF_WIDTH),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                onValueChange = { value ->
                    val builder = form.toBuilder().screenWidth(value)
                    if (lockAspect) {
                        val oldWidth = form.screenWidth.toIntOrNull()
                        val height = form.screenHeight.toIntOrNull()
                        val newWidth = value.toIntOrNull()
                        if (oldWidth != null && oldWidth > 0 && height != null && height > 0 &&
                            newWidth != null && newWidth > 0
                        ) {
                            builder.screenHeight((newWidth * height.toFloat() / oldWidth).roundToInt().toString())
                        }
                    }
                    onFormChanged(builder.build())
                },
            )
            IconButton(onClick = {
                onFormChanged(
                    form.toBuilder()
                        .screenWidth(form.screenHeight)
                        .screenHeight(form.screenWidth)
                        .build(),
                )
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap),
                    contentDescription = stringResource(R.string.SWAP_SIZES),
                )
            }
            CompactTextField(
                value = form.screenHeight,
                label = stringResource(R.string.PREF_HEIGHT),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                onValueChange = { value ->
                    val builder = form.toBuilder().screenHeight(value)
                    if (lockAspect) {
                        val oldHeight = form.screenHeight.toIntOrNull()
                        val width = form.screenWidth.toIntOrNull()
                        val newHeight = value.toIntOrNull()
                        if (oldHeight != null && oldHeight > 0 && width != null && width > 0 &&
                            newHeight != null && newHeight > 0
                        ) {
                            builder.screenWidth((newHeight * width.toFloat() / oldHeight).roundToInt().toString())
                        }
                    }
                    onFormChanged(builder.build())
                },
            )
            IconButton(onClick = events::onAddResolutionPreset) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_preset),
                    contentDescription = stringResource(R.string.add_resolution_preset),
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.PREF_KEEP_ASPECT_RATIO),
            checked = lockAspect,
            onCheckedChange = onLockAspectChanged,
        )
        ColorRow(
            label = stringResource(R.string.PREF_BACKGROUND),
            value = form.screenBackground,
            onValueChange = { value ->
                onFormChanged(form.toBuilder().screenBackground(normalizeHex(value)).build())
            },
            onPick = { events.onColorPicker(ConfigFormEvents.ColorField.SCREEN_BACKGROUND) },
        )
        ConfigRow(stringResource(R.string.pref_skin_title)) {
            val selected = state.skins.indexOfFirst { it == form.screenBackgroundImage }.coerceAtLeast(0)
            DropdownField(
                selected = state.skins.getOrElse(selected) { "" },
                options = state.skins,
                onSelected = { index ->
                    onFormChanged(
                        form.toBuilder()
                            .screenBackgroundImage(if (index == 0) null else state.skins.getOrNull(index))
                            .build(),
                    )
                },
            )
        }
        ConfigRow(stringResource(R.string.PREF_SCALE_RATIO)) {
            CompactTextField(
                value = form.screenScaleRatio,
                label = "100",
                keyboardType = KeyboardType.Number,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().screenScaleRatio(normalizeScaleRatio(value)).build())
                },
            )
        }
        ConfigRow(stringResource(R.string.PREF_ORIENTATION)) {
            val options = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
            DropdownField(
                selected = options.getOrElse(form.orientation) { options.firstOrNull().orEmpty() },
                options = options,
                onSelected = { index -> onFormChanged(form.toBuilder().orientation(index).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_gravity)) {
            val options = stringArrayResource(R.array.pref_screen_gravity_entries).toList()
            DropdownField(
                selected = options.getOrElse(form.screenGravity) { options.firstOrNull().orEmpty() },
                options = options,
                onSelected = { index -> onFormChanged(form.toBuilder().screenGravity(index).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_padding_title)) {
            CompactTextField(
                value = form.screenPadding,
                label = "0",
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChanged(form.toBuilder().screenPadding(value).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_scale_type)) {
            val options = stringArrayResource(R.array.pref_scale_type_entries).toList()
            DropdownField(
                selected = options.getOrElse(form.screenScaleType) { options.firstOrNull().orEmpty() },
                options = options,
                onSelected = { index -> onFormChanged(form.toBuilder().screenScaleType(index).build()) },
            )
        }
        SwitchRow(
            title = stringResource(R.string.PREF_FILTER),
            checked = form.screenFilter,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().screenFilter(checked).build()) },
        )
        SwitchRow(
            title = stringResource(R.string.PREF_IMMEDIATE),
            checked = form.immediateMode,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().immediateMode(checked).build()) },
        )
        ConfigRow(stringResource(R.string.pref_graphics_mode_title)) {
            val options = stringArrayResource(R.array.pref_graphics_mode_entries).toList()
            DropdownField(
                selected = options.getOrElse(form.graphicsMode) { options.firstOrNull().orEmpty() },
                options = options,
                onSelected = { index -> onFormChanged(form.toBuilder().graphicsMode(index).build()) },
            )
        }
        if (form.graphicsMode == 1) {
            ConfigRow(stringResource(R.string.PREF_SHADER_FILTER)) {
                val selected = state.shaders.indexOfFirst { it == form.shader }.coerceAtLeast(0)
                DropdownField(
                    selected = state.shaders.getOrElse(selected) { "" }.toString(),
                    options = state.shaders.map { it.toString() },
                    onSelected = { index ->
                        onFormChanged(
                            form.toBuilder().shader(if (index == 0) null else state.shaders.getOrNull(index)).build(),
                        )
                    },
                )
            }
            val selectedShader = state.shaders.getOrNull(
                state.shaders.indexOfFirst { it == form.shader }.coerceAtLeast(0),
            )
            if (selectedShader?.hasTunableSettings() == true) {
                TextButton(onClick = events::onShaderTuning) {
                    Text(stringResource(R.string.shader_tuning))
                }
            }
        }
        if (form.graphicsMode == 0 || form.graphicsMode == 3) {
            SwitchRow(
                title = stringResource(R.string.parallel_screen_redrawing),
                checked = form.parallelRedrawScreen,
                onCheckedChange = { checked ->
                    onFormChanged(form.toBuilder().parallelRedrawScreen(checked).build())
                },
            )
        }
        SwitchRow(
            title = stringResource(R.string.PREF_FORCE_FULLSCREEN),
            checked = form.forceFullscreen,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().forceFullscreen(checked).build()) },
        )
        SwitchRow(
            title = stringResource(R.string.PREF_SHOW_FPS),
            checked = form.showFps,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showFps(checked).build()) },
        )
        ConfigRow(stringResource(R.string.PREF_LIMIT_FPS)) {
            CompactTextField(
                value = form.fpsLimit,
                label = stringResource(R.string.unlimited),
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChanged(form.toBuilder().fpsLimit(value).build()) },
            )
        }
    }
}

@Composable
private fun FontSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigCard(title = stringResource(R.string.PREF_FONT_OPTIONS)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            CompactTextField(
                value = form.fontSizeSmall,
                label = stringResource(R.string.PREF_FONT_SMALL),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onFormChanged(form.toBuilder().fontSizeSmall(value).build()) },
            )
            CompactTextField(
                value = form.fontSizeMedium,
                label = stringResource(R.string.PREF_FONT_MEDIUM),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onFormChanged(form.toBuilder().fontSizeMedium(value).build()) },
            )
            CompactTextField(
                value = form.fontSizeLarge,
                label = stringResource(R.string.PREF_FONT_LARGE),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onFormChanged(form.toBuilder().fontSizeLarge(value).build()) },
            )
        }
        ConfigRow(stringResource(R.string.SIZE_PRESETS)) {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = state.fontPresets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.SIZE_PRESETS))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    state.fontPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.title) },
                            onClick = {
                                expanded = false
                                onFormChanged(
                                    form.toBuilder()
                                        .fontSizeSmall(preset.small.toString())
                                        .fontSizeMedium(preset.medium.toString())
                                        .fontSizeLarge(preset.large.toString())
                                        .build(),
                                )
                            },
                        )
                    }
                }
            }
        }
        SwitchRow(
            title = stringResource(R.string.PREF_FONT_SIZE_IN_SP),
            checked = form.fontApplyDimensions,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().fontApplyDimensions(checked).build())
            },
        )
        SwitchRow(
            title = stringResource(R.string.PREF_FONT_ANTI_ALIASING),
            checked = form.fontAA,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().fontAA(checked).build()) },
        )
    }
}

@Composable
private fun InputSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
) {
    ConfigCard(title = stringResource(R.string.pref_input_devices_title)) {
        SwitchRow(
            title = stringResource(R.string.PREF_TOUCH_INPUT),
            checked = form.touchInput,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().touchInput(checked).build()) },
        )
        ConfigRow(stringResource(R.string.PREF_LAYOUT)) {
            val options = stringArrayResource(R.array.PREF_LAYOUT_ENTRIES).toList()
            DropdownField(
                selected = options.getOrElse(form.keyCodesLayout) { options.firstOrNull().orEmpty() },
                options = options,
                onSelected = { index -> onFormChanged(form.toBuilder().keyCodesLayout(index).build()) },
            )
        }
        OutlinedButton(
            onClick = events::onKeyMappings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pref_map_keys))
        }
        SwitchRow(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            checked = form.showKeyboard,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showKeyboard(checked).build()) },
        )
        if (form.showKeyboard) {
            ConfigRow(stringResource(R.string.pref_button_shape_title)) {
                val options = stringArrayResource(R.array.pref_button_shape_entries).toList()
                DropdownField(
                    selected = options.getOrElse(form.vkButtonShape) { options.firstOrNull().orEmpty() },
                    options = options,
                    onSelected = { index -> onFormChanged(form.toBuilder().vkButtonShape(index).build()) },
                )
            }
            SwitchRow(
                title = stringResource(R.string.PREF_VK_FEEDBACK),
                checked = form.vkFeedback,
                onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkFeedback(checked).build()) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.PREF_VK_ALPHA),
                    modifier = Modifier.widthIn(min = 96.dp),
                )
                Slider(
                    value = form.vkAlpha.toFloat().coerceIn(0f, 255f),
                    onValueChange = { value -> onFormChanged(form.toBuilder().vkAlpha(value.roundToInt()).build()) },
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f),
                )
                Text(form.vkAlpha.toString())
            }
            SwitchRow(
                title = stringResource(R.string.PREF_VK_FORCE_OPACITY),
                checked = form.vkForceOpacity,
                onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkForceOpacity(checked).build()) },
            )
            ConfigRow(stringResource(R.string.PREF_VK_HIDE_DELAY)) {
                CompactTextField(
                    value = form.vkHideDelay,
                    label = stringResource(R.string.pref_vk_hide_hint),
                    keyboardType = KeyboardType.Number,
                    onValueChange = { value -> onFormChanged(form.toBuilder().vkHideDelay(value).build()) },
                )
            }
            ColorRow(
                label = stringResource(R.string.PREF_VK_FORE),
                value = form.vkForeground,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().vkForeground(normalizeHex(value)).build())
                },
                onPick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND) },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_BACK),
                value = form.vkBackground,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().vkBackground(normalizeHex(value)).build())
                },
                onPick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND) },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_SEL_FORE),
                value = form.vkSelectedForeground,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().vkSelectedForeground(normalizeHex(value)).build())
                },
                onPick = {
                    events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND)
                },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_SEL_BACK),
                value = form.vkSelectedBackground,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().vkSelectedBackground(normalizeHex(value)).build())
                },
                onPick = {
                    events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND)
                },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_OUTLINE),
                value = form.vkOutline,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().vkOutline(normalizeHex(value)).build())
                },
                onPick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_OUTLINE) },
            )
        }
    }
}

@Composable
private fun EmulationSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigCard(title = stringResource(R.string.pref_title_emulation)) {
        SwitchRow(
            title = stringResource(R.string.pref_skip_resume_call),
            checked = form.skipResumeCall,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().skipResumeCall(checked).build()) },
        )
    }
}

@Composable
private fun AudioSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigCard(title = stringResource(R.string.pref_audio_title)) {
        ConfigRow(stringResource(R.string.pref_soundbank_title)) {
            val selected = state.soundBanks.indexOfFirst { option ->
                form.soundBank != null && option == form.soundBank
            }.let { if (it < 0) 0 else it }
            DropdownField(
                selected = state.soundBanks.getOrElse(selected) { "" },
                options = state.soundBanks,
                onSelected = { index ->
                    onFormChanged(
                        form.toBuilder()
                            .soundBank(if (index == 0) null else state.soundBanks.getOrNull(index))
                            .build(),
                    )
                },
            )
        }
    }
}

@Composable
private fun SystemSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
) {
    ConfigCard(title = stringResource(R.string.PREF_SYS_PROPS)) {
        OutlinedButton(onClick = events::onEncodingPicker) {
            Text(stringResource(R.string.pref_encoding_title))
        }
        OutlinedTextField(
            value = form.systemProperties,
            onValueChange = { value -> onFormChanged(form.toBuilder().systemProperties(value).build()) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.PREF_SYS_PROPS_HINT)) },
            minLines = 6,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
    }
}

@Composable
private fun ConfigCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                content()
            },
        )
    }
}

@Composable
private fun ConfigRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(0.42f)
                .widthIn(min = 80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(0.58f)) {
            content()
        }
    }
}

@Composable
private fun CompactTextField(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun DropdownField(
    selected: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(selected, options) { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selected,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColorRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.weight(0.42f),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(normalizeHex(it)) },
            modifier = Modifier.weight(0.58f),
            label = { Text(stringResource(R.string.PREF_COLOR_HINT)) },
            singleLine = true,
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(parseColor(value)),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

private fun normalizeHex(value: String): String = value
    .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    .uppercase()
    .take(6)

private fun normalizeScaleRatio(value: String): String {
    val filtered = value.filter(Char::isDigit).take(4)
    return if (filtered.toIntOrNull()?.let { it > 1000 } == true) "1000" else filtered
}

private fun parseColor(value: String): Color {
    val parsed = value.toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or parsed).toInt())
}
