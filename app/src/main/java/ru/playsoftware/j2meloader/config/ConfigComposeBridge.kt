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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import ru.playsoftware.j2meloader.ui.adaptiveDialogLayout
import ru.playsoftware.j2meloader.ui.rememberScrollCanScrollForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import javax.microedition.shell.timing.TimingMode
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.availableWindowWidthDp
import ru.playsoftware.j2meloader.ui.jlModPlusNavigationBarItemColors
import ru.playsoftware.j2meloader.ui.jlModPlusNavigationRailItemColors
import ru.playsoftware.j2meloader.ui.rememberLazyListCanScrollForward
import kotlin.math.roundToInt

internal const val CONFIG_NAVIGATION_BAR_TAG = "config_navigation_bar"
internal const val CONFIG_NAVIGATION_RAIL_TAG = "config_navigation_rail"

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
    Basic(R.string.config_destination_general, R.drawable.ic_config_quick),
    Display(R.string.config_destination_graphics, R.drawable.ic_config_graphics),
    Audio(R.string.config_destination_audio, R.drawable.ic_config_audio),
    Controls(R.string.config_destination_controls, R.drawable.ic_config_controls),
    System(R.string.config_destination_system, R.drawable.ic_config_system),
}

internal enum class ConfigAction(val title: Int, val message: Int) {
    ClearData(R.string.config_delete_game_data, R.string.config_message_delete_game_data),
    ResetSettings(R.string.config_reset_all_settings, R.string.config_message_reset_settings),
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
    var pendingAction by remember { mutableStateOf<ConfigAction?>(null) }
    var systemPropertiesEditorVisible by rememberSaveable { mutableStateOf(false) }
    val updateForm: (ConfigFormState) -> Unit = { next ->
        events.onFormChanged(next)
    }

