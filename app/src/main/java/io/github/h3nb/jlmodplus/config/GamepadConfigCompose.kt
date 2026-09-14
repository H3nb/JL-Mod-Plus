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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.Binding
import io.github.h3nb.jlmodplus.input.BindingKind
import io.github.h3nb.jlmodplus.input.ControllerConfig
import io.github.h3nb.jlmodplus.input.ControllerInputRouter
import io.github.h3nb.jlmodplus.input.ControllerMode
import io.github.h3nb.jlmodplus.input.DirectionMode
import io.github.h3nb.jlmodplus.input.GuestKey
import io.github.h3nb.jlmodplus.input.HostAction
import io.github.h3nb.jlmodplus.input.PointerAction
import io.github.h3nb.jlmodplus.input.PointerMode
import io.github.h3nb.jlmodplus.input.StickId
import io.github.h3nb.jlmodplus.input.StickMode
import io.github.h3nb.jlmodplus.input.StickSettings
import io.github.h3nb.jlmodplus.input.TriggerSettings
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

/**
 * The reusable Controls → Gamepad surface. Runtime entry points use the same configuration
 * subtree and callbacks; this composable owns only a draft until Save is pressed.
 */
@Composable
internal fun GamepadSection(
    form: ConfigFormState,
    controllerAvailable: Boolean,
    onFormChanged: (ConfigFormState) -> Unit,
    events: ConfigFormEvents,
) {
    val opaqueController = form.controller?.let { !it.isJsonObject } == true
    val resolved = remember(form.controller) {
        ControllerConfig.parse(
            form.controller?.takeIf { it.isJsonObject }?.asJsonObject,
        )
    }
    // A future/invalid controller object remains opaque until the user explicitly resets it;
    // do not let the editor or runtime probes reinterpret that payload as a new mapping.
    val controllerUnavailable = opaqueController || !resolved.isSupported
    var editorVisible by remember { mutableStateOf(false) }
    var resetVisible by remember { mutableStateOf(false) }

    ConfigSection(
        title = androidx.compose.ui.res.stringResource(R.string.config_controls_gamepad),
    ) {
        val gamepadEnabled = controllerAvailable && !controllerUnavailable && resolved.enabled
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled_summary),
            checked = gamepadEnabled,
            enabled = controllerAvailable && !controllerUnavailable,
            onCheckedChange = { enabled ->
                if (controllerAvailable && !controllerUnavailable) {
                    updateController(form, onFormChanged) {
                        enabled(enabled)
                    }
                }
            },
        )
        val summary = if (!controllerAvailable) {
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_no_controller_summary)
        } else if (!resolved.isSupported) {
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_unsupported_summary)
        } else if (!resolved.enabled) {
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_summary_disabled)
        } else {
            androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_summary_enabled,
                presetLabel(resolved.preset),
                directionModeLabel(resolved.directionMode),
            )
        }
        ConfigMessageBlock(
            if (opaqueController) {
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_opaque_summary)
            } else {
                summary
            },
            if (opaqueController || !resolved.isSupported) ConfigMessageLevel.Warning
            else ConfigMessageLevel.Info,
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_editor),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_editor_summary),
            enabled = !controllerUnavailable,
            onClick = { editorVisible = true },
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_test),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_test_summary),
            enabled = controllerAvailable && !controllerUnavailable,
            onClick = events::onGamepadDiagnosis,
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibrate),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibrate_summary),
            enabled = controllerAvailable && !controllerUnavailable,
            onClick = events::onGamepadCalibration,
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_help),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_help_summary),
            onClick = events::onGamepadHelp,
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_reset),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_reset_summary),
            destructive = true,
            onClick = { resetVisible = true },
        )
    }

    if (editorVisible) {
        GamepadEditorDialog(
            initial = resolved,
            controllerAvailable = controllerAvailable,
            onDismissRequest = { editorVisible = false },
            onConfirm = { next ->
                editorVisible = false
                onFormChanged(form.toBuilder().controller(next.toJson()).build())
            },
            onCapture = events::onGamepadCapture,
            onDiagnosis = events::onGamepadDiagnosis,
            onCalibration = events::onGamepadCalibration,
        )
    }
    if (resetVisible) {
        AdaptiveAlertDialog(
            onDismissRequest = { resetVisible = false },
            title = {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_reset),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_reset_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            dismissButton = {
                TextButton(onClick = { resetVisible = false }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    resetVisible = false
                    onFormChanged(
                        form.toBuilder()
                            .controller(ControllerConfig.defaultNavigation().toJson())
                            .build(),
                    )
                }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.reset))
                }
            },
        )
    }
}

