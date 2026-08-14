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

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import kotlin.math.roundToInt

/** Host bridge; ConfigActivity remains the owner of persistence and platform-sensitive flows. */
class ConfigComposeController @JvmOverloads constructor(
    composeView: ComposeView,
    initialState: ConfigUiState,
    private val events: ConfigFormEvents,
    private val menuActions: ConfigMenuActions? = null,
    private val title: String = "",
    private val isProfile: Boolean = false,
) {
    private var state by mutableStateOf(initialState)
    private var colorPicker by mutableStateOf<ColorPickerRequest?>(null)
    private var encodingPicker by mutableStateOf<EncodingPickerRequest?>(null)

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
                    title = title,
                    isProfile = isProfile,
                    menuActions = menuActions,
                    colorPicker = colorPicker,
                    encodingPicker = encodingPicker,
                    onColorPickerDismiss = { colorPicker = null },
                    onColorPicked = { field, value ->
                        colorPicker = null
                        events.onColorPicked(field, value)
                    },
                    onEncodingPickerDismiss = { encodingPicker = null },
                    onEncodingSelected = { charset ->
                        encodingPicker = null
                        events.onEncodingSelected(charset)
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

    fun showEncodingPicker(options: List<String>, selected: String?) {
        encodingPicker = EncodingPickerRequest(options, selected)
    }
}

data class ColorPickerRequest(
    val field: ConfigFormEvents.ColorField,
    val initialHex: String,
)

data class EncodingPickerRequest(
    val options: List<String>,
    val selected: String?,
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
    title: String = "",
    isProfile: Boolean = false,
    menuActions: ConfigMenuActions? = null,
    colorPicker: ColorPickerRequest? = null,
    encodingPicker: EncodingPickerRequest? = null,
    onColorPickerDismiss: () -> Unit = {},
    onColorPicked: (ConfigFormEvents.ColorField, String) -> Unit = { _, _ -> },
    onEncodingPickerDismiss: () -> Unit = {},
    onEncodingSelected: (String) -> Unit = {},
) {
    var form by remember(state.form) { mutableStateOf(state.form) }
    var lockAspect by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var clearDataVisible by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val updateForm: (ConfigFormState) -> Unit = { next ->
        form = next
        events.onFormChanged(next)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
        topBar = {
            ConfigTopBar(
                title = title,
                isProfile = isProfile,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                onBack = { menuActions?.onBack() },
                onStart = { menuExpanded = false; menuActions?.onStart() },
                onClearData = {
                    menuExpanded = false
                    menuActions?.onClearData()
                    clearDataVisible = true
                },
                onResetSettings = { menuExpanded = false; menuActions?.onResetSettings() },
                onResetLayout = { menuExpanded = false; menuActions?.onResetLayout() },
                onLoadProfile = { menuExpanded = false; menuActions?.onLoadProfile() },
                onSaveProfile = { menuExpanded = false; menuActions?.onSaveProfile() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // A landscape phone (typically 600–719dp) keeps one readable form column;
                // tablets and desktop-sized windows get the two-column layout.
                val wideLayout = maxWidth >= 840.dp
                val screen: @Composable () -> Unit = {
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
                }
                val font: @Composable () -> Unit = {
                    FontSection(form = form, state = state, onFormChanged = updateForm)
                }
                val input: @Composable () -> Unit = {
                    InputSection(form = form, state = state, onFormChanged = updateForm, events = events)
                }
                val emulation: @Composable () -> Unit = {
                    EmulationSection(form = form, onFormChanged = updateForm)
                }
                val audio: @Composable () -> Unit = {
                    AudioSection(form = form, state = state, onFormChanged = updateForm)
                }
                val system: @Composable () -> Unit = {
                    SystemSection(form = form, onFormChanged = updateForm, events = events)
                }
                if (wideLayout) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            screen()
                            input()
                            audio()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            font()
                            emulation()
                            system()
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        screen()
                        font()
                        input()
                        emulation()
                        audio()
                        system()
                    }
                }
            }
        }
    }

    colorPicker?.let { request ->
        ConfigColorPickerDialog(
            initialHex = request.initialHex,
            onDismissRequest = onColorPickerDismiss,
            onConfirm = { value -> onColorPicked(request.field, value) },
        )
    }

    encodingPicker?.let { request ->
        ConfigChoiceDialog(
            title = stringResource(R.string.pref_encoding_title),
            selected = request.selected.orEmpty(),
            options = request.options,
            onDismissRequest = onEncodingPickerDismiss,
            onSelected = { index ->
                request.options.getOrNull(index)?.let(onEncodingSelected)
            },
        )
    }

    if (clearDataVisible) {
        AlertDialog(
            onDismissRequest = { clearDataVisible = false },
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.message_clear_data)) },
            dismissButton = {
                TextButton(onClick = { clearDataVisible = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clearDataVisible = false
                    menuActions?.onConfirmClearData()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTopBar(
    title: String,
    isProfile: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onClearData: () -> Unit,
    onResetSettings: () -> Unit,
    onResetLayout: () -> Unit,
    onLoadProfile: () -> Unit,
    onSaveProfile: () -> Unit,
) {
    TopAppBar(
        windowInsets = NoWindowInsets,
        title = {
            Text(
                text = title.ifBlank { stringResource(R.string.action_settings) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    if (!isProfile) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.START_CMD)) },
                            onClick = onStart,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.CLEAR_DATA_CMD)) },
                            onClick = onClearData,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.RESET_SETTINGS_CMD)) },
                        onClick = onResetSettings,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.RESET_LAYOUT_CMD)) },
                        onClick = onResetLayout,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.load_profile)) },
                        onClick = onLoadProfile,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_profile)) },
                        onClick = onSaveProfile,
                    )
                }
            }
        },
    )
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
        var presetsDialogVisible by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { presetsDialogVisible = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = stringResource(R.string.SIZE_PRESETS),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (presetsDialogVisible) {
                val selectedPreset = form.screenWidth.toIntOrNull()?.let { width ->
                    form.screenHeight.toIntOrNull()?.let { height -> Size(width, height) }
                }
                ScreenPresetDialog(
                    presets = state.screenPresets,
                    removablePresets = state.removableScreenPresets,
                    selectedPreset = selectedPreset,
                    onDismissRequest = { presetsDialogVisible = false },
                    onSelected = { preset ->
                        presetsDialogVisible = false
                        onFormChanged(
                            form.toBuilder()
                                .screenWidth(preset.width.toString())
                                .screenHeight(preset.height.toString())
                                .build(),
                        )
                    },
                    onRemove = events::onRemoveResolutionPreset,
                )
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onPick = { events.onColorPicker(ConfigFormEvents.ColorField.SCREEN_BACKGROUND) },
        )
        ConfigRow(stringResource(R.string.pref_skin_title)) {
            val selected = state.skins.indexOfFirst { it == form.screenBackgroundImage }.coerceAtLeast(0)
            ChoiceField(
                selected = state.skins.getOrElse(selected) { "" },
                options = state.skins,
                dialogTitle = stringResource(R.string.pref_skin_title),
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
                dialogTitle = stringResource(R.string.PREF_SCALE_RATIO),
                showLabel = false,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().screenScaleRatio(normalizeScaleRatio(value)).build())
                },
            )
        }
        ConfigRow(stringResource(R.string.PREF_ORIENTATION)) {
            val options = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
            ChoiceField(
                selected = options.getOrElse(form.orientation) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.PREF_ORIENTATION),
                onSelected = { index -> onFormChanged(form.toBuilder().orientation(index).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_gravity)) {
            val options = stringArrayResource(R.array.pref_screen_gravity_entries).toList()
            ChoiceField(
                selected = options.getOrElse(form.screenGravity) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.pref_screen_gravity),
                onSelected = { index -> onFormChanged(form.toBuilder().screenGravity(index).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_padding_title)) {
            CompactTextField(
                value = form.screenPadding,
                label = "0",
                keyboardType = KeyboardType.Number,
                dialogTitle = stringResource(R.string.pref_screen_padding_title),
                showLabel = false,
                onValueChange = { value -> onFormChanged(form.toBuilder().screenPadding(value).build()) },
            )
        }
        ConfigRow(stringResource(R.string.pref_screen_scale_type)) {
            val options = stringArrayResource(R.array.pref_scale_type_entries).toList()
            ChoiceField(
                selected = options.getOrElse(form.screenScaleType) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.pref_screen_scale_type),
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
            ChoiceField(
                selected = options.getOrElse(form.graphicsMode) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.pref_graphics_mode_title),
                onSelected = { index -> onFormChanged(form.toBuilder().graphicsMode(index).build()) },
            )
        }
        if (form.graphicsMode == 1) {
            ConfigRow(stringResource(R.string.PREF_SHADER_FILTER)) {
                val selected = state.shaders.indexOfFirst { it == form.shader }.coerceAtLeast(0)
                ChoiceField(
                    selected = state.shaders.getOrElse(selected) { "" }.toString(),
                    options = state.shaders.map { it.toString() },
                    dialogTitle = stringResource(R.string.PREF_SHADER_FILTER),
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
                dialogTitle = stringResource(R.string.PREF_LIMIT_FPS),
                showLabel = false,
                onValueChange = { value -> onFormChanged(form.toBuilder().fpsLimit(value).build()) },
            )
        }
    }
}