    val destinations = ConfigDestination.values().toList()
    val initialDestinationIndex = initialDestination
        ?.let { destinations.indexOf(it).takeIf { index -> index >= 0 } }
        ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialDestinationIndex,
        pageCount = { destinations.size },
    )
    val pagerScope = rememberCoroutineScope()
    val selectedDestination = destinations.getOrElse(pagerState.currentPage) { destinations.first() }
    val selectDestination: (ConfigDestination) -> Unit = { destination ->
        val index = destinations.indexOf(destination)
        if (index >= 0 && index != pagerState.currentPage) {
            pagerScope.launch { pagerState.animateScrollToPage(index) }
        }
    }
    val useNavigationRail = availableWindowWidthDp() >= 600.dp
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    if (systemPropertiesEditorVisible) {
        ConfigSystemPropertiesPage(
            value = form.systemProperties,
            onBack = { systemPropertiesEditorVisible = false },
            onSave = { value ->
                systemPropertiesEditorVisible = false
                updateForm(form.toBuilder().systemProperties(value).build())
            },
            modifier = modifier,
        )
    } else {
        Row(modifier = modifier.fillMaxSize()) {
            if (useNavigationRail) {
                ConfigNavigationRail(
                    destinations = destinations,
                    selected = selectedDestination,
                    onSelected = selectDestination,
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
                    // Keep the destination bar from floating above the IME while editing text.
                    if (!useNavigationRail && !imeVisible) {
                        ConfigNavigationBar(
                            destinations = destinations,
                            selected = selectedDestination,
                            onSelected = selectDestination,
                        )
                    }
                },
            ) { padding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) { page ->
                    val pageScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(pageScrollState)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            ConfigDestinationContent(
                                destination = destinations[page],
                                state = state,
                                form = form,
                                onFormChanged = updateForm,
                                events = events,
                                isProfile = isProfile,
                                onRequestAction = { pendingAction = it },
                                onEditSystemProperties = { systemPropertiesEditorVisible = true },
                                modifier = Modifier.widthIn(max = 880.dp),
                            )
                        }
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

    pendingAction?.let { action ->
        ConfigActionConfirmationDialog(
            action = action,
            isProfile = isProfile,
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
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    isProfile: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    onEditSystemProperties: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (destination) {
            ConfigDestination.Basic -> GeneralDestination(
                state = state,
                form = form,
                onFormChanged = onFormChanged,
                events = events,
                showProfileStatus = !isProfile,
            )
            ConfigDestination.Display -> {
                ScreenSection(form, state, onFormChanged, events)
                FontSection(form, state, onFormChanged)
            }
            ConfigDestination.Audio -> AudioSection(form, state, onFormChanged)
            ConfigDestination.Controls -> InputSection(form, onFormChanged, events, onRequestAction)
            ConfigDestination.System -> {
                EmulationSection(form, onFormChanged)
                SystemSection(state, form, onFormChanged, events, !isProfile, onRequestAction, onEditSystemProperties)
            }
        }
    }
}

@Composable
private fun GeneralDestination(
    state: ConfigUiState,
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    showProfileStatus: Boolean,
) {
    if (showProfileStatus) {
        ConfigProfilePanel(state.profileStatus, state.profileTemplates, events)
    }
    var presetsDialogVisible by rememberSaveable { mutableStateOf(false) }
    ConfigSection(title = stringResource(R.string.config_basic_display)) {
        ConfigValuePreference(
            title = stringResource(R.string.config_screen_size),
            description = stringResource(R.string.config_help_screen_size),
            value = "${form.screenWidth} × ${form.screenHeight}",
            onClick = { presetsDialogVisible = true },
        )
        val orientationOptions = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.PREF_ORIENTATION),
            description = stringResource(R.string.config_help_orientation),
            selected = orientationOptions.getOrElse(form.orientation) { orientationOptions.firstOrNull().orEmpty() },
            options = orientationOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().orientation(index).build()) },
        )
        val scaleOptions = stringArrayResource(R.array.pref_scale_type_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_screen_scale_type),
            description = stringResource(R.string.config_help_scale_type),
            selected = scaleOptions.getOrElse(form.screenScaleType) { scaleOptions.firstOrNull().orEmpty() },
            options = scaleOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().screenScaleType(index).build()) },
        )
        ConfigNumberPreference(
            title = stringResource(R.string.PREF_SCALE_RATIO),
            description = stringResource(R.string.config_help_scale_ratio),
            value = form.screenScaleRatio,
            fallbackLabel = "100",
            valueSuffix = "%",
            keyboardType = KeyboardType.Number,
            onValueChange = { value ->
                onFormChanged(form.toBuilder().screenScaleRatio(normalizeScaleRatio(value)).build())
            },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_FORCE_FULLSCREEN),
            description = stringResource(R.string.config_help_force_fullscreen),
            checked = form.forceFullscreen,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().forceFullscreen(checked).build())
            },
        )
    }
    ConfigSection(title = stringResource(R.string.config_basic_input)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            description = stringResource(R.string.config_help_virtual_keyboard),
            checked = form.showKeyboard,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().showKeyboard(checked).build())
            },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_TOUCH_INPUT),
            description = stringResource(R.string.config_help_touch_input),
            checked = form.touchInput,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().touchInput(checked).build())
            },
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
            onSwap = {
                presetsDialogVisible = false
                onFormChanged(
                    form.toBuilder()
                        .screenWidth(form.screenHeight)
                        .screenHeight(form.screenWidth)
                        .build(),
                )
            },
            onAdd = events::onAddResolutionPreset,
            onRemove = events::onRemoveResolutionPreset,
        )
    }
}

