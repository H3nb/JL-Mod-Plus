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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.input.HostCommand
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import javax.microedition.shell.timing.TimingMode
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.mmapi.camera.CameraCaptureOptions
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.TransientNoticeHost
import io.github.h3nb.jlmodplus.ui.TransientNoticeState
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
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
    private val notices = TransientNoticeState()

    init {
        composeView.id = R.id.config_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                Box(Modifier.fillMaxSize()) {
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
                    TransientNoticeHost(notices, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    fun update(nextState: ConfigUiState) {
        state = nextState
    }

    @JvmOverloads
    fun showNotice(message: String, durationMillis: Long = 3_500L) {
        notices.show(message, durationMillis)
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
    Graphics(R.string.config_destination_rendering, R.drawable.ic_runtime_fps),
    Controls(R.string.config_destination_controls, R.drawable.ic_config_controls),
    Media(R.string.config_destination_media, R.drawable.ic_config_media),
    System(R.string.config_destination_system, R.drawable.ic_settings),
}

internal enum class ConfigAction(val title: Int, val message: Int) {
    ClearData(R.string.config_delete_app_data, R.string.config_message_delete_app_data),
    ResetSettings(R.string.config_reset_all_settings, R.string.config_message_reset_settings),
    ResetLayout(R.string.RESET_LAYOUT_CMD, R.string.message_reset_layout),
}

internal enum class ConfigDetail { Font, Performance }

private enum class ConfigChildPage { Font, Performance, SystemProperties, Shaders }

private fun ConfigDetail.toChildPage(): ConfigChildPage = when (this) {
    ConfigDetail.Font -> ConfigChildPage.Font
    ConfigDetail.Performance -> ConfigChildPage.Performance
}

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
    var childPage by rememberSaveable { mutableStateOf<ConfigChildPage?>(null) }
    var systemPropertiesSession by rememberSaveable { mutableIntStateOf(0) }
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
            childPage != null -> childPage = null
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
    BackHandler(enabled = searchVisible || childPage != null ||
        selectedDestination != ConfigDestination.Basic || isProfile) {
        requestBack()
    }

    val quickScrollState = rememberScrollState()
    val displayScrollState = rememberScrollState()
    val graphicsScrollState = rememberScrollState()
    val controlsScrollState = rememberScrollState()
    val mediaScrollState = rememberScrollState()
    val systemScrollState = rememberScrollState()
    val density = LocalDensity.current
    val cutoutAvailable = cutoutSupportOverride ?: deviceHasDisplayCutout(density)
    val currentPage = when (childPage) {
        ConfigChildPage.Font -> ConfigPage.Detail(ConfigDetail.Font)
        ConfigChildPage.Performance -> ConfigPage.Detail(ConfigDetail.Performance)
        ConfigChildPage.SystemProperties -> ConfigPage.SystemProperties
        ConfigChildPage.Shaders -> ConfigPage.Rendering(RenderingSubpage.SHADERS)
        null -> if (selectedDestination == ConfigDestination.Basic) ConfigPage.Main
            else ConfigPage.Category(selectedDestination)
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
                        onBack = { if (active) childPage = null },
                    )
                }
                is ConfigPage.Rendering -> {
                    RenderingSettingsPage(
                        state = state,
                        onFormChanged = updateForm,
                        onShaderPickerOpened = events::onShaderPickerOpened,
                        onBack = { if (active) childPage = null },
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
                            onBack = { if (active) childPage = null },
                            onSave = { value ->
                                childPage = null
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
                        ConfigDestination.Graphics -> graphicsScrollState
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
                                    childPage = detail?.toChildPage()
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
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        LocalConfigSectionReveal provides revealSection?.let { section ->
                                            ConfigSectionReveal(section) { revealSection = null }
                                        },
                                    ) {
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
                                                onOpenPerformance = { childPage = ConfigChildPage.Performance },
                                                onOpenQuickSetting = { quickSetting = it },
                                                onRequestAction = { pendingAction = it },
                                            )
                                        }
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
                                                childPage = ConfigChildPage.SystemProperties
                                            },
                                            onOpenShaders = {
                                                childPage = ConfigChildPage.Shaders
                                            },
                                            onOpenDetail = { childPage = it.toChildPage() },
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
                ScreenAppearanceSection(form, state, onFormChanged, events)
                ConfigSection(title = stringResource(R.string.config_display_text)) {
                    ConfigNavigationPreference(
                        title = stringResource(R.string.config_font_page_title),
                        value = stringResource(R.string.config_font_sizes_value,
                            form.fontSizeSmall, form.fontSizeMedium, form.fontSizeLarge),
                        onClick = { onOpenDetail(ConfigDetail.Font) },
                    )
                }
            }
            ConfigDestination.Graphics -> {
                RenderingSection(form, state, onFormChanged, onOpenShaders)
                ConfigSection(title = stringResource(R.string.config_display_performance)) {
                    ConfigNavigationPreference(
                        title = stringResource(R.string.config_performance_page_title),
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
            showInlineDescription = false,
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
internal fun ConfigGlobalActionsSection(
    isProfile: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
) {
    ConfigSection(
        title = stringResource(
            if (isProfile) R.string.config_profile_maintenance else R.string.config_midlet_actions,
        ),
    ) {
        ConfigActionPreference(
            title = stringResource(
                if (isProfile) R.string.config_reset_profile_settings
                else R.string.config_reset_all_settings,
            ),
            description = stringResource(
                if (isProfile) R.string.config_reset_profile_settings_summary
                else R.string.config_reset_all_settings_summary,
            ),
            destructive = true,
            testTag = "config_reset_settings_action",
            onClick = { onRequestAction(ConfigAction.ResetSettings) },
        )
        if (!isProfile) {
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
private fun ScreenAppearanceSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
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
}

@Composable
private fun RenderingSection(
    form: ConfigFormState,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onOpenShaders: () -> Unit,
) {
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
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
                        modifier = Modifier
                            .background(
                                if (swapped) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                CircleShape,
                            )
                            .semantics { contentDescription = swapToggleDescription },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap_horiz),
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.config_help_screen_size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
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
private fun CompatibilitySection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_system_compatibility)) {
        ConfigSwitchPreference(
            title = stringResource(R.string.pref_skip_resume_call),
            description = if (form.skipResumeCall) null else stringResource(R.string.config_help_skip_resume),
            checked = form.skipResumeCall,
            message = if (form.skipResumeCall) stringResource(R.string.config_help_skip_resume) else null,
            messageLevel = ConfigMessageLevel.Warning,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().skipResumeCall(checked).build()) },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.PREF_IMMEDIATE),
            description = if (form.immediateMode) null
                else stringResource(R.string.config_help_immediate),
            checked = form.immediateMode,
            message = if (form.immediateMode) stringResource(R.string.config_help_immediate) else null,
            messageLevel = ConfigMessageLevel.Warning,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().immediateMode(checked).build()) },
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
    CompatibilitySection(form, onFormChanged)
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
    val actionTitle = if (action == ConfigAction.ResetSettings && isProfile) {
        R.string.config_reset_profile_settings
    } else action.title
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(actionTitle)) },
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
                Text(stringResource(actionTitle))
            }
        },
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
