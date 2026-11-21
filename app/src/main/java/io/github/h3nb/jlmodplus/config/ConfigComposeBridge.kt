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

package io.github.h3nb.jlmodplus.config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.input.HostCommand
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import javax.microedition.shell.timing.TimingMode
import javax.microedition.lcdui.keyboard.VirtualControlAppearance
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.mmapi.camera.CameraCaptureOptions
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp
import io.github.h3nb.jlmodplus.ui.PAGE_TRAVEL_DP
import io.github.h3nb.jlmodplus.ui.pageMotionDirection
import io.github.h3nb.jlmodplus.ui.pageEnter
import io.github.h3nb.jlmodplus.ui.pageExit
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
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
    private var gamepadHelpVisible by mutableStateOf(false)
    private var gamepadDiagnosis by mutableStateOf<String?>(null)
    private var gamepadCalibration by mutableStateOf<GamepadCalibrationUiState?>(null)

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
                    gamepadHelpVisible = gamepadHelpVisible,
                    gamepadDiagnosis = gamepadDiagnosis,
                    gamepadCalibration = gamepadCalibration,
                    onColorPickerDismiss = { colorPicker = null },
                    onColorPicked = { field, value ->
                        colorPicker = null
                        events.onColorPicked(field, value)
                    },
                    onBackgroundColorPicked = { value, dynamic ->
                        colorPicker = null
                        events.onBackgroundColorPicked(value, dynamic)
                    },
                    onEncodingPickerDismiss = { encodingPicker = null },
                    onEncodingSelected = { charset ->
                        encodingPicker = null
                        events.onEncodingSelected(charset)
                    },
                    onGamepadHelpDismiss = { gamepadHelpVisible = false },
                    onGamepadDiagnosisDismiss = { gamepadDiagnosis = null },
                    onGamepadCalibrationDismiss = {
                        gamepadCalibration = null
                        events.onGamepadCalibrationCancel()
                    },
                    onGamepadCalibrationReset = { events.onGamepadCalibrationReset() },
                    onGamepadCalibrationAdvance = { events.onGamepadCalibrationAdvance() },
                    onGamepadCalibrationSave = { events.onGamepadCalibrationSave() },
                )
            }
        }
    }

    fun update(nextState: ConfigUiState) {
        state = nextState
    }

    fun showColorPicker(field: ConfigFormEvents.ColorField, initialHex: String, dynamic: Boolean) {
        colorPicker = ColorPickerRequest(field, initialHex, dynamic)
    }

    /** Drops a host-owned picker that became invalid while the draft was changing. */
    fun dismissColorPicker(field: ConfigFormEvents.ColorField) {
        if (colorPicker?.field == field) colorPicker = null
    }

    fun showEncodingPicker(options: List<String>, selected: String?) {
        encodingPicker = EncodingPickerRequest(options, selected)
    }

    fun isControllerModalActive(): Boolean =
        gamepadHelpVisible || gamepadDiagnosis != null || gamepadCalibration != null

    /** Consumes host commands while a controller-owned modal is visible. */
    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!isControllerModalActive()) return false
        if (pressed) {
            when {
                gamepadCalibration != null -> when (command) {
                    HostCommand.Activate -> {
                        val calibration = gamepadCalibration ?: return true
                        when {
                            calibration.canSave -> events.onGamepadCalibrationSave()
                            calibration.canAdvance -> events.onGamepadCalibrationAdvance()
                        }
                    }
                    HostCommand.Back,
                    HostCommand.OpenMenu,
                    HostCommand.OpenKeypad,
                    -> {
                        gamepadCalibration = null
                        events.onGamepadCalibrationCancel()
                    }
                    else -> Unit
                }
                gamepadHelpVisible && (command == HostCommand.Activate || command == HostCommand.Back ||
                    command == HostCommand.OpenMenu || command == HostCommand.OpenKeypad) -> {
                    gamepadHelpVisible = false
                }
                gamepadDiagnosis != null && (command == HostCommand.Activate || command == HostCommand.Back ||
                    command == HostCommand.OpenMenu || command == HostCommand.OpenKeypad) -> {
                    gamepadDiagnosis = null
                }
            }
        }
        return true
    }

    fun showGamepadHelp() {
        gamepadHelpVisible = true
    }

    fun showGamepadDiagnosis(text: String) {
        gamepadDiagnosis = text
    }

    fun showGamepadCalibration(state: GamepadCalibrationUiState) {
        gamepadCalibration = state
    }

    fun updateGamepadCalibration(state: GamepadCalibrationUiState?) {
        gamepadCalibration = state
    }

}

data class ColorPickerRequest(
    val field: ConfigFormEvents.ColorField,
    val initialHex: String,
    val dynamic: Boolean = false,
)

data class EncodingPickerRequest(
    val options: List<String>,
    val selected: String?,
)

internal enum class ConfigDestination(val label: Int, val icon: Int) {
    Basic(R.string.config_quick_settings, R.drawable.ic_config_general),
    Display(R.string.config_destination_graphics, R.drawable.ic_config_graphics),
    Controls(R.string.config_destination_controls, R.drawable.ic_config_controls),
    Media(R.string.config_destination_media, R.drawable.ic_config_media),
    System(R.string.config_destination_system, R.drawable.ic_settings),
}

internal enum class ConfigAction(val title: Int, val message: Int) {
    ClearData(R.string.config_delete_app_data, R.string.config_message_delete_app_data),
    ResetSettings(R.string.config_reset_all_settings, R.string.config_message_reset_settings),
    ResetLayout(R.string.RESET_LAYOUT_CMD, R.string.message_reset_layout),
}

private enum class ConfigDetail { Font, Performance }

private sealed interface ConfigPage {
    data object Main : ConfigPage
    data class Category(val destination: ConfigDestination) : ConfigPage
    data object SystemProperties : ConfigPage
    data class Detail(val type: ConfigDetail) : ConfigPage
    data class Rendering(val subpage: RenderingSubpage) : ConfigPage
}

private fun ConfigPage.depth(): Int = when (this) {
    ConfigPage.Main -> 0
    is ConfigPage.Category -> 1
    ConfigPage.SystemProperties, is ConfigPage.Detail, is ConfigPage.Rendering -> 2
}

private fun ConfigPage.siblingOrder(): Int = when (this) {
    is ConfigPage.Rendering -> subpage.ordinal
    else -> 0
}