@Composable
private fun GamepadEditorDialog(
    initial: ControllerConfig,
    controllerAvailable: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (ControllerConfig) -> Unit,
    onCapture: (String) -> Unit,
    onDiagnosis: () -> Unit,
    onCalibration: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var bindingControl by remember { mutableStateOf<String?>(null) }
    var helpVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val maxHeight = adaptiveDialogLayout().maxHeight

    AdaptiveAlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_editor_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(scrollState),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!draft.isSupported) {
                        ConfigMessageBlock(
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_unsupported_summary),
                            ConfigMessageLevel.Warning,
                        )
                    }
                    ConfigSwitchPreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled_summary),
                        checked = controllerAvailable && draft.enabled,
                        enabled = controllerAvailable,
                        onCheckedChange = { value ->
                            if (controllerAvailable) draft = editController(draft) { enabled(value) }
                        },
                    )
                    ConfigChoicePreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode_summary),
                        selected = if (draft.legacy) {
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode_legacy)
                        } else {
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode_enabled)
                        },
                        options = listOf(
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode_enabled),
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_mode_legacy),
                        ),
                        onSelected = { index ->
                            draft = editController(draft) {
                                mode(if (index == 0) ControllerMode.ENABLED else ControllerMode.LEGACY)
                            }
                        },
                    )
                    ConfigChoicePreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_summary),
                        selected = presetLabel(draft.preset),
                        options = listOf(
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_navigation),
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_game),
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_numeric),
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_custom),
                        ),
                        onSelected = { index ->
                            val preset = listOf(
                                io.github.h3nb.jlmodplus.input.Preset.NAVIGATION,
                                io.github.h3nb.jlmodplus.input.Preset.GAME,
                                io.github.h3nb.jlmodplus.input.Preset.NUMERIC,
                                io.github.h3nb.jlmodplus.input.Preset.CUSTOM,
                            ).getOrNull(index)
                            if (preset != null) draft = editController(draft) { preset(preset) }
                        },
                    )
                    val directionOptions = listOf(
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_four),
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_eight),
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_diagonal),
                    )
                    ConfigChoicePreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_direction_mode),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_direction_mode_summary),
                        selected = directionOptions.getOrElse(directionIndex(draft.directionMode)) { directionOptions[1] },
                        options = directionOptions,
                        onSelected = { index ->
                            val mode = when (index) {
                                0 -> DirectionMode.FOUR
                                2 -> DirectionMode.DIAGONAL_NUMBER
                                else -> DirectionMode.EIGHT
                            }
                            draft = editController(draft) { directionMode(mode) }
                        },
                    )
                    StickEditor(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_left_stick),
                        settings = draft.leftStick,
                        onChange = { draft = editController(draft) { leftStick(it) } },
                    )
                    StickEditor(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_right_stick),
                        settings = draft.rightStick,
                        onChange = { draft = editController(draft) { rightStick(it) } },
                    )
                    ConfigSection(title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_triggers)) {
                        ThresholdSlider(
                            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_trigger_press),
                            value = draft.triggers.pressThreshold.toFloat(),
                            range = 0.05f..1.0f,
                            onValueChange = { value ->
                                val press = value.toDouble()
                                val release = draft.triggers.releaseThreshold.coerceAtMost(press - 0.01)
                                    .coerceAtLeast(0.0)
                                draft = editController(draft) {
                                    triggers(TriggerSettings(press, release))
                                }
                            },
                        )
                        ThresholdSlider(
                            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_trigger_release),
                            value = draft.triggers.releaseThreshold.toFloat(),
                            range = 0.0f..0.90f,
                            onValueChange = { value ->
                                val release = value.toDouble().coerceAtMost(draft.triggers.pressThreshold - 0.01)
                                draft = editController(draft) {
                                    triggers(TriggerSettings(draft.triggers.pressThreshold, release))
                                }
                            },
                        )
                    }
                    ConfigSection(title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_bindings)) {
                        bindingRows().forEach { (control, labelRes) ->
                            val label = androidx.compose.ui.res.stringResource(labelRes)
                            val binding = draft.binding(control) ?: Binding.none()
                            ConfigValuePreference(
                                title = label,
                                description = bindingLabel(binding),
                                value = androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture),
                                onClick = { bindingControl = control },
                            )
                            TextButton(
                                enabled = controllerAvailable,
                                onClick = { onCapture(control) },
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture))
                            }
                        }
                    }
                    PointerEditor(
                        pointer = draft.pointer,
                        onChange = { pointer -> draft = editController(draft) { pointer(pointer) } },
                        onBinding = { bindingControl = POINTER_CLICK_CONTROL },
                    )
                    ConfigActionPreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_test),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_test_summary),
                        enabled = controllerAvailable,
                        onClick = onDiagnosis,
                    )
                    ConfigActionPreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibrate),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibrate_summary),
                        enabled = controllerAvailable,
                        onClick = onCalibration,
                    )
                    ConfigActionPreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_help),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_help_summary),
                        onClick = { helpVisible = true },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.validate().isValid,
                onClick = { onConfirm(draft) },
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.save))
            }
        },
    )

    bindingControl?.let { control ->
        val current = if (control == POINTER_CLICK_CONTROL) draft.pointer.clickBinding
        else draft.binding(control) ?: Binding.none()
        GamepadBindingDialog(
            controlLabel = if (control == POINTER_CLICK_CONTROL) {
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_click_binding)
            } else {
                bindingRows().firstOrNull { it.first == control }?.second
                    ?.let { androidx.compose.ui.res.stringResource(it) }
                    ?: control
            },
            choices = if (control == POINTER_CLICK_CONTROL) {
                pointerBindingChoices()
            } else {
                bindingChoices()
            },
            selected = current,
            onDismissRequest = { bindingControl = null },
            onSelected = { binding ->
                draft = if (control == POINTER_CLICK_CONTROL) {
                    editController(draft) { pointer(draft.pointer.copy(clickBinding = binding)) }
                } else {
                    editController(draft) { binding(control, binding) }
                }
                bindingControl = null
            },
        )
    }

    if (helpVisible) {
        AdaptiveAlertDialog(
            onDismissRequest = { helpVisible = false },
            title = {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_mapping_help_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_mapping_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { helpVisible = false }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun StickEditor(
    title: String,
    settings: StickSettings,
    onChange: (StickSettings) -> Unit,
) {
    ConfigSection(title = title) {
        val modes = listOf(
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_stick_directions),
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_stick_unassigned),
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_stick_pointer),
        )
        ConfigChoicePreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_stick_mode),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_stick_mode_summary),
            selected = modes.getOrElse(stickModeIndex(settings.mode)) { modes[0] },
            options = modes,
            onSelected = { index ->
                onChange(settings.copy(mode = listOf(StickMode.DIRECTIONS, StickMode.UNASSIGNED, StickMode.POINTER)
                    .getOrElse(index) { StickMode.DIRECTIONS }))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_inner_deadzone),
            value = settings.innerDeadzone.toFloat(),
            range = 0.0f..0.60f,
            onValueChange = { value ->
                val inner = value.toDouble().coerceAtMost(settings.pressRadius - 0.02)
                    .coerceAtLeast(0.0)
                onChange(settings.copy(innerDeadzone = inner))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_outer_saturation),
            value = settings.outerSaturation.toFloat(),
            range = 0.20f..1.0f,
            onValueChange = { value ->
                onChange(settings.copy(
                    outerSaturation = value.toDouble().coerceAtLeast(settings.innerDeadzone + 0.02),
                ))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_press_radius),
            value = settings.pressRadius.toFloat(),
            range = 0.20f..1.0f,
            onValueChange = { value ->
                val press = value.toDouble().coerceAtLeast(settings.innerDeadzone + 0.02)
                val release = settings.releaseRadius.coerceAtMost(press - 0.01)
                    .coerceAtLeast(settings.innerDeadzone + 0.01)
                onChange(settings.copy(pressRadius = press, releaseRadius = release))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_release_radius),
            value = settings.releaseRadius.toFloat(),
            range = 0.05f..0.90f,
            onValueChange = { value ->
                val release = value.toDouble().coerceIn(
                    settings.innerDeadzone + 0.01,
                    settings.pressRadius - 0.01,
                )
                onChange(settings.copy(releaseRadius = release))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_angular_hysteresis),
            value = settings.angularHysteresisDegrees.toFloat(),
            range = 0.0f..(settings.directionMode.sectorWidthDegrees.toFloat() / 2.0f - 0.01f)
                .coerceAtLeast(0.01f),
            onValueChange = { value ->
                onChange(settings.copy(
                    angularHysteresisDegrees = value.toDouble().coerceIn(
                        0.0,
                        settings.directionMode.sectorWidthDegrees / 2.0 - 0.01,
                    ),
                ))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_response_exponent),
            value = settings.responseExponent.toFloat(),
            range = 0.25f..3.0f,
            onValueChange = { value ->
                onChange(settings.copy(responseExponent = value.toDouble().coerceAtLeast(0.01)))
            },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_x),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_x_summary),
            checked = settings.invertX,
            onCheckedChange = { onChange(settings.copy(invertX = it)) },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_y),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_y_summary),
            checked = settings.invertY,
            onCheckedChange = { onChange(settings.copy(invertY = it)) },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_swap_axes),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_swap_axes_summary),
            checked = settings.swapAxes,
            onCheckedChange = { onChange(settings.copy(swapAxes = it)) },
        )
    }
}