@Composable
private fun ConfigNavigationBar(
    destinations: List<ConfigDestination>,
    selected: ConfigDestination,
    onSelected: (ConfigDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.testTag(CONFIG_NAVIGATION_BAR_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { destination ->
            val label = stringResource(destination.label)
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                colors = jlModPlusNavigationBarItemColors(),
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
    NavigationRail(
        modifier = Modifier.testTag(CONFIG_NAVIGATION_RAIL_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        destinations.forEach { destination ->
            val label = stringResource(destination.label)
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                colors = jlModPlusNavigationRailItemColors(),
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
                    contentDescription = stringResource(R.string.action_back),
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
    ConfigSection(title = stringResource(R.string.config_display_appearance)) {
        ConfigColorPreference(
            title = stringResource(R.string.PREF_BACKGROUND),
            description = stringResource(R.string.config_help_background),
            value = form.screenBackground,
            onClick = { events.onColorPicker(ConfigFormEvents.ColorField.SCREEN_BACKGROUND) },
        )
        val skinIndex = state.skins.indexOfFirst { it == form.screenBackgroundImage }.coerceAtLeast(0)
        ConfigChoicePreference(
            title = stringResource(R.string.pref_skin_title),
            description = stringResource(R.string.config_help_skin),
            selected = state.skins.getOrElse(skinIndex) { "" },
            options = state.skins,
            onSelected = { index ->
                onFormChanged(
                    form.toBuilder().screenBackgroundImage(
                        if (index == 0) null else state.skins.getOrNull(index),
                    ).build(),
                )
            },
        )
        val gravityOptions = stringArrayResource(R.array.pref_screen_gravity_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_screen_gravity),
            description = stringResource(R.string.config_help_screen_gravity),
            selected = gravityOptions.getOrElse(form.screenGravity) { gravityOptions.firstOrNull().orEmpty() },
            options = gravityOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().screenGravity(index).build()) },
        )
        ConfigNumberPreference(
            title = stringResource(R.string.pref_screen_padding_title),
            description = stringResource(R.string.config_help_screen_padding),
            value = form.screenPadding,
            fallbackLabel = "0",
            keyboardType = KeyboardType.Number,
            onValueChange = { value -> onFormChanged(form.toBuilder().screenPadding(value).build()) },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_FILTER),
            description = stringResource(R.string.config_help_filter),
            checked = form.screenFilter,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().screenFilter(checked).build()) },
        )
    }
    ConfigSection(title = stringResource(R.string.config_display_rendering)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_IMMEDIATE),
            description = stringResource(R.string.config_help_immediate),
            checked = form.immediateMode,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().immediateMode(checked).build()) },
        )
        val graphicsOptions = stringArrayResource(R.array.pref_graphics_mode_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_graphics_mode_title),
            description = stringResource(R.string.config_help_graphics_mode),
            selected = graphicsOptions.getOrElse(form.graphicsMode) { graphicsOptions.firstOrNull().orEmpty() },
            options = graphicsOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().graphicsMode(index).build()) },
        )
        if (form.graphicsMode == 1) {
            val shaderIndex = state.shaders.indexOfFirst { it == form.shader }.coerceAtLeast(0)
            ConfigChoicePreference(
                title = stringResource(R.string.PREF_SHADER_FILTER),
                description = stringResource(R.string.config_help_shader),
                selected = state.shaders.getOrElse(shaderIndex) { "" }.toString(),
                options = state.shaders.map { it.toString() },
                onSelected = { index ->
                    onFormChanged(
                        form.toBuilder().shader(if (index == 0) null else state.shaders.getOrNull(index)).build(),
                    )
                },
            )
            val selectedShader = state.shaders.getOrNull(shaderIndex)
            if (selectedShader?.hasTunableSettings() == true) {
                ConfigActionPreference(
                    title = stringResource(R.string.shader_tuning),
                    description = stringResource(R.string.config_help_shader_tuning),
                    onClick = events::onShaderTuning,
                )
            }
        }
        if (form.graphicsMode == 0 || form.graphicsMode == 3) {
            ConfigSwitchPreference(
                title = stringResource(R.string.parallel_screen_redrawing),
                description = stringResource(R.string.config_help_parallel_redraw),
                checked = form.parallelRedrawScreen,
                onCheckedChange = { checked ->
                    onFormChanged(form.toBuilder().parallelRedrawScreen(checked).build())
                },
            )
        }
    }
    ConfigSection(title = stringResource(R.string.config_display_performance)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_SHOW_FPS),
            description = stringResource(R.string.config_help_show_fps),
            checked = form.showFps,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showFps(checked).build()) },
        )
        ConfigNumberPreference(
            title = stringResource(R.string.PREF_LIMIT_FPS),
            description = stringResource(R.string.config_help_fps_limit),
            value = form.fpsLimit,
            fallbackLabel = stringResource(R.string.unlimited),
            keyboardType = KeyboardType.Number,
            onValueChange = { value -> onFormChanged(form.toBuilder().fpsLimit(value).build()) },
        )
    }
}