@Composable
internal fun ScreenPresetDialog(
    presets: List<Size>,
    removablePresets: List<Size>,
    selectedPreset: Size?,
    onDismissRequest: () -> Unit,
    onSelected: (Size) -> Unit,
    onRemove: (Size) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.SIZE_PRESETS)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(presets) { _, preset ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(preset.toString()) },
                        leadingContent = {
                            RadioButton(
                                selected = preset == selectedPreset,
                                onClick = null,
                            )
                        },
                        trailingContent = if (removablePresets.contains(preset)) {
                            {
                                IconButton(onClick = { onRemove(preset) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_report),
                                        contentDescription = stringResource(R.string.remove_screen_preset),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { onSelected(preset) },
                            ),
                    )
                }
            }
        },
        confirmButton = {},
    )
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
            val selectedPreset = state.fontPresets.firstOrNull { preset ->
                preset.small.toString() == form.fontSizeSmall &&
                    preset.medium.toString() == form.fontSizeMedium &&
                    preset.large.toString() == form.fontSizeLarge
            }?.title ?: stringResource(R.string.SIZE_PRESETS)
            ChoiceField(
                selected = selectedPreset,
                options = state.fontPresets.map { it.title },
                dialogTitle = stringResource(R.string.SIZE_PRESETS),
                onSelected = { index ->
                    state.fontPresets.getOrNull(index)?.let { preset ->
                        onFormChanged(
                            form.toBuilder()
                                .fontSizeSmall(preset.small.toString())
                                .fontSizeMedium(preset.medium.toString())
                                .fontSizeLarge(preset.large.toString())
                                .build(),
                        )
                    }
                },
            )
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
            ChoiceField(
                selected = options.getOrElse(form.keyCodesLayout) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.PREF_LAYOUT),
                onSelected = { index -> onFormChanged(form.toBuilder().keyCodesLayout(index).build()) },
            )
        }
        OutlinedButton(
            onClick = events::onKeyMappings,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
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
                ChoiceField(
                    selected = options.getOrElse(form.vkButtonShape) { options.firstOrNull().orEmpty() },
                    options = options,
                    dialogTitle = stringResource(R.string.pref_button_shape_title),
                    onSelected = { index -> onFormChanged(form.toBuilder().vkButtonShape(index).build()) },
                )
            }
            SwitchRow(
                title = stringResource(R.string.PREF_VK_FEEDBACK),
                checked = form.vkFeedback,
                onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkFeedback(checked).build()) },
            )
            ConfigRow(stringResource(R.string.PREF_VK_ALPHA)) {
                SliderField(
                    value = form.vkAlpha,
                    valueRange = 0..255,
                    onSelected = { value -> onFormChanged(form.toBuilder().vkAlpha(value).build()) },
                )
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
                    dialogTitle = stringResource(R.string.PREF_VK_HIDE_DELAY),
                    showLabel = false,
                    valueSuffix = "ms",
                    onValueChange = { value -> onFormChanged(form.toBuilder().vkHideDelay(value).build()) },
                )
            }
            ColorRow(
                label = stringResource(R.string.PREF_VK_FORE),
                value = form.vkForeground,
                onPick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND) },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_BACK),
                value = form.vkBackground,
                onPick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND) },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_SEL_FORE),
                value = form.vkSelectedForeground,
                onPick = {
                    events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND)
                },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_SEL_BACK),
                value = form.vkSelectedBackground,
                onPick = {
                    events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND)
                },
            )
            ColorRow(
                label = stringResource(R.string.PREF_VK_OUTLINE),
                value = form.vkOutline,
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
            ChoiceField(
                selected = state.soundBanks.getOrElse(selected) { "" },
                options = state.soundBanks,
                dialogTitle = stringResource(R.string.pref_soundbank_title),
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
    var propertiesDialogVisible by rememberSaveable { mutableStateOf(false) }
    ConfigCard(title = stringResource(R.string.PREF_SYS_PROPS)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = events::onEncodingPicker,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.pref_encoding_title))
            }
            OutlinedButton(
                onClick = { propertiesDialogVisible = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.edit))
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClick = { propertiesDialogVisible = true },
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                val preview = form.systemProperties
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?: stringResource(R.string.config_system_properties_empty)
                Text(
                    text = preview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    color = if (form.systemProperties.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
    if (propertiesDialogVisible) {
        ConfigSystemPropertiesDialog(
            initialValue = form.systemProperties,
            onDismissRequest = { propertiesDialogVisible = false },
            onConfirm = { value ->
                propertiesDialogVisible = false
                onFormChanged(form.toBuilder().systemProperties(value).build())
            },
        )
    }
}

@Composable
internal fun ConfigSystemPropertiesDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    AlertDialog(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.PREF_SYS_PROPS)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp),
                placeholder = {
                    Text(
                        text = stringResource(R.string.PREF_SYS_PROPS_HINT),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun ConfigCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                .weight(0.38f)
                .widthIn(min = 72.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(modifier = Modifier.weight(0.62f)) {
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
    dialogTitle: String = label,
    showLabel: Boolean = true,
    valueSuffix: String? = null,
    onValueChange: (String) -> Unit,
) {
    var dialogVisible by remember(value) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                dialogVisible = true
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value.ifEmpty { label },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value.isNotEmpty() && valueSuffix != null) {
                    Text(
                        text = valueSuffix,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (dialogVisible) {
        ConfigNumberDialog(
            title = dialogTitle,
            initialValue = value,
            label = label.takeIf { showLabel },
            keyboardType = keyboardType,
            valueSuffix = valueSuffix,
            onDismissRequest = { dialogVisible = false },
            onConfirm = { nextValue ->
                dialogVisible = false
                onValueChange(nextValue)
            },
        )
    }
}

@Composable
internal fun ConfigNumberDialog(
    title: String,
    initialValue: String,
    label: String?,
    keyboardType: KeyboardType,
    valueSuffix: String? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = label?.let { value ->
                    { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                suffix = valueSuffix?.let { suffix -> { Text(suffix) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun SliderField(
    value: Int,
    valueRange: IntRange,
    onSelected: (Int) -> Unit,
) {
    var dialogVisible by remember(value) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                dialogVisible = true
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = value.toString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    if (dialogVisible) {
        ConfigSliderDialog(
            title = stringResource(R.string.PREF_VK_ALPHA),
            initialValue = value,
            valueRange = valueRange,
            onDismissRequest = { dialogVisible = false },
            onConfirm = { nextValue ->
                dialogVisible = false
                onSelected(nextValue)
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConfigSliderDialog(
    title: String,
    initialValue: Int,
    valueRange: IntRange,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftText by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val range = valueRange.first.toFloat()..valueRange.last.toFloat()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val draftValue = draftText.toIntOrNull()
                val sliderValue = (draftValue ?: initialValue).coerceIn(valueRange)
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { draftText = it.roundToInt().toString() },
                    valueRange = range,
                    steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            colors = SliderDefaults.colors(),
                            thumbTrackGapSize = 0.dp,
                            trackInsideCornerSize = 8.dp,
                        )
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(valueRange.first.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(valueRange.last.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draftText.toInt().coerceIn(valueRange)) },
                enabled = draftText.toIntOrNull() != null,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun ChoiceField(
    selected: String,
    options: List<String>,
    dialogTitle: String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogVisible by remember(selected, options) { mutableStateOf(false) }
    val enabled = options.isNotEmpty()
    val surfaceColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { dialogVisible = true },
        shape = MaterialTheme.shapes.medium,
        color = surfaceColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = selected,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    if (dialogVisible) {
        ConfigChoiceDialog(
            title = dialogTitle,
            selected = selected,
            options = options,
            onDismissRequest = { dialogVisible = false },
            onSelected = { index ->
                dialogVisible = false
                onSelected(index)
            },
        )
    }
}

@Composable
internal fun ConfigChoiceDialog(
    title: String,
    selected: String,
    options: List<String>,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(options) { index, option ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = option,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = option == selected,
                                onClick = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { onSelected(index) },
                            ),
                    )
                }
            }
        },
        confirmButton = {},
    )
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
    onPick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onPick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(parseColor(value)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value.ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun normalizeScaleRatio(value: String): String {
    val filtered = value.filter(Char::isDigit).take(4)
    return if (filtered.toIntOrNull()?.let { it > 1000 } == true) "1000" else filtered
}

private fun parseColor(value: String): Color {
    val parsed = value.toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or parsed).toInt())
}