@Composable
internal fun ConfigScreen(
    state: ConfigUiState,
    events: ConfigFormEvents,
    modifier: Modifier = Modifier,
    title: String = "",
    isProfile: Boolean = false,
    initialDestination: ConfigDestination? = null,
    cutoutSupportOverride: Boolean? = null,
    menuActions: ConfigMenuActions? = null,
    colorPicker: ColorPickerRequest? = null,
    encodingPicker: EncodingPickerRequest? = null,
    gamepadHelpVisible: Boolean = false,
    gamepadDiagnosis: String? = null,
    gamepadCalibration: GamepadCalibrationUiState? = null,
    onColorPickerDismiss: () -> Unit = {},
    onColorPicked: (ConfigFormEvents.ColorField, String) -> Unit = { _, _ -> },
    onBackgroundColorPicked: (String, Boolean) -> Unit = { _, _ -> },
    onEncodingPickerDismiss: () -> Unit = {},
    onEncodingSelected: (String) -> Unit = {},
    onGamepadHelpDismiss: () -> Unit = {},
    onGamepadDiagnosisDismiss: () -> Unit = {},
    onGamepadCalibrationDismiss: () -> Unit = {},
    onGamepadCalibrationReset: () -> Unit = {},
    onGamepadCalibrationAdvance: () -> Unit = {},
    onGamepadCalibrationSave: () -> Unit = {},
) {
    val form = state.form
    var pendingAction by remember { mutableStateOf<ConfigAction?>(null) }
    var systemPropertiesEditorVisible by rememberSaveable { mutableStateOf(false) }
    var systemPropertiesSession by rememberSaveable { mutableIntStateOf(0) }
    var renderingSubpage by rememberSaveable { mutableStateOf<RenderingSubpage?>(null) }
    var detailPage by rememberSaveable { mutableStateOf<ConfigDetail?>(null) }
    var selectedDestination by rememberSaveable {
        mutableStateOf(initialDestination ?: ConfigDestination.Basic)
    }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var revealSection by remember { mutableStateOf<String?>(null) }
    var quickSetting by rememberSaveable { mutableStateOf<QuickSetting?>(null) }
    var presetPickerVisible by rememberSaveable { mutableStateOf(false) }
    var savePresetVisible by rememberSaveable { mutableStateOf(false) }
    var discardProfileChangesVisible by rememberSaveable { mutableStateOf(false) }
    val updateForm: (ConfigFormState) -> Unit = events::onFormChanged
    val closeSearch = { searchVisible = false; searchQuery = "" }
    val requestBack = {
        when {
            searchVisible -> closeSearch()
            renderingSubpage != null -> renderingSubpage = null
            detailPage != null -> detailPage = null
            systemPropertiesEditorVisible -> systemPropertiesEditorVisible = false
            selectedDestination != ConfigDestination.Basic ->
                selectedDestination = ConfigDestination.Basic
            isProfile && menuActions?.hasUnsavedProfileChanges() == true ->
                discardProfileChangesVisible = true
            else -> {
                menuActions?.onBack()
                Unit
            }
        }
    }
    BackHandler(enabled = searchVisible || renderingSubpage != null || detailPage != null ||
        systemPropertiesEditorVisible || selectedDestination != ConfigDestination.Basic || isProfile) {
        requestBack()
    }

    val quickScrollState = rememberScrollState()
    val displayScrollState = rememberScrollState()
    val controlsScrollState = rememberScrollState()
    val mediaScrollState = rememberScrollState()
    val systemScrollState = rememberScrollState()
    val density = LocalDensity.current
    val cutoutAvailable = cutoutSupportOverride ?: deviceHasDisplayCutout(density)
    val currentPage = when {
        renderingSubpage != null -> ConfigPage.Rendering(requireNotNull(renderingSubpage))
        detailPage != null -> ConfigPage.Detail(requireNotNull(detailPage))
        systemPropertiesEditorVisible -> ConfigPage.SystemProperties
        selectedDestination != ConfigDestination.Basic -> ConfigPage.Category(selectedDestination)
        else -> ConfigPage.Main
    }
    val motionOffsetPx = with(density) { PAGE_TRAVEL_DP.dp.roundToPx() }
    AnimatedContent(
        targetState = currentPage,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val direction = pageMotionDirection(
                initialState.depth(), targetState.depth(),
                initialState.siblingOrder(), targetState.siblingOrder(),
            )
            pageEnter(direction, motionOffsetPx).togetherWith(pageExit(direction, motionOffsetPx))
        },
        label = "Config page",
    ) { shownPage ->
        val active = shownPage == currentPage
        Box(
            modifier = Modifier.fillMaxSize().then(
                if (active) Modifier else Modifier.clearAndSetSemantics {},
            ),
        ) {
            when (shownPage) {
                is ConfigPage.Detail -> {
                    ConfigDetailPage(
                        detail = shownPage.type,
                        form = form,
                        state = state,
                        onFormChanged = updateForm,
                        onBack = { detailPage = null },
                    )
                }
                is ConfigPage.Rendering -> {
                    RenderingSettingsPage(
                        state = state,
                        onFormChanged = updateForm,
                        onShaderPickerOpened = events::onShaderPickerOpened,
                        onBack = { if (active) renderingSubpage = null },
                    )
                }
                ConfigPage.SystemProperties -> {
                    val context = LocalContext.current
                    val defaultProperties = remember(context) {
                        ConfigFormState.normalizeSystemProperties(
                            context.assets.open("defaults/system.props").bufferedReader().use { it.readText() }
                        )
                    }
                    key(systemPropertiesSession) {
                        ConfigSystemPropertiesPage(
                            value = form.systemProperties,
                            defaultValue = defaultProperties,
                            onBack = { if (active) systemPropertiesEditorVisible = false },
                            onSave = { value ->
                                systemPropertiesEditorVisible = false
                                updateForm(form.toBuilder().systemProperties(value).build())
                            },
                            modifier = Modifier,
                        )
                    }
                }
                ConfigPage.Main, is ConfigPage.Category -> {
                    val destination = (shownPage as? ConfigPage.Category)?.destination
                        ?: ConfigDestination.Basic
                    val scrollState = when (destination) {
                        ConfigDestination.Basic -> quickScrollState
                        ConfigDestination.Display -> displayScrollState
                        ConfigDestination.Controls -> controlsScrollState
                        ConfigDestination.Media -> mediaScrollState
                        ConfigDestination.System -> systemScrollState
                    }
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            ConfigTopBar(
                                title = if (destination == ConfigDestination.Basic) title
                                    else stringResource(destination.label),
                                isProfile = isProfile,
                                onBack = requestBack,
                                onSearch = { searchVisible = true },
                                onStart = { menuActions?.onStart() },
                                onSave = { menuActions?.onSaveProfileDraft() },
                                saveEnabled = !isProfile ||
                                    menuActions?.hasUnsavedProfileChanges() == true,
                                searchVisible = searchVisible,
                                searchQuery = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onCloseSearch = closeSearch,
                            )
                        },
                    ) { padding ->
                        if (searchVisible) {
                            ConfigSearchResults(
                                query = searchQuery,
                                isProfile = isProfile,
                                modifier = Modifier.fillMaxSize().padding(padding)
                                    .consumeWindowInsets(padding),
                                onSelected = { nextDestination, section, detail ->
                                    closeSearch()
                                    selectedDestination = nextDestination
                                    revealSection = if (detail == null) section else null
                                    detailPage = detail
                                },
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(padding)
                                    .consumeWindowInsets(padding).imePadding()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (destination == ConfigDestination.Basic) {
                                    Column(
                                        modifier = Modifier.widthIn(max = 880.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ConfigQuickSettings(
                                            state = state,
                                            isProfile = isProfile,
                                            events = events,
                                            onUsePreset = { presetPickerVisible = true },
                                            onSavePreset = { savePresetVisible = true },
                                            onStopFollowing = { events.onStopFollowingProfile() },
                                            onOpenCategory = { selectedDestination = it },
                                            onOpenPerformance = { detailPage = ConfigDetail.Performance },
                                            onOpenQuickSetting = { quickSetting = it },
                                        )
                                    }
                                } else {
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        LocalConfigSectionReveal provides revealSection?.let { section ->
                                            ConfigSectionReveal(section) { revealSection = null }
                                        },
                                    ) {
                                        ConfigDestinationContent(
                                            destination = destination,
                                            state = state,
                                            form = form,
                                            onFormChanged = updateForm,
                                            events = events,
                                            isProfile = isProfile,
                                            cutoutAvailable = cutoutAvailable,
                                            onRequestAction = { pendingAction = it },
                                            onEditSystemProperties = {
                                                systemPropertiesSession++
                                                systemPropertiesEditorVisible = true
                                            },
                                            onOpenShaders = {
                                                renderingSubpage = RenderingSubpage.SHADERS
                                            },
                                            onOpenDetail = { detailPage = it },
                                            modifier = Modifier.widthIn(max = 880.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!active) {
                Box(
                    Modifier.matchParentSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                )
            }
        }
    }

    quickSetting?.let { setting ->
        ConfigQuickSettingDialog(
            setting = setting,
            state = state,
            events = events,
            onDismiss = { quickSetting = null },
            onFormChanged = updateForm,
        )
    }

    colorPicker?.let { request ->
        ConfigColorPickerDialog(
            initialHex = request.initialHex,
            allowDynamic = request.field == ConfigFormEvents.ColorField.SCREEN_BACKGROUND,
            initialDynamic = request.dynamic,
            onDismissRequest = onColorPickerDismiss,
            onConfirm = { value, dynamic ->
                if (request.field == ConfigFormEvents.ColorField.SCREEN_BACKGROUND) {
                    onBackgroundColorPicked(value, dynamic)
                } else {
                    onColorPicked(request.field, value)
                }
            },
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

    if (gamepadHelpVisible) {
        GamepadHelpDialog(onDismiss = onGamepadHelpDismiss)
    }
    gamepadDiagnosis?.let { diagnosis ->
        GamepadDiagnosisDialog(diagnosis, onGamepadDiagnosisDismiss)
    }
    gamepadCalibration?.let { calibration ->
        GamepadCalibrationDialog(
            state = calibration,
            onCancel = onGamepadCalibrationDismiss,
            onReset = onGamepadCalibrationReset,
            onAdvance = onGamepadCalibrationAdvance,
            onSave = onGamepadCalibrationSave,
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

    if (!isProfile) {
        PresetDialogs(
            state = state,
            events = events,
            pickerVisible = presetPickerVisible,
            saveDialogVisible = savePresetVisible,
            onPickerDismiss = { presetPickerVisible = false },
            onSaveDismiss = { savePresetVisible = false },
        )
    }

    if (discardProfileChangesVisible) {
        AlertDialog(
            onDismissRequest = { discardProfileChangesVisible = false },
            title = { Text(stringResource(R.string.preset_discard_changes_title)) },
            text = { Text(stringResource(R.string.preset_discard_changes_summary)) },
            dismissButton = {
                TextButton(onClick = { discardProfileChangesVisible = false }) {
                    Text(stringResource(R.string.preset_continue_editing))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    discardProfileChangesVisible = false
                    menuActions?.onDiscardProfileChanges()
                }) {
                    Text(stringResource(R.string.preset_discard_changes))
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
    cutoutAvailable: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    onEditSystemProperties: () -> Unit,
    onOpenShaders: () -> Unit,
    onOpenDetail: (ConfigDetail) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (destination) {
            ConfigDestination.Basic -> Unit
            ConfigDestination.Display -> {
                ScreenGeometrySection(state, form, onFormChanged, events, cutoutAvailable)
                ScreenSection(form, state, onFormChanged, events, onOpenShaders)
                ConfigSection(title = stringResource(R.string.config_display_more)) {
                    ConfigNavigationPreference(
                        title = stringResource(R.string.config_display_text),
                        value = stringResource(R.string.config_font_sizes_value,
                            form.fontSizeSmall, form.fontSizeMedium, form.fontSizeLarge),
                        onClick = { onOpenDetail(ConfigDetail.Font) },
                    )
                    ConfigNavigationPreference(
                        title = stringResource(R.string.config_display_performance),
                        value = if (form.fpsLimit == "0" || form.fpsLimit.isBlank())
                            stringResource(R.string.fps_limit_maximum)
                        else "${form.fpsLimit} FPS",
                        onClick = { onOpenDetail(ConfigDetail.Performance) },
                    )
                }
            }
            ConfigDestination.Media -> {
                AudioSection(form, state, onFormChanged, events)
                CameraSection(form, onFormChanged)
            }
            ConfigDestination.Controls -> {
                ConfigBasicInputSection(form, onFormChanged)
                InputSection(form, state, onFormChanged, events, onRequestAction)
            }
            ConfigDestination.System -> {
                SystemSection(state, form, onFormChanged, events, !isProfile, onEditSystemProperties)
                ConfigMaintenanceSection(!isProfile, onRequestAction)
            }
        }
    }
}

/** Cutout capability is derived from the current window, never persisted in a profile.
 * Reading Compose insets observes attachment/rotation. Physical rectangles also cover
 * camera holes that do not consume a display edge's safe inset.
 */
@Composable
private fun deviceHasDisplayCutout(density: androidx.compose.ui.unit.Density): Boolean {
    val cutout = WindowInsets.displayCutout
    val layoutDirection = LocalLayoutDirection.current
    val hasSafeInset = cutout.getLeft(density, layoutDirection) > 0 || cutout.getTop(density) > 0 ||
        cutout.getRight(density, layoutDirection) > 0 || cutout.getBottom(density) > 0
    val physical = ViewCompat.getRootWindowInsets(LocalView.current)?.displayCutout
    return hasSafeInset || physical?.boundingRects?.any { !it.isEmpty } == true
}

@Composable
private fun ScreenGeometrySection(
    state: ConfigUiState,
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    cutoutAvailable: Boolean,
) {
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
        if (form.screenScaleType == 0) {
            ConfigNumberPreference(
                title = stringResource(R.string.PREF_SCALE_RATIO),
                description = stringResource(R.string.config_help_scale_ratio),
                value = form.screenScaleRatio,
                fallbackLabel = "100",
                valueSuffix = "%",
                keyboardType = KeyboardType.Number,
                resetValue = "100",
                validIntegerRange = 1..1000,
                onValueChange = { value ->
                    onFormChanged(form.toBuilder().screenScaleRatio(value).build())
                },
            )
        }
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
    }
    ConfigSection(title = stringResource(R.string.config_screen_behavior)) {
        if (cutoutAvailable) {
            ConfigSwitchPreference(
                title = stringResource(R.string.config_avoid_camera_cutout_title),
                description = stringResource(R.string.config_avoid_camera_cutout_summary),
                checked = !form.allowDisplayCutout,
                onCheckedChange = { avoidCutout ->
                    onFormChanged(form.toBuilder().allowDisplayCutout(!avoidCutout).build())
                },
            )
        }
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_FORCE_FULLSCREEN),
            description = stringResource(R.string.config_help_force_fullscreen),
            checked = form.forceFullscreen,
            onCheckedChange = { checked ->
                onFormChanged(form.toBuilder().forceFullscreen(checked).build())
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
            onAdd = events::onAddResolutionPreset,
            onRemove = events::onRemoveResolutionPreset,
        )
    }
}


@Composable
private fun ConfigBasicInputSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
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
}

@Composable
private fun ConfigMaintenanceSection(
    showClearData: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_maintenance)) {
        ConfigActionPreference(
            title = stringResource(R.string.config_reset_all_settings),
            description = stringResource(R.string.config_reset_all_settings_summary),
            destructive = true,
            testTag = "config_reset_settings_action",
            onClick = { onRequestAction(ConfigAction.ResetSettings) },
        )
        if (showClearData) {
            ConfigActionPreference(
                title = stringResource(R.string.config_delete_app_data),
                description = stringResource(R.string.config_delete_app_data_summary),
                destructive = true,
                testTag = "config_clear_data_action",
                onClick = { onRequestAction(ConfigAction.ClearData) },
            )
        }
    }
}

private data class ConfigSearchItem(
    @StringRes val title: Int,
    @StringRes val section: Int,
    val destination: ConfigDestination,
    val detail: ConfigDetail? = null,
    val keywords: String = "",
)

// A small index of existing options, not a second configuration model.
private val configSearchIndex = listOf(
    ConfigSearchItem(R.string.preset_use, R.string.presets, ConfigDestination.Basic, keywords = "profile template"),
    ConfigSearchItem(R.string.preset_save_as, R.string.presets, ConfigDestination.Basic, keywords = "save profile"),
    ConfigSearchItem(R.string.config_screen_size, R.string.config_basic_display, ConfigDestination.Display, keywords = "resolution dimensions"),
    ConfigSearchItem(R.string.PREF_ORIENTATION, R.string.config_basic_display, ConfigDestination.Display, keywords = "rotation portrait landscape"),
    ConfigSearchItem(R.string.pref_screen_scale_type, R.string.config_basic_display, ConfigDestination.Display, keywords = "scale zoom"),
    ConfigSearchItem(R.string.PREF_SCALE_RATIO, R.string.config_basic_display, ConfigDestination.Display, keywords = "manual scale"),
    ConfigSearchItem(R.string.pref_screen_gravity, R.string.config_basic_display, ConfigDestination.Display, keywords = "position"),
    ConfigSearchItem(R.string.pref_screen_padding_title, R.string.config_basic_display, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_avoid_camera_cutout_title, R.string.config_basic_display, ConfigDestination.Display, keywords = "notch punch hole camera"),
    ConfigSearchItem(R.string.PREF_FORCE_FULLSCREEN, R.string.config_basic_display, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_background_mode, R.string.config_display_appearance, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_background_custom_color, R.string.config_display_appearance, ConfigDestination.Display, keywords = "background color"),
    ConfigSearchItem(R.string.pref_skin_title, R.string.config_display_appearance, ConfigDestination.Display, keywords = "skin frame bezel"),
    ConfigSearchItem(R.string.pref_graphics_mode_title, R.string.config_display_rendering, ConfigDestination.Display, keywords = "renderer OpenGL ES"),
    ConfigSearchItem(R.string.internal_resolution_title, R.string.config_display_rendering, ConfigDestination.Display, keywords = "resolution scaling"),
    ConfigSearchItem(R.string.bitmap_upscaling_title, R.string.config_display_rendering, ConfigDestination.Display, keywords = "MMPX nearest bilinear edge aware sprites"),
    ConfigSearchItem(R.string.render_shader_effects, R.string.config_display_rendering, ConfigDestination.Display, keywords = "shader GLSL effects"),
    ConfigSearchItem(R.string.render_texture_filtering, R.string.config_display_rendering, ConfigDestination.Display, keywords = "texture filtering"),
    ConfigSearchItem(R.string.parallel_screen_redrawing, R.string.config_display_rendering, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_font_sizes, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font, "font text"),
    ConfigSearchItem(R.string.SIZE_PRESETS, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_FONT_SIZE_IN_SP, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_FONT_ANTI_ALIASING, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_LIMIT_FPS, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance, "framerate fps"),
    ConfigSearchItem(R.string.perf_overlay_title, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance, "stats performance"),
    ConfigSearchItem(R.string.PREF_IMMEDIATE, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance),
    ConfigSearchItem(R.string.perf_overlay_preset, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance),
    ConfigSearchItem(R.string.perf_overlay_parameters, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance),
    ConfigSearchItem(R.string.perf_overlay_position, R.string.config_display_performance, ConfigDestination.Display, ConfigDetail.Performance),
    ConfigSearchItem(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS, R.string.config_basic_input, ConfigDestination.Controls, keywords = "virtual keys controls"),
    ConfigSearchItem(R.string.PREF_TOUCH_INPUT, R.string.config_basic_input, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_LAYOUT, R.string.config_controls_key_input, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_map_keys, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "key mapping keyboard"),
    ConfigSearchItem(R.string.config_analog_stick, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "gamepad joystick"),
    ConfigSearchItem(R.string.config_gamepad_calibrate, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "gamepad calibration"),
    ConfigSearchItem(R.string.PREF_VK_FEEDBACK, R.string.config_controls_virtual_keyboard, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_VK_HIDE_DELAY, R.string.config_controls_virtual_keyboard, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_button_shape_title, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.control_skin_title, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_VK_ALPHA, R.string.config_controls_customization, ConfigDestination.Controls, keywords = "transparency opacity"),
    ConfigSearchItem(R.string.config_controls_opaque_outside, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.config_controls_virtual_keyboard_colors, R.string.config_controls_customization, ConfigDestination.Controls, keywords = "button colors palette"),
    ConfigSearchItem(R.string.RESET_LAYOUT_CMD, R.string.config_controls_maintenance, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_soundbank_title, R.string.pref_audio_title, ConfigDestination.Media, keywords = "MIDI audio"),
    ConfigSearchItem(R.string.config_camera_default_title, R.string.config_camera_title, ConfigDestination.Media),
    ConfigSearchItem(R.string.config_camera_resolution_title, R.string.config_camera_title, ConfigDestination.Media),
    ConfigSearchItem(R.string.midlet_storage_mode_title, R.string.midlet_permissions_settings_title, ConfigDestination.System, keywords = "files storage"),
    ConfigSearchItem(R.string.midlet_permissions_read, R.string.midlet_permissions_settings_title, ConfigDestination.System),
    ConfigSearchItem(R.string.midlet_permissions_write, R.string.midlet_permissions_settings_title, ConfigDestination.System),
    ConfigSearchItem(R.string.pref_skip_resume_call, R.string.pref_title_emulation, ConfigDestination.System),
    ConfigSearchItem(R.string.PREF_TIMING_MODE, R.string.config_system_timing, ConfigDestination.System),
    ConfigSearchItem(R.string.pref_encoding_title, R.string.PREF_SYS_PROPS, ConfigDestination.System),
    ConfigSearchItem(R.string.config_edit_system_properties, R.string.PREF_SYS_PROPS, ConfigDestination.System, keywords = "properties CLDC Java ME"),
    ConfigSearchItem(R.string.config_reset_all_settings, R.string.config_maintenance, ConfigDestination.System),
    ConfigSearchItem(R.string.config_delete_app_data, R.string.config_maintenance, ConfigDestination.System, keywords = "clear data"),
)

@Composable
private fun ConfigSearchResults(
    query: String,
    isProfile: Boolean,
    onSelected: (ConfigDestination, String, ConfigDetail?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val suggestions = setOf(
        R.string.config_screen_size, R.string.PREF_ORIENTATION,
        R.string.pref_graphics_mode_title, R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
        R.string.pref_map_keys, R.string.PREF_TIMING_MODE,
    )
    val filtered = configSearchIndex.filter { entry ->
        if (isProfile && entry.destination == ConfigDestination.Basic) return@filter false
        if (query.isBlank()) entry.title in suggestions
        else resources.getString(entry.title).contains(query.trim(), ignoreCase = true) ||
            resources.getString(entry.section).contains(query.trim(), ignoreCase = true) ||
            entry.keywords.contains(query.trim(), ignoreCase = true)
    }
    LazyColumn(modifier = modifier.imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (query.isBlank()) {
            item {
                Text(stringResource(R.string.config_search_suggestions),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(stringResource(R.string.config_search_no_results),
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(filtered, key = { _, entry -> entry.title }) { _, entry ->
            ListItem(
                headlineContent = { Text(stringResource(entry.title)) },
                supportingContent = {
                    Text(stringResource(entry.destination.label) + " · " +
                        stringResource(entry.section))
                },
                modifier = Modifier.clickable {
                    onSelected(entry.destination, resources.getString(entry.section), entry.detail)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDetailPage(
    detail: ConfigDetail,
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(
                        if (detail == ConfigDetail.Font) R.string.config_font_page_title
                        else R.string.config_performance_page_title,
                    ))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 880.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (detail) {
                    ConfigDetail.Font -> FontSection(form, state, onFormChanged)
                    ConfigDetail.Performance -> PerformanceSection(form, onFormChanged)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTopBar(
    title: String,
    isProfile: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    searchVisible: Boolean,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    if (searchVisible) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Surface(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
            color = MaterialTheme.colorScheme.surface,
        ) {
            io.github.h3nb.jlmodplus.applist.LibrarySearchField(
                query = searchQuery,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                onDismissInput = { focusManager.clearFocus() },
                onClose = onCloseSearch,
                placeholder = stringResource(R.string.config_search_hint),
                autoFocus = true,
            )
        }
    } else {
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
                    Icon(painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                IconButton(onClick = onSearch) {
                    Icon(painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.config_search_title))
                }
                if (isProfile) {
                    TextButton(onClick = onSave, enabled = saveEnabled) {
                        Text(stringResource(R.string.save))
                    }
                } else {
                    IconButton(onClick = onStart) {
                        Icon(painterResource(R.drawable.ic_play),
                            contentDescription = stringResource(R.string.START_CMD))
                    }
                }
            },
        )
    }
}

@Composable
private fun ScreenSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
    onOpenShaders: () -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_display_appearance)) {
        val backgroundModes = listOf(
            stringResource(R.string.config_background_custom),
            stringResource(R.string.config_background_theme),
            stringResource(R.string.config_background_immersive),
        )
        val backgroundMode = BackgroundMode.sanitize(form.screenBackgroundMode)
        val backgroundDescription = when (backgroundMode) {
            BackgroundMode.THEME -> stringResource(R.string.config_background_theme_help)
            BackgroundMode.IMMERSIVE -> stringResource(R.string.config_background_immersive_help)
            BackgroundMode.DYNAMIC_SOLID -> stringResource(R.string.config_background_dynamic_help)
            else -> stringResource(R.string.config_help_background)
        }
        ConfigChoicePreference(
            title = stringResource(R.string.config_background_mode),
            description = backgroundDescription,
            selected = backgroundModes[if (backgroundMode == BackgroundMode.DYNAMIC_SOLID) 0 else backgroundMode],
            options = backgroundModes,
            onSelected = { index ->
                onFormChanged(form.toBuilder().screenBackgroundMode(index).build())
            },
        )
        if (backgroundMode == BackgroundMode.CUSTOM || backgroundMode == BackgroundMode.DYNAMIC_SOLID) {
            ConfigColorPreference(
                title = stringResource(R.string.config_background_custom_color),
                description = stringResource(
                    if (backgroundMode == BackgroundMode.DYNAMIC_SOLID)
                        R.string.config_background_dynamic_help else R.string.config_help_background_custom_color
                ),
                value = form.screenBackground,
                displayLabel = if (backgroundMode == BackgroundMode.DYNAMIC_SOLID)
                    stringResource(R.string.config_background_dynamic) else null,
                onClick = { events.onColorPicker(ConfigFormEvents.ColorField.SCREEN_BACKGROUND) },
            )
        }
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
            onImport = { events.onImportAsset(ConfigFormEvents.AssetKind.SKIN) },
            onRename = { old, stem -> events.onRenameAsset(ConfigFormEvents.AssetKind.SKIN, old, stem) },
            onDelete = { name -> events.onDeleteAsset(ConfigFormEvents.AssetKind.SKIN, name) },
        )
    }
    ConfigSection(title = stringResource(R.string.config_display_rendering)) {
        val graphicsOptions = stringArrayResource(R.array.pref_graphics_mode_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_graphics_mode_title),
            description = stringResource(R.string.config_help_graphics_mode),
            selected = graphicsOptions.getOrElse(form.graphicsMode) { graphicsOptions.firstOrNull().orEmpty() },
            options = graphicsOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().graphicsMode(index).build()) },
        )
        val internalScaleOptions = listOf(stringResource(R.string.internal_resolution_auto)) +
            (1..10).map { "${it}×" }
        ConfigChoicePreference(
            title = stringResource(R.string.internal_resolution_title),
            description = stringResource(R.string.internal_resolution_description),
            selected = internalScaleOptions.getOrElse(form.internalResolutionScale) { internalScaleOptions[1] },
            options = internalScaleOptions,
            onSelected = { index -> onFormChanged(form.toBuilder().internalResolutionScale(index).build()) },
        )
        if (form.internalResolutionScale != 1) {
            val bitmapOptions = listOf(
                stringResource(R.string.bitmap_upscaling_nearest),
                stringResource(R.string.bitmap_upscaling_bilinear),
                stringResource(R.string.bitmap_upscaling_mmpx),
                stringResource(R.string.bitmap_upscaling_edge_aware),
            )
            ConfigChoicePreference(
                title = stringResource(R.string.bitmap_upscaling_title),
                description = stringResource(R.string.bitmap_upscaling_description),
                selected = bitmapOptions.getOrElse(form.bitmapUpscalingMode) { bitmapOptions[0] },
                options = bitmapOptions,
                onSelected = { index ->
                    onFormChanged(form.toBuilder().bitmapUpscalingMode(index).build())
                },
            )
        }
        if (form.graphicsMode == 1) {
            ConfigValuePreference(
                title = stringResource(R.string.render_shader_effects),
                description = stringResource(R.string.render_shader_picker_help),
                value = selectedShaderLabel(state),
                onClick = onOpenShaders,
            )
        }
        if (isTextureFilteringApplicable(state)) {
            val filterOptions = listOf(
                stringResource(R.string.render_custom_nearest),
                stringResource(R.string.render_custom_linear),
            )
            ConfigChoicePreference(
                title = stringResource(R.string.render_texture_filtering),
                description = stringResource(R.string.render_texture_filtering_help),
                selected = filterOptions[if (form.screenFilter) 1 else 0],
                options = filterOptions,
                onSelected = { index ->
                    onFormChanged(form.toBuilder().screenFilter(index == 1).build())
                },
            )
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
}

@Composable
private fun PerformanceSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_display_performance)) {
        ConfigNumberPreference(
            title = stringResource(R.string.PREF_LIMIT_FPS),
            description = stringResource(R.string.config_help_fps_limit),
            value = form.fpsLimit,
            fallbackLabel = stringResource(R.string.fps_limit_maximum),
            keyboardType = KeyboardType.Number,
            onValueChange = { value -> onFormChanged(form.toBuilder().fpsLimit(value).build()) },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.perf_overlay_title),
            description = stringResource(R.string.perf_overlay_help),
            checked = form.showFps,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showFps(checked).build()) },
        )
        if (form.showFps) {
            PerformanceOverlayPreferences(form, onFormChanged)
        }
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_IMMEDIATE),
            description = stringResource(R.string.config_help_immediate),
            checked = form.immediateMode,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().immediateMode(checked).build()) },
        )
    }
}