@Composable
internal fun ScreenPresetDialog(
    presets: List<Size>,
    removablePresets: List<Size>,
    selectedPreset: Size?,
    onDismissRequest: () -> Unit,
    onSelected: (Size) -> Unit,
    onSwap: (() -> Unit)? = null,
    onAdd: (Size) -> Unit,
    onRemove: (Size) -> Unit,
    useModalBottomSheet: Boolean = true,
) {
    var customResolutionVisible by rememberSaveable { mutableStateOf(false) }
    val dialogLayout = adaptiveDialogLayout()
    val listedPresets = if (selectedPreset != null && !presets.contains(selectedPreset)) {
        listOf(selectedPreset) + presets
    } else {
        presets
    }
    val temporaryPreset = selectedPreset != null && !presets.contains(selectedPreset)

    val selectAndDismiss: (Size) -> Unit = { size ->
        onSelected(size)
        onDismissRequest()
    }

    val dialogContent: @Composable () -> Unit = {
        val listState = rememberLazyListState()
        val canScrollForward = rememberLazyListCanScrollForward(listState)
        Surface(
            modifier = dialogLayout.modifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_select_screen_size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
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
                                        selected = preset == selectedPreset,
                                        onClick = null,
                                    )
                                },
                                trailingContent = if (!isTemporary && removablePresets.contains(preset)) {
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
                                        onClick = { selectAndDismiss(preset) },
                                    ),
                            )
                        }
                    }
                    ScrollableContentHint(
                        visible = canScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                HorizontalDivider()
                if (onSwap != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSwap)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.config_swap_screen_size),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { customResolutionVisible = true }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
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
                }
            }
        }
    }

    if (useModalBottomSheet) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = dialogLayout.properties,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                dialogContent()
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            dialogContent()
        }
    }

    if (customResolutionVisible) {
        CustomResolutionDialog(
            initialSize = selectedPreset,
            onDismissRequest = { customResolutionVisible = false },
            onSave = { size ->
                customResolutionVisible = false
                onAdd(size)
                selectAndDismiss(size)
            },
        )
    }
}