@Composable
private fun PointerEditor(
    pointer: io.github.h3nb.jlmodplus.input.PointerSettings,
    onChange: (io.github.h3nb.jlmodplus.input.PointerSettings) -> Unit,
    onBinding: () -> Unit,
) {
    ConfigSection(title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer)) {
        val modes = listOf(
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_off),
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_cursor),
            androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_joystick),
        )
        ConfigChoicePreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_mode),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_mode_summary),
            selected = modes.getOrElse(pointerModeIndex(pointer.mode)) { modes[0] },
            options = modes,
            onSelected = { index ->
                onChange(pointer.copy(mode = listOf(PointerMode.OFF, PointerMode.CURSOR, PointerMode.TOUCH_JOYSTICK)
                    .getOrElse(index) { PointerMode.OFF }))
            },
        )
        if (pointer.mode != PointerMode.OFF) {
            ConfigChoicePreference(
                title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_stick),
                description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_stick_summary),
                selected = androidx.compose.ui.res.stringResource(
                    if (pointer.sourceStick == StickId.LEFT) R.string.config_gamepad_left_stick
                    else R.string.config_gamepad_right_stick,
                ),
                options = listOf(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_left_stick),
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_right_stick),
                ),
                onSelected = { index -> onChange(pointer.copy(sourceStick = if (index == 0) StickId.LEFT else StickId.RIGHT)) },
            )
            ThresholdSlider(
                label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_speed),
                value = pointer.speed.toFloat(),
                range = 0.25f..4.0f,
                onValueChange = { onChange(pointer.copy(speed = it.toDouble())) },
            )
            if (pointer.mode == PointerMode.TOUCH_JOYSTICK) {
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_center_x),
                    value = pointer.centerX.toFloat(),
                    range = 0.0f..1.0f,
                    onValueChange = { onChange(pointer.copy(centerX = it.toDouble())) },
                )
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_center_y),
                    value = pointer.centerY.toFloat(),
                    range = 0.0f..1.0f,
                    onValueChange = { onChange(pointer.copy(centerY = it.toDouble())) },
                )
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_radius),
                    value = pointer.radius.toFloat(),
                    range = 0.05f..1.0f,
                    onValueChange = { onChange(pointer.copy(radius = it.toDouble())) },
                )
            }
            ConfigValuePreference(
                title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_click_binding),
                description = bindingLabel(pointer.clickBinding),
                value = androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture),
                onClick = onBinding,
            )
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                String.format(java.util.Locale.US, "%.2f", value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = SliderDefaults.colors(),
        )
    }
}

