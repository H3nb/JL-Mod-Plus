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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

internal enum class ConfigDestination(val label: Int, val icon: Int) {
    Quick(R.string.config_destination_quick, R.drawable.ic_config_quick),
    Graphics(R.string.config_destination_graphics, R.drawable.ic_config_graphics),
    Audio(R.string.config_destination_audio, R.drawable.ic_config_audio),
    Media(R.string.config_destination_media, R.drawable.ic_config_media),
    Controls(R.string.config_destination_controls, R.drawable.ic_config_controls),
    System(R.string.config_destination_system, R.drawable.ic_config_system),
}

internal enum class ConfigAction(val title: Int, val message: Int) {
    ClearData(R.string.CLEAR_DATA_CMD, R.string.message_clear_data),
    ResetSettings(R.string.RESET_SETTINGS_CMD, R.string.message_reset_settings),
    ResetLayout(R.string.RESET_LAYOUT_CMD, R.string.message_reset_layout),
}

@Composable
internal fun ConfigScreen(
    state: ConfigUiState,
    events: ConfigFormEvents,
    modifier: Modifier = Modifier,
    title: String = "",
    isProfile: Boolean = false,
    initialDestination: ConfigDestination? = null,
    menuActions: ConfigMenuActions? = null,
    colorPicker: ColorPickerRequest? = null,
    encodingPicker: EncodingPickerRequest? = null,
    onColorPickerDismiss: () -> Unit = {},
    onColorPicked: (ConfigFormEvents.ColorField, String) -> Unit = { _, _ -> },
    onEncodingPickerDismiss: () -> Unit = {},
    onEncodingSelected: (String) -> Unit = {},
) {
    val form = state.form
    var lockAspect by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<ConfigAction?>(null) }
    val scrollState = rememberScrollState()
    val updateForm: (ConfigFormState) -> Unit = { next ->
        events.onFormChanged(next)
    }

    val destinations = if (isProfile) {
        ConfigDestination.values().filter { it != ConfigDestination.Quick }
    } else {
        ConfigDestination.values().toList()
    }
    var selectedDestinationIndex by rememberSaveable(isProfile, initialDestination) {
        mutableStateOf(
            initialDestination?.let { destinations.indexOf(it).takeIf { index -> index >= 0 } }
                ?: 0,
        )
    }
    val selectedDestination = destinations.getOrElse(selectedDestinationIndex) { destinations.first() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Row(modifier = modifier.fillMaxSize()) {
        if (isLandscape) {
            ConfigNavigationRail(
                destinations = destinations,
                selected = selectedDestination,
                onSelected = { selectedDestinationIndex = destinations.indexOf(it) },
            )
        }

        Scaffold(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                ConfigTopBar(
                    title = title,
                    isProfile = isProfile,
                    onBack = { menuActions?.onBack() },
                    onStart = { menuActions?.onStart() },
                )
            },
            bottomBar = {
                if (!isLandscape) {
                    ConfigNavigationBar(
                        destinations = destinations,
                        selected = selectedDestination,
                        onSelected = { selectedDestinationIndex = destinations.indexOf(it) },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ConfigDestinationContent(
                        destination = selectedDestination,
                        state = state,
                        form = form,
                        lockAspect = lockAspect,
                        onLockAspectChanged = { checked ->
                            val width = form.screenWidth.toIntOrNull()
                            val height = form.screenHeight.toIntOrNull()
                            lockAspect = checked && width != null && height != null && width > 0 && height > 0
                        },
                        onFormChanged = updateForm,
                        events = events,
                        isProfile = isProfile,
                        onRequestAction = { pendingAction = it },
                        modifier = Modifier.widthIn(max = 880.dp),
                    )
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

    pendingAction?.let { action ->
        ConfigActionConfirmationDialog(
            action = action,
            onDismissRequest = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                when (action) {
                    ConfigAction.ClearData -> menuActions?.onClearData()
                    ConfigAction.ResetSettings -> menuActions?.onResetSettings()
                    ConfigAction.ResetLayout -> menuActions?.onResetLayout()
                }
            },
        )
    }
}

@Composable
private fun ConfigDestinationContent(
    destination: ConfigDestination,
    state: ConfigUiState,
    form: ConfigFormState,
    lockAspect: Boolean,
    onLockAspectChanged: (Boolean) -> Unit,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    isProfile: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (destination) {
            ConfigDestination.Quick -> QuickDestination(
                state,
                form,
                lockAspect,
                onLockAspectChanged,
                onFormChanged,
                events,
                onRequestAction,
            )
            ConfigDestination.Graphics -> {
                ScreenSection(form, state, onFormChanged, events)
                FontSection(form, state, onFormChanged)
            }
            ConfigDestination.Audio -> AudioSection(form, state, onFormChanged)
            ConfigDestination.Media -> MediaDestination()
            ConfigDestination.Controls -> InputSection(form, state, onFormChanged, events, onRequestAction)
            ConfigDestination.System -> {
                EmulationSection(form, onFormChanged)
                SystemSection(form, onFormChanged, events, !isProfile, onRequestAction)
            }
        }
    }
}

@Composable
private fun QuickDestination(
    state: ConfigUiState,
    form: ConfigFormState,
    lockAspect: Boolean,
    onLockAspectChanged: (Boolean) -> Unit,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    onRequestAction: (ConfigAction) -> Unit,
) {
    ProfileStatusCard(state.profileStatus, events)
    ConfigCard(title = stringResource(R.string.config_quick_settings)) {
        var presetsDialogVisible by rememberSaveable { mutableStateOf(false) }
        ConfigRow(stringResource(R.string.config_screen_size)) {
            val size = "${form.screenWidth} × ${form.screenHeight}"
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { presetsDialogVisible = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(size, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(
                        painter = painterResource(R.drawable.ic_list),
                        contentDescription = stringResource(R.string.SIZE_PRESETS),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    keepAspectRatio = lockAspect,
                    onAdd = events::onAddResolutionPreset,
                    onRemove = events::onRemoveResolutionPreset,
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.PREF_KEEP_ASPECT_RATIO),
            checked = lockAspect,
            onCheckedChange = onLockAspectChanged,
        )
        ConfigRow(stringResource(R.string.PREF_ORIENTATION)) {
            val options = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
            ChoiceField(
                selected = options.getOrElse(form.orientation) { options.firstOrNull().orEmpty() },
                options = options,
                dialogTitle = stringResource(R.string.PREF_ORIENTATION),
                onSelected = { index -> onFormChanged(form.toBuilder().orientation(index).build()) },
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
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            checked = form.showKeyboard,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showKeyboard(checked).build()) },
        )
        SwitchRow(
            title = stringResource(R.string.PREF_TOUCH_INPUT),
            checked = form.touchInput,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().touchInput(checked).build()) },
        )
        OutlinedButton(
            onClick = { onRequestAction(ConfigAction.ResetSettings) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.RESET_SETTINGS_CMD))
        }
    }
}