@Composable
private fun configDialogLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
private fun ConfigDialogScrollableBody(
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Leave title and actions outside the scroll container, including with large fonts.
    val maxBodyHeight = adaptiveDialogLayout().maxContentHeight(reservedHeight = 168.dp)
    val scrollState = rememberScrollState()
    val hintThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val canScrollForward = rememberScrollCanScrollForward(scrollState, hintThresholdPx)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxBodyHeight)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                .verticalScroll(scrollState),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        ScrollableContentHint(
            visible = canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun CustomResolutionDialog(
    initialSize: Size?,
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
    var lockAspect by rememberSaveable(fallback.width, fallback.height) { mutableStateOf(false) }
    var aspectWidth by rememberSaveable(fallback.width, fallback.height) { mutableIntStateOf(fallback.width) }
    var aspectHeight by rememberSaveable(fallback.width, fallback.height) { mutableIntStateOf(fallback.height) }
    val validWidth = width.toIntOrNull()?.takeIf { it > 0 }
    val validHeight = height.toIntOrNull()?.takeIf { it > 0 }
    val valid = validWidth != null && validHeight != null
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_custom_resolution)) },
        text = {
            ConfigDialogScrollableBody(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { value ->
                            width = value
                            if (lockAspect) {
                                val newWidth = value.toIntOrNull()
                                if (newWidth != null && aspectWidth > 0) {
                                    height = (newWidth * aspectHeight.toFloat() / aspectWidth)
                                        .roundToInt().toString()
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
                        val oldAspectWidth = aspectWidth
                        aspectWidth = aspectHeight
                        aspectHeight = oldAspectWidth
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
                            if (lockAspect) {
                                val newHeight = value.toIntOrNull()
                                if (newHeight != null && aspectHeight > 0) {
                                    width = (newHeight * aspectWidth.toFloat() / aspectHeight)
                                        .roundToInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.PREF_HEIGHT)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.config_lock_custom_resolution_ratio),
                    checked = lockAspect,
                    onCheckedChange = { checked ->
                        if (checked) {
                            width.toIntOrNull()?.takeIf { it > 0 }?.let { aspectWidth = it }
                            height.toIntOrNull()?.takeIf { it > 0 }?.let { aspectHeight = it }
                        }
                        lockAspect = checked
                    },
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
private fun FontSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    var fontSizesVisible by rememberSaveable { mutableStateOf(false) }
    ConfigSection(title = stringResource(R.string.config_display_text)) {
        ConfigValuePreference(
            title = stringResource(R.string.config_font_sizes),
            description = stringResource(R.string.config_help_font_sizes),
            value = stringResource(
                R.string.config_font_sizes_value,
                form.fontSizeSmall,
                form.fontSizeMedium,
                form.fontSizeLarge,
            ),
            onClick = { fontSizesVisible = true },
        )
        val selectedPreset = state.fontPresets.firstOrNull { preset ->
            preset.small.toString() == form.fontSizeSmall &&
                preset.medium.toString() == form.fontSizeMedium &&
                preset.large.toString() == form.fontSizeLarge
        }?.title ?: stringResource(R.string.profile_custom)
        ConfigChoicePreference(
            title = stringResource(R.string.SIZE_PRESETS),
            description = stringResource(R.string.config_help_font_presets),
            selected = selectedPreset,
            options = state.fontPresets.map { it.title },
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
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_FONT_SIZE_IN_SP),
            description = stringResource(R.string.config_help_font_dimensions),
            checked = form.fontApplyDimensions,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().fontApplyDimensions(checked).build())
            },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_FONT_ANTI_ALIASING),
            description = stringResource(R.string.config_help_font_aa),
            checked = form.fontAA,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().fontAA(checked).build()) },
        )
    }
    if (fontSizesVisible) {
        FontSizesDialog(
            small = form.fontSizeSmall,
            medium = form.fontSizeMedium,
            large = form.fontSizeLarge,
            onDismissRequest = { fontSizesVisible = false },
            onConfirm = { small, medium, large ->
                fontSizesVisible = false
                onFormChanged(
                    form.toBuilder()
                        .fontSizeSmall(small)
                        .fontSizeMedium(medium)
                        .fontSizeLarge(large)
                        .build(),
                )
            },
        )
    }
}