@Composable
private fun GamepadBindingDialog(
    controlLabel: String,
    choices: List<Binding>,
    selected: Binding,
    onDismissRequest: () -> Unit,
    onSelected: (Binding) -> Unit,
) {
    AdaptiveAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(controlLabel, style = MaterialTheme.typography.titleLarge) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = adaptiveDialogLayout().maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = { onSelected(choice) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = bindingLabel(choice),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (choice == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}

private const val POINTER_CLICK_CONTROL = "pointer.clickBinding"

private fun updateController(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
    change: ControllerConfig.Builder.() -> Unit,
) {
    val current = ControllerConfig.parse(form.controller?.takeIf { it.isJsonObject }?.asJsonObject)
    onFormChanged(form.toBuilder().controller(editController(current, change).toJson()).build())
}

private fun editController(
    current: ControllerConfig,
    change: ControllerConfig.Builder.() -> Unit,
): ControllerConfig = current.toBuilder().apply(change).buildOrNull() ?: current

private fun directionIndex(mode: DirectionMode): Int = when (mode) {
    DirectionMode.FOUR -> 0
    DirectionMode.EIGHT -> 1
    DirectionMode.DIAGONAL_NUMBER -> 2
}

private fun stickModeIndex(mode: StickMode): Int = when (mode) {
    StickMode.DIRECTIONS -> 0
    StickMode.UNASSIGNED -> 1
    StickMode.POINTER -> 2
}

private fun pointerModeIndex(mode: PointerMode): Int = when (mode) {
    PointerMode.OFF -> 0
    PointerMode.CURSOR -> 1
    PointerMode.TOUCH_JOYSTICK -> 2
}

private fun bindingRows(): List<Pair<String, Int>> = listOf(
    ControllerInputRouter.CONTROL_BUTTON_A to R.string.config_gamepad_control_button_a,
    ControllerInputRouter.CONTROL_BUTTON_B to R.string.config_gamepad_control_button_b,
    ControllerInputRouter.CONTROL_BUTTON_X to R.string.config_gamepad_control_button_x,
    ControllerInputRouter.CONTROL_BUTTON_Y to R.string.config_gamepad_control_button_y,
    ControllerInputRouter.CONTROL_BUTTON_L1 to R.string.config_gamepad_control_button_l1,
    ControllerInputRouter.CONTROL_BUTTON_R1 to R.string.config_gamepad_control_button_r1,
    ControllerInputRouter.CONTROL_BUTTON_L2 to R.string.config_gamepad_control_button_l2,
    ControllerInputRouter.CONTROL_BUTTON_R2 to R.string.config_gamepad_control_button_r2,
    ControllerInputRouter.CONTROL_BUTTON_START to R.string.config_gamepad_control_button_start,
    ControllerInputRouter.CONTROL_BUTTON_SELECT to R.string.config_gamepad_control_button_select,
    ControllerInputRouter.CONTROL_DPAD_UP to R.string.config_gamepad_control_dpad_up,
    ControllerInputRouter.CONTROL_DPAD_DOWN to R.string.config_gamepad_control_dpad_down,
    ControllerInputRouter.CONTROL_DPAD_LEFT to R.string.config_gamepad_control_dpad_left,
    ControllerInputRouter.CONTROL_DPAD_RIGHT to R.string.config_gamepad_control_dpad_right,
)

private fun bindingChoices(): List<Binding> = buildList {
    add(Binding.none())
    GuestKey.entries.forEach { add(Binding.guestKey(it)) }
    HostAction.entries.forEach { add(Binding.hostAction(it)) }
    PointerAction.entries.forEach { add(Binding.pointerAction(it)) }
}

private fun pointerBindingChoices(): List<Binding> = buildList {
    add(Binding.none())
    PointerAction.entries.forEach { add(Binding.pointerAction(it)) }
}

@Composable
private fun bindingLabel(binding: Binding): String = when (binding.kind) {
    BindingKind.NONE -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_binding_none)
    BindingKind.GUEST_KEY -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_binding_guest,
        bindingTokenLabel(binding.kind, binding.token),
    )
    BindingKind.HOST_ACTION -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_binding_host,
        bindingTokenLabel(binding.kind, binding.token),
    )
    BindingKind.POINTER_ACTION -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_binding_pointer,
        bindingTokenLabel(binding.kind, binding.token),
    )
}