@Composable
internal fun PerformanceOverlayPreferences(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    var parametersVisible by rememberSaveable { mutableStateOf(false) }
    val presetNames = listOf(
        stringResource(R.string.perf_overlay_minimal),
        stringResource(R.string.perf_overlay_standard),
        stringResource(R.string.perf_overlay_debug),
        stringResource(R.string.perf_overlay_custom),
    )
    val presetMasks = listOf(
        PerformanceOverlayOptions.MINIMAL,
        PerformanceOverlayOptions.STANDARD,
        PerformanceOverlayOptions.DEBUG,
    )
    val presetIndex = presetMasks.indexOf(form.performanceOverlayMetrics).let { if (it < 0) 3 else it }
    ConfigChoicePreference(
        title = stringResource(R.string.perf_overlay_preset),
        description = stringResource(R.string.perf_overlay_preset_help),
        selected = presetNames[presetIndex],
        options = presetNames,
        onSelected = { index ->
            if (index == 3) {
                parametersVisible = true
            } else {
                onFormChanged(form.toBuilder().performanceOverlayMetrics(presetMasks[index]).build())
            }
        },
    )
    val selectedCount = Integer.bitCount(form.performanceOverlayMetrics)
    ConfigValuePreference(
        title = stringResource(R.string.perf_overlay_parameters),
        description = stringResource(R.string.perf_overlay_parameters_help),
        value = pluralStringResource(R.plurals.perf_overlay_selected, selectedCount, selectedCount),
        onClick = { parametersVisible = true },
    )
    val positions = listOf(
        stringResource(R.string.perf_overlay_top_left),
        stringResource(R.string.perf_overlay_top_right),
        stringResource(R.string.perf_overlay_bottom_left),
        stringResource(R.string.perf_overlay_bottom_right),
    )
    ConfigChoicePreference(
        title = stringResource(R.string.perf_overlay_position),
        description = stringResource(R.string.perf_overlay_position_help),
        selected = positions[form.performanceOverlayPosition],
        options = positions,
        onSelected = { index ->
            onFormChanged(form.toBuilder().performanceOverlayPosition(index).build())
        },
    )
    if (parametersVisible) {
        PerformanceOverlayParametersDialog(
            selectedMetrics = form.performanceOverlayMetrics,
            onMetricsChanged = { mask ->
                onFormChanged(form.toBuilder().performanceOverlayMetrics(mask).build())
            },
            onDismissRequest = { parametersVisible = false },
        )
    }
}