@Composable
internal fun FontSizesDialog(
    small: String,
    medium: String,
    large: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var smallDraft by remember(small) { mutableStateOf(small) }
    var mediumDraft by remember(medium) { mutableStateOf(medium) }
    var largeDraft by remember(large) { mutableStateOf(large) }
    val landscape = configDialogLandscape()
    val useHorizontalFields = availableWindowWidthDp() >= 600.dp
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_font_sizes)) },
        text = {
            ConfigDialogScrollableBody(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_help_font_sizes_long),
                    style = if (landscape) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (useHorizontalFields) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FontSizeField(smallDraft, { smallDraft = it }, R.string.PREF_FONT_SMALL, Modifier.weight(1f))
                        FontSizeField(mediumDraft, { mediumDraft = it }, R.string.PREF_FONT_MEDIUM, Modifier.weight(1f))
                        FontSizeField(largeDraft, { largeDraft = it }, R.string.PREF_FONT_LARGE, Modifier.weight(1f))
                    }
                } else {
                    FontSizeField(smallDraft, { smallDraft = it }, R.string.PREF_FONT_SMALL)
                    FontSizeField(mediumDraft, { mediumDraft = it }, R.string.PREF_FONT_MEDIUM)
                    FontSizeField(largeDraft, { largeDraft = it }, R.string.PREF_FONT_LARGE)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = smallDraft.toIntOrNull() != null &&
                    mediumDraft.toIntOrNull() != null && largeDraft.toIntOrNull() != null,
                onClick = { onConfirm(smallDraft, mediumDraft, largeDraft) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun FontSizeField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun InputSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    onRequestAction: (ConfigAction) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_controls_key_input)) {
        val layoutOptions = stringArrayResource(R.array.PREF_LAYOUT_ENTRIES).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.PREF_LAYOUT),
            description = stringResource(R.string.config_help_key_layout),
            selected = layoutOptions.getOrElse(form.keyCodesLayout) { layoutOptions.firstOrNull().orEmpty() },
            options = layoutOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().keyCodesLayout(index).build()) },
        )
        ConfigActionPreference(
            title = stringResource(R.string.pref_map_keys),
            description = stringResource(R.string.config_help_key_mapping),
            onClick = events::onKeyMappings,
        )
    }
    ConfigSection(title = stringResource(R.string.config_controls_virtual_keyboard)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            description = stringResource(R.string.config_help_virtual_keyboard),
            checked = form.showKeyboard,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showKeyboard(checked).build()) },
        )
        if (form.showKeyboard) {
            val shapeOptions = stringArrayResource(R.array.pref_button_shape_entries).toList()
            ConfigChoicePreference(
                title = stringResource(R.string.pref_button_shape_title),
                description = stringResource(R.string.config_help_vk_button_shape),
                selected = shapeOptions.getOrElse(form.vkButtonShape) { shapeOptions.firstOrNull().orEmpty() },
                options = shapeOptions,
                onSelected = { index -> onFormChanged(form.toBuilder().vkButtonShape(index).build()) },
            )
            ConfigSwitchPreference(
                title = stringResource(R.string.PREF_VK_FEEDBACK),
                description = stringResource(R.string.config_help_vk_feedback),
                checked = form.vkFeedback,
                onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkFeedback(checked).build()) },
            )
            ConfigSliderPreference(
                title = stringResource(R.string.PREF_VK_ALPHA),
                description = stringResource(R.string.config_help_vk_alpha),
                value = form.vkAlpha,
                valueRange = 0..255,
                onSelected = { value -> onFormChanged(form.toBuilder().vkAlpha(value).build()) },
            )
            ConfigSwitchPreference(
                title = stringResource(R.string.PREF_VK_FORCE_OPACITY),
                description = stringResource(R.string.config_help_vk_force_opacity),
                checked = form.vkForceOpacity,
                onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkForceOpacity(checked).build()) },
            )
            ConfigNumberPreference(
                title = stringResource(R.string.PREF_VK_HIDE_DELAY),
                description = stringResource(R.string.config_help_vk_hide_delay),
                value = form.vkHideDelay,
                fallbackLabel = stringResource(R.string.pref_vk_hide_hint),
                valueSuffix = stringResource(R.string.PREF_UNIT_MS),
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChanged(form.toBuilder().vkHideDelay(value).build()) },
            )
        }
    }
    ConfigSection(title = stringResource(R.string.config_controls_virtual_keyboard_colors)) {
        if (form.showKeyboard) {
            ConfigColorPreference(
                title = stringResource(R.string.PREF_VK_FORE),
                description = stringResource(R.string.config_help_vk_foreground),
                value = form.vkForeground,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND) },
            )
            ConfigColorPreference(
                title = stringResource(R.string.PREF_VK_BACK),
                description = stringResource(R.string.config_help_vk_background),
                value = form.vkBackground,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND) },
            )
            ConfigColorPreference(
                title = stringResource(R.string.PREF_VK_SEL_FORE),
                description = stringResource(R.string.config_help_vk_selected_foreground),
                value = form.vkSelectedForeground,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND) },
            )
            ConfigColorPreference(
                title = stringResource(R.string.PREF_VK_SEL_BACK),
                description = stringResource(R.string.config_help_vk_selected_background),
                value = form.vkSelectedBackground,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND) },
            )
            ConfigColorPreference(
                title = stringResource(R.string.PREF_VK_OUTLINE),
                description = stringResource(R.string.config_help_vk_outline),
                value = form.vkOutline,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_OUTLINE) },
            )
        }
    }
    ConfigSection(title = stringResource(R.string.config_controls_maintenance)) {
        ConfigActionPreference(
            title = stringResource(R.string.RESET_LAYOUT_CMD),
            description = stringResource(R.string.config_help_reset_layout),
            destructive = true,
            onClick = { onRequestAction(ConfigAction.ResetLayout) },
        )
    }
}