@Composable
private fun bindingTokenLabel(kind: BindingKind, token: String?): String = when (kind) {
    BindingKind.GUEST_KEY -> when (GuestKey.fromToken(token.orEmpty())) {
        GuestKey.NUM0 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num0)
        GuestKey.NUM1 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num1)
        GuestKey.NUM2 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num2)
        GuestKey.NUM3 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num3)
        GuestKey.NUM4 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num4)
        GuestKey.NUM5 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num5)
        GuestKey.NUM6 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num6)
        GuestKey.NUM7 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num7)
        GuestKey.NUM8 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num8)
        GuestKey.NUM9 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_num9)
        GuestKey.STAR -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_star)
        GuestKey.POUND -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_pound)
        GuestKey.UP -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_up)
        GuestKey.DOWN -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_down)
        GuestKey.LEFT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_left)
        GuestKey.RIGHT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_right)
        GuestKey.FIRE -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_fire)
        GuestKey.SOFT_LEFT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_soft_left)
        GuestKey.SOFT_RIGHT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_soft_right)
        GuestKey.CLEAR -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_clear)
        GuestKey.SEND -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_send)
        GuestKey.END -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_end)
        GuestKey.GAME_A -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_game_a)
        GuestKey.GAME_B -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_game_b)
        GuestKey.GAME_C -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_game_c)
        GuestKey.GAME_D -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_guest_game_d)
        null -> token.orEmpty()
    }
    BindingKind.HOST_ACTION -> when (HostAction.fromToken(token.orEmpty())) {
        HostAction.OPEN_MENU -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_open_menu)
        HostAction.OPEN_MAPPING_HELP -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_mapping_help)
        HostAction.BACK -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_back)
        HostAction.ACTIVATE -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_activate)
        HostAction.NEXT_TAB -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_next_tab)
        HostAction.PREVIOUS_TAB -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_previous_tab)
        HostAction.OPEN_KEYPAD -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_host_open_keypad)
        null -> token.orEmpty()
    }
    BindingKind.POINTER_ACTION -> when (PointerAction.fromToken(token.orEmpty())) {
        PointerAction.CLICK -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_click)
        null -> token.orEmpty()
    }
    BindingKind.NONE -> ""
}