private data class OverlayParameter(
    val bit: Int,
    val title: Int,
)

private data class OverlayParameterGroup(val title: Int, val parameters: List<OverlayParameter>)

private val overlayParameterGroups = listOf(
    OverlayParameterGroup(R.string.perf_overlay_group_rate, listOf(
        OverlayParameter(PerformanceOverlayOptions.FPS, R.string.perf_overlay_fps),
        OverlayParameter(PerformanceOverlayOptions.CAP, R.string.perf_overlay_cap),
        OverlayParameter(PerformanceOverlayOptions.GUEST_FPS, R.string.perf_overlay_guest_fps),
        OverlayParameter(PerformanceOverlayOptions.SPEED, R.string.perf_overlay_speed),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_timing, listOf(
        OverlayParameter(PerformanceOverlayOptions.FRAME_INTERVAL, R.string.perf_overlay_interval),
        OverlayParameter(PerformanceOverlayOptions.P95_INTERVAL, R.string.perf_overlay_p95),
        OverlayParameter(PerformanceOverlayOptions.MAX_INTERVAL, R.string.perf_overlay_max),
        OverlayParameter(PerformanceOverlayOptions.RENDER_INTERVAL, R.string.perf_overlay_render_interval),
        OverlayParameter(PerformanceOverlayOptions.RENDER_P95_INTERVAL, R.string.perf_overlay_render_p95),
        OverlayParameter(PerformanceOverlayOptions.RENDER_MAX_INTERVAL, R.string.perf_overlay_render_max),
        OverlayParameter(PerformanceOverlayOptions.COALESCED, R.string.perf_overlay_coalesced),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_pipeline, listOf(
        OverlayParameter(PerformanceOverlayOptions.PAINT, R.string.perf_overlay_paint),
        OverlayParameter(PerformanceOverlayOptions.COPY, R.string.perf_overlay_copy),
        OverlayParameter(PerformanceOverlayOptions.SUBMIT, R.string.perf_overlay_submit),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_queue, listOf(
        OverlayParameter(PerformanceOverlayOptions.INPUT_QUEUE, R.string.perf_overlay_input_queue),
        OverlayParameter(PerformanceOverlayOptions.FRAME_QUEUE, R.string.perf_overlay_frame_queue),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_resources, listOf(
        OverlayParameter(PerformanceOverlayOptions.CPU, R.string.perf_overlay_cpu),
        OverlayParameter(PerformanceOverlayOptions.RAM, R.string.perf_overlay_ram),
        OverlayParameter(PerformanceOverlayOptions.JAVA_HEAP, R.string.perf_overlay_java),
        OverlayParameter(PerformanceOverlayOptions.NATIVE_HEAP, R.string.perf_overlay_native),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_runtime, listOf(
        OverlayParameter(PerformanceOverlayOptions.RENDERER, R.string.perf_overlay_renderer),
        OverlayParameter(PerformanceOverlayOptions.DISPLAY, R.string.perf_overlay_display),
        OverlayParameter(PerformanceOverlayOptions.THERMAL, R.string.perf_overlay_thermal),
    )),
)

@Composable
internal fun PerformanceOverlayParametersDialog(
    selectedMetrics: Int,
    onMetricsChanged: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val listState = rememberLazyListState()
    val canScrollForward = rememberLazyListCanScrollForward(listState)
    val maxHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        onDismissRequest = onDismissRequest,
        textScrollable = false,
        title = { Text(stringResource(R.string.perf_overlay_parameters)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    overlayParameterGroups.forEach { group ->
                        item(key = "group_${group.title}") {
                            Text(
                                stringResource(group.title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(group.parameters, key = { _, parameter -> parameter.bit }) { _, parameter ->
                            val checked = selectedMetrics and parameter.bit != 0
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("perf_metric_${parameter.bit}")
                                    .heightIn(min = 48.dp)
                                    .toggleable(value = checked, role = Role.Checkbox) { selected ->
                                        onMetricsChanged(
                                            if (selected) selectedMetrics or parameter.bit
                                            else selectedMetrics and parameter.bit.inv(),
                                        )
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    stringResource(parameter.title),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    item(key = "bottom_space") { Spacer(Modifier.height(20.dp)) }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.perf_overlay_done)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenPresetDialog(
    presets: List<Size>,
    removablePresets: List<Size>,
    selectedPreset: Size?,
    onDismissRequest: () -> Unit,
    onSelected: (Size) -> Unit,
    onAdd: (Size) -> Unit,
    onRemove: (Size) -> Unit,
    useModalBottomSheet: Boolean = true,
) {
    var swapped by rememberSaveable { mutableStateOf(false) }
    var customResolutionVisible by rememberSaveable { mutableStateOf(false) }
    val swapToggleDescription = stringResource(R.string.config_swap_screen_size)
    val dialogLayout = adaptiveDialogLayout()
    val availablePresets = if (selectedPreset != null && !presets.contains(selectedPreset)) {
        listOf(selectedPreset) + presets
    } else {
        presets
    }

    val dialogContent: @Composable () -> Unit = {
        Surface(
            modifier = dialogLayout.modifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.config_select_screen_size),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (selectedPreset != null) {
                            Text(
                                text = stringResource(
                                    R.string.config_current_screen_size,
                                    selectedPreset.toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconToggleButton(
                        checked = swapped,
                        onCheckedChange = { swapped = it },
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = swapToggleDescription
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap_horiz),
                            contentDescription = null,
                        )
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = when {
                            maxWidth >= 480.dp -> 3
                            maxWidth >= 250.dp -> 2
                            else -> 1
                        }
                        val tileWidth = (maxWidth - 8.dp * (columns - 1)) / columns
                        FlowRow(
                            maxItemsInEachRow = columns,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availablePresets.forEach { source ->
                                val display = if (swapped) Size(source.height, source.width) else source
                                val selected = display == selectedPreset
                                Surface(
                                    modifier = Modifier
                                        .width(tileWidth)
                                        .heightIn(min = 56.dp)
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = {
                                                onSelected(display)
                                                onDismissRequest()
                                            },
                                        ),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = if (selected) BorderStroke(
                                        1.dp, MaterialTheme.colorScheme.primary,
                                    ) else null,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = display.toString(),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (removablePresets.contains(source)) {
                                            IconButton(
                                                modifier = Modifier.size(40.dp),
                                                onClick = { onRemove(source) },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_delete),
                                                    contentDescription = stringResource(R.string.remove_screen_preset),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { customResolutionVisible = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.config_custom_resolution))
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }

    if (!customResolutionVisible) {
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
    } else {
        CustomResolutionDialog(
            initialSize = selectedPreset,
            onDismissRequest = { customResolutionVisible = false },
            onSave = { size ->
                customResolutionVisible = false
                onAdd(size)
                onSelected(size)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun ConfigDialogBody(
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The shared dialog owns scrolling and measures the space left by its title/actions.
    Column(Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement, content = content)
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
            ConfigDialogBody(
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
                            painter = painterResource(R.drawable.ic_swap_horiz),
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
                    description = stringResource(R.string.config_help_lock_custom_resolution_ratio),
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
    val useHorizontalFields = adaptiveDialogLayout().width >=
        600.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_font_sizes)) },
        text = {
            ConfigDialogBody(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_help_font_sizes_long),
                    style = MaterialTheme.typography.bodyMedium,
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
    state: ConfigUiState,
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
            compact = true,
            onSelected = { index -> onFormChanged(form.toBuilder().keyCodesLayout(index).build()) },
        )
        ConfigCompactValuePreference(
            title = stringResource(R.string.pref_map_keys),
            description = stringResource(R.string.config_help_key_mapping),
            onClick = events::onKeyMappings,
        )
        GamepadInputPreferences(
            form = form,
            controllerAvailable = state.controllerAvailable,
            onFormChanged = onFormChanged,
            events = events,
            compact = true,
        )
    }
    ConfigSection(title = stringResource(R.string.config_controls_virtual_keyboard)) {
        if (!form.showKeyboard) {
            Text(
                text = stringResource(R.string.config_controls_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_VK_FEEDBACK),
            description = stringResource(R.string.config_help_vk_feedback),
            checked = form.vkFeedback,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkFeedback(checked).build()) },
        )
        ConfigNumberPreference(
            title = stringResource(R.string.PREF_VK_HIDE_DELAY),
            description = stringResource(R.string.config_help_vk_hide_delay),
            value = form.vkHideDelay,
            fallbackLabel = stringResource(R.string.pref_vk_hide_hint),
            valueSuffix = stringResource(R.string.PREF_UNIT_MS),
            keyboardType = KeyboardType.Number,
            compact = true,
            onValueChange = { value -> onFormChanged(form.toBuilder().vkHideDelay(value).build()) },
        )
    }
    ConfigSection(title = stringResource(R.string.config_controls_customization)) {
        val shapeOptions = stringArrayResource(R.array.pref_button_shape_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_button_shape_title),
            description = stringResource(R.string.config_help_vk_button_shape),
            selected = shapeOptions.getOrElse(form.vkButtonShape) { shapeOptions.firstOrNull().orEmpty() },
            options = shapeOptions,
            compact = true,
            onSelected = { index -> onFormChanged(form.toBuilder().vkButtonShape(index).build()) },
        )
        ConfigCompactValuePreference(
            title = stringResource(R.string.control_skin_title),
            description = stringResource(R.string.config_controls_skin_help),
            value = state.controlSkinName.ifBlank { stringResource(R.string.control_skin_default) },
            onClick = events::onManageControlSkins,
        )
        val displayedOpacity = (form.vkAlpha.coerceIn(0, 255) * 100f / 255f).roundToInt()
        ConfigSliderPreference(
            title = stringResource(R.string.PREF_VK_ALPHA),
            description = stringResource(R.string.config_controls_opacity_help),
            value = displayedOpacity,
            valueRange = 0..100,
            compact = true,
            valueSuffix = "%",
            onSelected = { percent ->
                // Preserve the precise stored value when a rounded percentage was not changed.
                if (percent != displayedOpacity) {
                    onFormChanged(form.toBuilder().vkAlpha((percent * 255f / 100f).roundToInt()).build())
                }
            },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.config_controls_opaque_outside),
            description = stringResource(R.string.config_controls_opaque_outside_help),
            checked = form.vkForceOpacity,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().vkForceOpacity(checked).build()) },
        )
        ConfigControlsColorOptions(form, events::onColorPicker)
    }
    ConfigSection(title = stringResource(R.string.config_controls_maintenance)) {
        ConfigCompactValuePreference(
            title = stringResource(R.string.RESET_LAYOUT_CMD),
            description = stringResource(R.string.config_help_reset_layout),
            destructive = true,
            onClick = { onRequestAction(ConfigAction.ResetLayout) },
        )
    }
}

@Composable
internal fun VirtualControlsColorPreview(form: ConfigFormState) {
    var pressed by rememberSaveable { mutableStateOf(false) }
    val background = parseColor(form.vkBackground)
    val foreground = parseColor(form.vkForeground)
    val selectedBackground = parseColor(form.vkSelectedBackground)
    val selectedForeground = parseColor(form.vkSelectedForeground)
    val outline = parseColor(form.vkOutline)
    val previewOpacity = form.vkAlpha.coerceIn(0, 255) / 255f
    val keyShape = when (form.vkButtonShape) {
        0 -> CircleShape
        1 -> RectangleShape
        else -> MaterialTheme.shapes.small
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_controls_color_preview")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.config_virtual_controls_color_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(
                selected = !pressed,
                onClick = { pressed = false },
                label = { Text(stringResource(R.string.config_virtual_controls_preview_normal)) },
            )
            FilterChip(
                selected = pressed,
                onClick = { pressed = true },
                label = { Text(stringResource(R.string.config_virtual_controls_preview_pressed)) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(previewOpacity),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 52.dp, height = 40.dp),
                shape = keyShape,
                color = if (pressed) selectedBackground else background,
                border = BorderStroke(0.5.dp, outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "L",
                        color = if (pressed) selectedForeground else foreground,
                    )
                }
            }
            DpadColorSample(
                background, foreground, selectedBackground, selectedForeground,
                outline, pressed,
            )
            AnalogColorSample(
                background, selectedBackground, outline, pressed,
            )
        }
    }
}

@Composable
private fun DpadColorSample(
    background: Color,
    foreground: Color,
    selectedBackground: Color,
    selectedForeground: Color,
    outline: Color,
    pressed: Boolean,
) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy)
        val a = r * 0.34f
        val corner = r * 0.16f
        val inset = r * 0.09f
        val cross = Path().apply {
            moveTo(cx - a + corner, cy - r)
            lineTo(cx + a - corner, cy - r)
            quadraticBezierTo(cx + a, cy - r, cx + a, cy - r + corner)
            lineTo(cx + a, cy - a - inset)
            quadraticBezierTo(cx + a, cy - a, cx + a + inset, cy - a)
            lineTo(cx + r - corner, cy - a)
            quadraticBezierTo(cx + r, cy - a, cx + r, cy - a + corner)
            lineTo(cx + r, cy + a - corner)
            quadraticBezierTo(cx + r, cy + a, cx + r - corner, cy + a)
            lineTo(cx + a + inset, cy + a)
            quadraticBezierTo(cx + a, cy + a, cx + a, cy + a + inset)
            lineTo(cx + a, cy + r - corner)
            quadraticBezierTo(cx + a, cy + r, cx + a - corner, cy + r)
            lineTo(cx - a + corner, cy + r)
            quadraticBezierTo(cx - a, cy + r, cx - a, cy + r - corner)
            lineTo(cx - a, cy + a + inset)
            quadraticBezierTo(cx - a, cy + a, cx - a - inset, cy + a)
            lineTo(cx - r + corner, cy + a)
            quadraticBezierTo(cx - r, cy + a, cx - r, cy + a - corner)
            lineTo(cx - r, cy - a + corner)
            quadraticBezierTo(cx - r, cy - a, cx - r + corner, cy - a)
            lineTo(cx - a - inset, cy - a)
            quadraticBezierTo(cx - a, cy - a, cx - a, cy - a - inset)
            lineTo(cx - a, cy - r + corner)
            quadraticBezierTo(cx - a, cy - r, cx - a + corner, cy - r)
            close()
        }
        drawPath(cross, background)
        if (pressed) {
            val inset = r * 0.045f
            drawRoundRect(
                selectedBackground,
                topLeft = Offset(cx - a + inset, cy - r + inset),
                size = ComposeSize(2f * (a - inset), r - inset - r * 0.235f),
                cornerRadius = CornerRadius(r * 0.095f),
            )
        }
        drawPath(cross, outline, style = Stroke(0.5.dp.toPx()))
        drawCircle(background, r * 0.175f, Offset(cx, cy))
        drawCircle(
            lerp(background, outline, 0.45f),
            r * 0.175f, Offset(cx, cy),
            style = Stroke(0.5.dp.toPx()),
        )

        val arrow = r * 0.11f
        for (direction in 0..3) {
            val x = when (direction) {
                2 -> cx - r * 0.61f
                3 -> cx + r * 0.61f
                else -> cx
            }
            val y = when (direction) {
                0 -> cy - r * 0.61f
                1 -> cy + r * 0.61f
                else -> cy
            }
            val triangle = Path().apply {
                when (direction) {
                    0 -> {
                        moveTo(x, y - arrow)
                        lineTo(x - arrow, y + arrow)
                        lineTo(x + arrow, y + arrow)
                    }
                    1 -> {
                        moveTo(x, y + arrow)
                        lineTo(x - arrow, y - arrow)
                        lineTo(x + arrow, y - arrow)
                    }
                    2 -> {
                        moveTo(x - arrow, y)
                        lineTo(x + arrow, y - arrow)
                        lineTo(x + arrow, y + arrow)
                    }
                    else -> {
                        moveTo(x + arrow, y)
                        lineTo(x - arrow, y - arrow)
                        lineTo(x - arrow, y + arrow)
                    }
                }
                close()
            }
            drawPath(triangle, if (pressed && direction == 0) selectedForeground else foreground)
        }
    }
}

@Composable
private fun AnalogColorSample(
    background: Color,
    selectedBackground: Color,
    outline: Color,
    pressed: Boolean,
) {
    val backgroundRgb = background.toArgb() and 0xFFFFFF
    val thumbRgb = VirtualControlAppearance.analogThumbColor(
        backgroundRgb, selectedBackground.toArgb() and 0xFFFFFF, pressed,
    )
    fun opaque(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    Canvas(modifier = Modifier.size(72.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val shellRadius = radius * 0.94f
        val thumbRadius = radius * 0.45f
        val outlineStroke = Stroke(0.5.dp.toPx())

        // The palette and circle proportions mirror the runtime renderer.
        drawCircle(
            brush = Brush.linearGradient(
                listOf(
                    opaque(VirtualControlAppearance.analogShellTop(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogShell(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogShellBottom(backgroundRgb)),
                ),
                start = Offset(center.x - shellRadius, center.y - shellRadius),
                end = Offset(center.x + shellRadius, center.y + shellRadius),
            ),
            radius = shellRadius,
            center = center,
        )
        drawCircle(outline, shellRadius, center, style = outlineStroke)
        drawCircle(
            opaque(VirtualControlAppearance.analogShellRim(backgroundRgb)).copy(alpha = 0.2f),
            radius * 0.915f, center, style = outlineStroke,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    opaque(VirtualControlAppearance.analogBowlCenter(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogBowlEdge(backgroundRgb)),
                ),
                center = center,
                radius = radius * 0.70f,
            ),
            radius = radius * 0.70f,
            center = center,
        )
        drawCircle(
            opaque(VirtualControlAppearance.analogThumbShadow(backgroundRgb)).copy(alpha = 0x5A / 255f),
            thumbRadius * 1.055f,
            Offset(center.x, center.y + thumbRadius * 0.045f),
        )
        drawCircle(opaque(VirtualControlAppearance.analogThumbRim(thumbRgb)),
            thumbRadius * 1.035f, center)
        drawCircle(
            brush = Brush.linearGradient(
                listOf(
                    opaque(VirtualControlAppearance.analogThumbTop(thumbRgb)),
                    opaque(thumbRgb),
                    opaque(VirtualControlAppearance.analogThumbBottom(thumbRgb)),
                ),
                start = Offset(center.x - thumbRadius, center.y - thumbRadius),
                end = Offset(center.x + thumbRadius, center.y + thumbRadius),
            ),
            radius = thumbRadius,
            center = center,
        )
        drawCircle(
            opaque(VirtualControlAppearance.analogThumbRim(thumbRgb)).copy(alpha = 0.33f),
            thumbRadius * 0.96f, center, style = outlineStroke,
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
    events: ConfigFormEvents,
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
            onImport = { events.onImportAsset(ConfigFormEvents.AssetKind.SOUNDBANK) },
            onRename = { old, stem -> events.onRenameAsset(ConfigFormEvents.AssetKind.SOUNDBANK, old, stem) },
            onDelete = { name -> events.onDeleteAsset(ConfigFormEvents.AssetKind.SOUNDBANK, name) },
        )
    }
}

@Composable
private fun CameraSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_camera_title)) {
        val facingValues = listOf(CameraCaptureOptions.BACK, CameraCaptureOptions.FRONT)
        val facingOptions = listOf(
            stringResource(R.string.config_camera_rear),
            stringResource(R.string.config_camera_front),
        )
        ConfigChoicePreference(
            title = stringResource(R.string.config_camera_default_title),
            description = null,
            selected = facingOptions[if (form.cameraDefaultFacing == CameraCaptureOptions.FRONT) 1 else 0],
            options = facingOptions,
            onSelected = { index ->
                onFormChanged(form.toBuilder().cameraDefaultFacing(facingValues[index]).build())
            },
        )
        val resolutionOptions = listOf(
            stringResource(R.string.config_camera_resolution_auto),
            stringResource(R.string.config_camera_resolution_portrait),
            stringResource(R.string.config_camera_resolution_landscape),
        )
        ConfigChoicePreference(
            title = stringResource(R.string.config_camera_resolution_title),
            description = null,
            selected = resolutionOptions.getOrElse(form.cameraResolution) { resolutionOptions[0] },
            options = resolutionOptions,
            onSelected = { index ->
                onFormChanged(form.toBuilder().cameraResolution(index).build())
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
    onEditSystemProperties: () -> Unit,
) {
    if (showClearData) {
        ConfigSection(title = stringResource(R.string.midlet_permissions_settings_title)) {
            val access = state.fileAccess
            val modes = org.microemu.cldc.file.StorageMode.values().toList()
            val modeOptions = listOf(
                stringResource(R.string.midlet_storage_mode_shared),
                stringResource(R.string.midlet_storage_mode_android),
            )
            ConfigChoicePreference(
                title = stringResource(R.string.midlet_storage_mode_title),
                description = stringResource(if (access?.mode == org.microemu.cldc.file.StorageMode.ANDROID)
                    R.string.midlet_storage_mode_android_summary else R.string.midlet_storage_mode_shared_summary),
                selected = access?.mode?.let { modeOptions[modes.indexOf(it)] }
                    ?: stringResource(R.string.loading_info),
                options = modeOptions,
                enabled = access != null,
                onSelected = { modes.getOrNull(it)?.let(events::onStorageModeChanged) },
            )
            val options = listOf(
                stringResource(R.string.midlet_permissions_ask),
                stringResource(R.string.midlet_permissions_allow),
                stringResource(R.string.midlet_permissions_deny),
            )
            listOf(false, true).forEach { write ->
                val decision = if (write) access?.write else access?.read
                ConfigChoicePreference(
                    title = stringResource(if (write) R.string.midlet_permissions_write else R.string.midlet_permissions_read),
                    description = if (write) stringResource(R.string.midlet_permissions_write_summary) else null,
                    selected = decision?.let { options.getOrElse(it) { options[0] } }
                        ?: stringResource(R.string.loading_info),
                    options = options,
                    enabled = access != null,
                    onSelected = { events.onFilePermissionChanged(write, it) },
                )
            }
        }
    }
    EmulationSection(form, onFormChanged)
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

    if (showClearData) {
        state.fileAccess?.legacyFiles?.takeIf { it.isNotEmpty() }?.let { files ->
            var importVisible by rememberSaveable { mutableStateOf(false) }
            ConfigSection(title = stringResource(R.string.midlet_legacy_import_title)) {
                ConfigActionPreference(
                    title = stringResource(R.string.midlet_legacy_import_action),
                    description = stringResource(R.string.midlet_legacy_import_summary),
                    onClick = { importVisible = true },
                )
            }
            if (importVisible) {
                val listState = rememberLazyListState()
                val canScrollForward = rememberLazyListCanScrollForward(listState)
                AlertDialog(
                    onDismissRequest = { importVisible = false },
                    title = { Text(stringResource(R.string.midlet_legacy_import_title)) },
                    textScrollable = false,
                    text = {
                        Box(Modifier.fillMaxWidth()) {
                            LazyColumn(state = listState) {
                                item { Text(stringResource(R.string.midlet_legacy_import_summary)) }
                                itemsIndexed(files, key = { _, relative -> relative }) { _, relative ->
                                    TextButton(onClick = {
                                        importVisible = false
                                        events.onImportLegacyPrivateFile(relative)
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text(relative, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            ScrollableContentHint(canScrollForward, Modifier.align(Alignment.BottomCenter))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { importVisible = false }) { Text(stringResource(android.R.string.cancel)) }
                    },
                )
            }
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
    defaultValue: String = "",
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    var resetConfirmationVisible by rememberSaveable { mutableStateOf(false) }

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
            if (defaultValue.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { resetConfirmationVisible = true },
                        enabled = draft != defaultValue,
                    ) {
                        Text(stringResource(R.string.config_system_properties_reset))
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }
    if (resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { resetConfirmationVisible = false },
            title = { Text(stringResource(R.string.config_system_properties_reset_title)) },
            text = { Text(stringResource(R.string.config_system_properties_reset_message)) },
            dismissButton = {
                TextButton(onClick = { resetConfirmationVisible = false }) {
                    Text(stringResource(R.string.CANCEL_CMD))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = defaultValue
                    resetConfirmationVisible = false
                }) {
                    Text(stringResource(R.string.config_system_properties_reset))
                }
            },
        )
    }
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
            .heightIn(min = 48.dp)
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
    resetValue: String? = null,
    validIntegerRange: IntRange? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val canConfirm = validIntegerRange == null ||
        draft.toIntOrNull()?.let { it in validIntegerRange } == true
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            ConfigDialogBody(
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
            Row {
                if (resetValue != null) {
                    TextButton(onClick = { draft = resetValue }) {
                        Text(stringResource(R.string.reset))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = canConfirm) {
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
            .heightIn(min = 48.dp)
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
            ConfigDialogBody(
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
            .heightIn(min = 48.dp)
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
    onImport: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val maxListHeight = adaptiveDialogLayout().maxHeight
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
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
                                trailingContent = {
                                    if (index > 0 && (onRename != null || onDelete != null)) {
                                        var expanded by remember(option) { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { expanded = true }) {
                                                Icon(
                                                    painterResource(R.drawable.ic_more_vert),
                                                    contentDescription = stringResource(R.string.profile_more_actions, option),
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false },
                                            ) {
                                                if (onRename != null) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.action_context_rename)) },
                                                        onClick = {
                                                            expanded = false
                                                            onRename(option)
                                                        },
                                                    )
                                                }
                                                if (onDelete != null) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.action_context_delete)) },
                                                        onClick = {
                                                            expanded = false
                                                            onDelete(option)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == selected,
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
                if (onImport != null) {
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.config_asset_add))
                    }
                    Text(
                        text = stringResource(R.string.config_asset_copy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = null,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ConfigSwitchPreference(
        title = title,
        description = description,
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

private fun parseColor(value: String): Color {
    val parsed = value.toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or parsed).toInt())
}