@Composable
private fun EmulationSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.pref_title_emulation)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.pref_skip_resume_call),
            description = stringResource(R.string.config_help_skip_resume),
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
    ConfigSection(title = stringResource(R.string.pref_audio_title)) {
        val selected = state.soundBanks.indexOfFirst { option ->
            form.soundBank != null && option == form.soundBank
        }.let { if (it < 0) 0 else it }
        ConfigChoicePreference(
            title = stringResource(R.string.pref_soundbank_title),
            description = stringResource(R.string.config_help_soundbank),
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

@Composable
private fun SystemSection(
    state: ConfigUiState,
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    showClearData: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    onEditSystemProperties: () -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_system_timing)) {
        val timingModes = TimingMode.values().toList()
        val timingModeOptions = listOf(
            stringResource(R.string.config_timing_mode_full_guest),
            stringResource(R.string.config_timing_mode_real_wall),
        )
        val currentTimingMode = TimingMode.sanitize(form.timingMode)
        val timingModeIndex = timingModes.indexOf(currentTimingMode).coerceAtLeast(0)
        val timingUnavailableMessage = if (state.timingControlsEnabled) null
        else stringResource(R.string.config_help_timing_unavailable)
        ConfigChoicePreference(
            title = stringResource(R.string.PREF_TIMING_MODE),
            description = stringResource(R.string.config_help_timing_mode),
            selected = timingModeOptions.getOrElse(timingModeIndex) { timingModeOptions.first() },
            options = timingModeOptions,
            enabled = state.timingControlsEnabled,
            message = timingUnavailableMessage,
            messageLevel = ConfigMessageLevel.Warning,
            onSelected = { index ->
                timingModes.getOrNull(index)?.let { mode ->
                    onFormChanged(form.toBuilder().timingMode(mode).build())
                }
            },
        )
    }
    ConfigSection(title = stringResource(R.string.PREF_SYS_PROPS)) {
        ConfigActionPreference(
  title = stringResource(R.string.pref_encoding_title),
  description = stringResource(R.string.config_help_encoding),
  onClick = events::onEncodingPicker,
        )
        ConfigSystemPropertiesPreference(
  value = form.systemProperties,
  onClick = onEditSystemProperties,
        )
    }

    ConfigSection(title = stringResource(R.string.config_maintenance)) {
        ConfigActionPreference(
  title = stringResource(R.string.config_reset_all_settings),
  description = stringResource(R.string.config_reset_all_settings_summary),
  destructive = true,
  onClick = { onRequestAction(ConfigAction.ResetSettings) },
        )
        if (showClearData) {
  ConfigActionPreference(
      title = stringResource(R.string.config_delete_game_data),
      description = stringResource(R.string.config_delete_game_data_summary),
      destructive = true,
      onClick = { onRequestAction(ConfigAction.ClearData) },
  )
        }
    }
}

@Composable
private fun ConfigSystemPropertiesPreference(
    value: String,
    onClick: () -> Unit,
) {
    ConfigValuePreference(
        title = stringResource(R.string.config_edit_system_properties),
        description = stringResource(R.string.config_help_system_properties),
        value = pluralStringResource(
            R.plurals.config_system_properties_value,
            value.lineSequence().count { it.isNotBlank() },
            value.lineSequence().count { it.isNotBlank() },
        ),
        message = stringResource(R.string.config_system_properties_info),
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigSystemPropertiesPage(
    value: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val hostView = LocalView.current
    DisposableEffect(hostView, onBack) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }
        (hostView.context as? AppCompatActivity)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.PREF_SYS_PROPS)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.config_help_system_properties_long),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConfigMessageBlock(stringResource(R.string.config_system_properties_info))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    summary: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ConfigActionPreference(
        title = title,
        description = summary ?: stringResource(R.string.config_help_action_generic),
        destructive = destructive,
        onClick = onClick,
    )
}