@Composable
private fun presetLabel(preset: io.github.h3nb.jlmodplus.input.Preset): String = when (preset) {
    io.github.h3nb.jlmodplus.input.Preset.NAVIGATION ->
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_navigation)
    io.github.h3nb.jlmodplus.input.Preset.GAME ->
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_game)
    io.github.h3nb.jlmodplus.input.Preset.NUMERIC ->
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_numeric)
    io.github.h3nb.jlmodplus.input.Preset.CUSTOM ->
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_preset_custom)
}

@Composable
private fun directionModeLabel(mode: DirectionMode): String = when (mode) {
    DirectionMode.FOUR -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_four)
    DirectionMode.EIGHT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_eight)
    DirectionMode.DIAGONAL_NUMBER -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_diagonal)
}

@Composable
private fun controllerControlLabel(control: String): String = when (control) {
    ControllerInputRouter.CONTROL_BUTTON_A -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_a)
    ControllerInputRouter.CONTROL_BUTTON_B -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_b)
    ControllerInputRouter.CONTROL_BUTTON_X -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_x)
    ControllerInputRouter.CONTROL_BUTTON_Y -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_y)
    ControllerInputRouter.CONTROL_BUTTON_L1 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_l1)
    ControllerInputRouter.CONTROL_BUTTON_R1 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_r1)
    ControllerInputRouter.CONTROL_BUTTON_L2 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_l2)
    ControllerInputRouter.CONTROL_BUTTON_R2 -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_r2)
    ControllerInputRouter.CONTROL_BUTTON_START -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_start)
    ControllerInputRouter.CONTROL_BUTTON_SELECT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_button_select)
    ControllerInputRouter.CONTROL_DPAD_UP -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_dpad_up)
    ControllerInputRouter.CONTROL_DPAD_DOWN -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_dpad_down)
    ControllerInputRouter.CONTROL_DPAD_LEFT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_dpad_left)
    ControllerInputRouter.CONTROL_DPAD_RIGHT -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_control_dpad_right)
    else -> control
}