@Composable
private fun ProfileStatusCard(status: ConfigUiState.ProfileStatus, events: ConfigFormEvents) {
    val active = status.activeProfile
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = active ?: stringResource(R.string.profile_custom),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (active == null) R.string.profile_custom_summary else R.string.profile_active_summary,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            OutlinedButton(onClick = events::onUseProfile, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (active == null) R.string.profile_use else R.string.profile_change,
                    ),
                )
            }
            if (active == null) {
                OutlinedButton(onClick = events::onSaveAsProfile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_save_as))
                }
            }
            Text(
                text = stringResource(
                    R.string.profile_default_summary,
                    status.defaultProfile ?: stringResource(R.string.profile_default_none),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun MediaDestination() {
    ConfigCard(title = stringResource(R.string.config_destination_media)) {
        Text(
            text = stringResource(R.string.config_media_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigNavigationBar(
    destinations: List<ConfigDestination>,
    selected: ConfigDestination,
    onSelected: (ConfigDestination) -> Unit,
) {
    NavigationBar {
        destinations.forEach { destination ->
            val label = stringResource(destination.label)
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = label,
                    )
                },
                label = {
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                },
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
private fun ConfigNavigationRail(
    destinations: List<ConfigDestination>,
    selected: ConfigDestination,
    onSelected: (ConfigDestination) -> Unit,
) {
    NavigationRail {
        destinations.forEach { destination ->
            val label = stringResource(destination.label)
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = label,
                    )
                },
                label = {
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                },
                alwaysShowLabel = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTopBar(
    title: String,
    isProfile: Boolean,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    TopAppBar(
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
            if (!isProfile) {
                IconButton(onClick = onStart) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        modifier = Modifier.size(30.dp),
                        contentDescription = stringResource(R.string.START_CMD),
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
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    ConfigCard(title = stringResource(R.string.PREF_SCREEN_OPTIONS)) {
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
        AdvancedSettingsRow(
            expanded = advancedExpanded,
            onExpandedChange = { advancedExpanded = it },
        )
        if (advancedExpanded) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenPresetDialog(
    presets: List<Size>,
    removablePresets: List<Size>,
    selectedPreset: Size?,
    onDismissRequest: () -> Unit,
    onSelected: (Size) -> Unit,
    keepAspectRatio: Boolean = false,
    onAdd: (Size) -> Unit,
    onRemove: (Size) -> Unit,
    useModalBottomSheet: Boolean = true,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    var pendingWidth by rememberSaveable(selectedPreset?.width, selectedPreset?.height) {
        mutableStateOf(selectedPreset?.width?.toString().orEmpty())
    }
    var pendingHeight by rememberSaveable(selectedPreset?.width, selectedPreset?.height) {
        mutableStateOf(selectedPreset?.height?.toString().orEmpty())
    }
    var customResolutionVisible by rememberSaveable { mutableStateOf(false) }
    val pendingSize = pendingWidth.toIntOrNull()?.let { width ->
        pendingHeight.toIntOrNull()?.let { height -> Size(width, height) }
    }
    val listedPresets = if (pendingSize != null && !presets.contains(pendingSize)) {
        listOf(pendingSize) + presets
    } else {
        presets
    }
    val temporaryPreset = pendingSize != null && !presets.contains(pendingSize)

    val sheetContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 560.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.config_select_screen_size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp),
                ) {
                    itemsIndexed(listedPresets) { index, preset ->
                        val isTemporary = temporaryPreset && index == 0
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(
                                    if (isTemporary) {
                                        stringResource(R.string.config_current_screen_size, preset.toString())
                                    } else {
                                        preset.toString()
                                    },
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = preset == pendingSize,
                                    onClick = null,
                                )
                            },
                            trailingContent = if (!isTemporary && removablePresets.contains(preset)) {
                                {
                                    IconButton(onClick = {
                                        if (preset == pendingSize) {
                                            pendingWidth = ""
                                            pendingHeight = ""
                                        }
                                        onRemove(preset)
                                    }) {
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
                                    onClick = {
                                        pendingWidth = preset.width.toString()
                                        pendingHeight = preset.height.toString()
                                    },
                                ),
                        )
                    }
                }
                ConfigLazyScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { customResolutionVisible = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.config_custom_resolution),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    enabled = pendingSize != null && pendingSize.width > 0 && pendingSize.height > 0,
                    onClick = {
                        pendingSize?.let {
                            onSelected(it)
                            onDismissRequest()
                        }
                    },
                ) {
                    Text(stringResource(R.string.config_select))
                }
            }
        }
    }

    if (useModalBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            content = { sheetContent() },
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                sheetContent()
            }
        }
    }

    if (customResolutionVisible) {
        CustomResolutionDialog(
            initialSize = pendingSize ?: selectedPreset,
            keepAspectRatio = keepAspectRatio,
            onDismissRequest = { customResolutionVisible = false },
            onSave = { size ->
                pendingWidth = size.width.toString()
                pendingHeight = size.height.toString()
                onAdd(size)
                customResolutionVisible = false
            },
        )
    }
}

@Composable
internal fun CustomResolutionDialog(
    initialSize: Size?,
    keepAspectRatio: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (Size) -> Unit,
) {
    val fallback = initialSize ?: Size(240, 320)
    var width by rememberSaveable(fallback.width, fallback.height) {
        mutableStateOf(fallback.width.toString())
    }
    var height by rememberSaveable(fallback.width, fallback.height) {
        mutableStateOf(fallback.height.toString())
    }
    val validWidth = width.toIntOrNull()?.takeIf { it > 0 }
    val validHeight = height.toIntOrNull()?.takeIf { it > 0 }
    val valid = validWidth != null && validHeight != null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_custom_resolution)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { value ->
                        width = value
                        if (keepAspectRatio) {
                            val newWidth = value.toIntOrNull()
                            val oldWidth = fallback.width
                            if (newWidth != null && oldWidth > 0) {
                                height = (newWidth * fallback.height.toFloat() / oldWidth).roundToInt().toString()
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.PREF_WIDTH)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val oldWidth = width
                    width = height
                    height = oldWidth
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_swap),
                        contentDescription = stringResource(R.string.SWAP_SIZES),
                    )
                }
                OutlinedTextField(
                    value = height,
                    onValueChange = { value ->
                        height = value
                        if (keepAspectRatio) {
                            val newHeight = value.toIntOrNull()
                            val oldHeight = fallback.height
                            if (newHeight != null && oldHeight > 0) {
                                width = (newHeight * fallback.width.toFloat() / oldHeight).roundToInt().toString()
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.PREF_HEIGHT)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(Size(validWidth!!, validHeight!!)) },
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@Composable
private fun ConfigLazyScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size
    val thumbFraction = if (totalItems == 0) 1f else {
        (visibleItems.toFloat() / totalItems).coerceIn(0.16f, 1f)
    }
    val offsetFraction = if (totalItems <= visibleItems) {
        0f
    } else {
        (layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0).toFloat() /
            (totalItems - visibleItems).toFloat()
    }.coerceIn(0f, 1f)
    ConfigScrollbar(thumbFraction, offsetFraction, modifier)
}

@Composable
private fun ConfigScrollbar(
    thumbFraction: Float,
    offsetFraction: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
    ) {
        val thumbOffset = maxHeight * (1f - thumbFraction) * offsetFraction
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(thumbFraction)
                .offset(y = thumbOffset)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun FontSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
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
        AdvancedSettingsRow(
            expanded = advancedExpanded,
            onExpandedChange = { advancedExpanded = it },
        )
        if (advancedExpanded) {
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
}

@Composable
private fun InputSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    onRequestAction: (ConfigAction) -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    ConfigCard(title = stringResource(R.string.pref_input_devices_title)) {
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
            AdvancedSettingsRow(
                expanded = advancedExpanded,
                onExpandedChange = { advancedExpanded = it },
            )
            if (advancedExpanded) {
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
                        valueSuffix = stringResource(R.string.PREF_UNIT_MS),
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
        OutlinedButton(
            onClick = { onRequestAction(ConfigAction.ResetLayout) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.RESET_LAYOUT_CMD))
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
    showClearData: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
) {
    val propertiesScrollState = rememberScrollState()
    ConfigCard(title = stringResource(R.string.PREF_SYS_PROPS)) {
        OutlinedButton(
            onClick = events::onEncodingPicker,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.pref_encoding_title))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 360.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.medium,
                ),
        ) {
            BasicTextField(
                value = form.systemProperties,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().systemProperties(value).build())
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(propertiesScrollState)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 22.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { innerTextField ->
                    if (form.systemProperties.isBlank()) {
                        Text(
                            text = stringResource(R.string.PREF_SYS_PROPS_HINT),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    innerTextField()
                },
            )
            ConfigScrollStateScrollbar(
                scrollState = propertiesScrollState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp),
            )
        }
        if (showClearData) {
            OutlinedButton(
                onClick = { onRequestAction(ConfigAction.ClearData) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.CLEAR_DATA_CMD))
            }
        }
    }
}

@Composable
private fun ConfigScrollStateScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.width(4.dp).fillMaxHeight()) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        val contentPx = viewportPx + scrollState.maxValue
        val thumbFraction = if (contentPx <= 0f) 1f else {
            (viewportPx / contentPx).coerceIn(0.16f, 1f)
        }
        val offsetFraction = if (scrollState.maxValue == 0) 0f else {
            (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
        }
        ConfigScrollbar(
            thumbFraction = thumbFraction,
            offsetFraction = offsetFraction,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ConfigActionConfirmationDialog(
    action: ConfigAction,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(action.title)) },
        text = { Text(stringResource(action.message)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun AdvancedSettingsRow(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onExpandedChange(!expanded) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.config_advanced_settings),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (expanded) "−" else "+",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
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