@Composable
private fun ConfigActionConfirmationDialog(
    action: ConfigAction,
    isProfile: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = if (action == ConfigAction.ResetSettings && isProfile) {
        R.string.config_message_reset_profile_settings
    } else {
        action.message
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(action.title)) },
        text = { Text(stringResource(message)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(action.title))
            }
        },
    )
}

@Composable
private fun ConfigRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Box(modifier = Modifier.weight(0.9f), contentAlignment = Alignment.CenterEnd) {
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
    val shownValue = buildString {
        append(value.ifEmpty { label })
        if (value.isNotEmpty() && valueSuffix != null) append(" ").append(valueSuffix)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable { dialogVisible = true },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = shownValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    description: String? = null,
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
            ConfigDialogScrollableBody(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable { dialogVisible = true },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
    description: String? = null,
    initialValue: Int,
    valueRange: IntRange,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftText by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            ConfigDialogScrollableBody(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val draftValue = draftText.toIntOrNull()
                val currentValue = (draftValue ?: initialValue).coerceIn(valueRange)
                Slider(
                    value = currentValue.toFloat(),
                    onValueChange = { value -> draftText = value.roundToInt().toString() },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    // Keep the control visually continuous. A step for every integer makes
                    // Material3 draw hundreds of tick marks, which turns the track into a
                    // distracting double line for ranges such as 0..255.
                    steps = 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    ),
                    track = { sliderState ->
                        val primary = MaterialTheme.colorScheme.primary
                        val inactive = primary.copy(alpha = 0.28f)
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        ) {
                            val range = valueRange.last - valueRange.first
                            val fraction = if (range <= 0) {
                                0f
                            } else {
                                ((sliderState.value - valueRange.first.toFloat()) / range.toFloat())
                                    .coerceIn(0f, 1f)
                            }
                            val radius = size.height / 2f
                            drawRoundRect(
                                color = inactive,
                                topLeft = Offset.Zero,
                                size = size,
                                cornerRadius = CornerRadius(radius, radius),
                            )
                            val activeWidth = size.width * fraction
                            if (activeWidth > 0f) {
                                drawRoundRect(
                                    color = primary,
                                    topLeft = Offset.Zero,
                                    size = ComposeSize(activeWidth, size.height),
                                    cornerRadius = CornerRadius(radius, radius),
                                )
                            }
                        }
                    },
                    thumb = {
                        // Keep the target easy to drag without adding endpoint icons.
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .shadow(2.dp, CircleShape)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    },
                )
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    label = { Text(stringResource(R.string.config_current_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(valueRange.first.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(valueRange.last.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Reserve a small clear area for the automatic scroll affordance so it never
                // obscures the range labels when the dialog is height-constrained.
                Spacer(Modifier.height(28.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    draftText.toIntOrNull()?.let { onConfirm(it.coerceIn(valueRange)) }
                },
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled) { dialogVisible = true },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = selected,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
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
    description: String? = null,
    selected: String,
    options: List<String>,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    val maxListHeight = adaptiveDialogLayout().maxContentHeight(reservedHeight = 152.dp)
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val listState = rememberLazyListState()
                val canScrollForward = rememberLazyListCanScrollForward(listState)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        state = listState,
                    ) {
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
                    ScrollableContentHint(
                        visible = canScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
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
    ConfigSwitchPreference(
        title = title,
        description = stringResource(R.string.config_help_generic_toggle),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun ColorRow(
    label: String,
    value: String,
    onPick: () -> Unit,
) {
    ConfigColorPreference(
        title = label,
        description = stringResource(R.string.config_help_generic_color),
        value = value,
        onClick = onPick,
    )
}

private fun normalizeScaleRatio(value: String): String {
    val filtered = value.filter(Char::isDigit).take(4)
    return if (filtered.toIntOrNull()?.let { it > 1000 } == true) "1000" else filtered
}

private fun parseColor(value: String): Color {
    val parsed = value.toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or parsed).toInt())
}