@Composable
private fun capturePhaseLabel(phase: String): String = when (phase) {
    "WAIT_OPENING_RELEASE" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_phase_waiting_opening)
    "ARMED" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_phase_armed)
    "CANDIDATE" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_phase_candidate)
    "WAIT_CANDIDATE_RELEASE" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_phase_waiting_candidate)
    "REVIEW" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_phase_review)
    else -> phase
}

@Composable
private fun calibrationPhaseLabel(phase: String): String = when (phase) {
    "WAIT_NEUTRAL" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_neutral)
    "STICK_RANGE" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_stick_range)
    "TRIGGER_RANGE" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_trigger_range)
    "REVIEW" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_review)
    "COMMITTED" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_committed)
    "CANCELLED" -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_phase_cancelled)
    else -> phase
}

data class GamepadCaptureUiState(
    val targetControl: String,
    val phase: String,
    val candidateControl: String? = null,
    val candidateKind: String? = null,
    val message: String? = null,
)

@Composable
internal fun GamepadCaptureDialog(
    state: GamepadCaptureUiState,
    onCancel: () -> Unit,
    onCommit: (String, String) -> Unit,
) {
    val candidate = state.candidateControl
    val targetLabel = controllerControlLabel(state.targetControl)
    val phaseLabel = capturePhaseLabel(state.phase)
    AdaptiveAlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        R.string.config_gamepad_capture_target,
                        phaseLabel,
                        targetLabel,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    state.message
                        ?: if (candidate == null) {
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_waiting)
                        } else if (state.candidateKind == "AXIS") {
                            androidx.compose.ui.res.stringResource(R.string.config_gamepad_capture_axis_not_assignable)
                        } else {
                            androidx.compose.ui.res.stringResource(
                                R.string.config_gamepad_capture_candidate,
                                controllerControlLabel(candidate),
                            )
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = candidate != null && state.candidateKind == "BUTTON" && state.phase == "REVIEW",
                onClick = { candidate?.let { onCommit(state.targetControl, it) } },
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.save))
            }
        },
    )
}

@Composable
internal fun GamepadHelpDialog(onDismiss: () -> Unit) {
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_mapping_help_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_mapping_help_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun GamepadDiagnosisDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_test),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = adaptiveDialogLayout().maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun GamepadCalibrationDialog(
    state: GamepadCalibrationUiState,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onAdvance: () -> Unit,
    onSave: () -> Unit,
) {
    AdaptiveAlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibrate),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = adaptiveDialogLayout().maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        calibrationPhaseLabel(state.phase),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    state.signature?.let {
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                R.string.config_gamepad_calibration_profile,
                                it,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReset) {
                    Text(androidx.compose.ui.res.stringResource(R.string.reset))
                }
                TextButton(onClick = onCancel) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = state.canAdvance, onClick = onAdvance) {
                    Text(androidx.compose.ui.res.stringResource(R.string.config_gamepad_calibration_next))
                }
                TextButton(enabled = state.canSave, onClick = onSave) {
                    Text(androidx.compose.ui.res.stringResource(R.string.save))
                }
            }
        },
    )
}

data class GamepadCalibrationUiState(
    val phase: String,
    val text: String,
    val signature: String? = null,
    val canAdvance: Boolean = false,
    val canSave: Boolean = false,
)
