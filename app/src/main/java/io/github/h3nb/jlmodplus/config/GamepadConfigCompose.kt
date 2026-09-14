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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.ControllerConfig
import io.github.h3nb.jlmodplus.input.DirectionMode
import io.github.h3nb.jlmodplus.input.PointerAction
import io.github.h3nb.jlmodplus.input.PointerMode
import io.github.h3nb.jlmodplus.input.PointerSettings
import io.github.h3nb.jlmodplus.input.StickId
import io.github.h3nb.jlmodplus.input.StickMode
import io.github.h3nb.jlmodplus.input.VirtualAnalogStickMode
import io.github.h3nb.jlmodplus.input.StickSettings
import io.github.h3nb.jlmodplus.input.TriggerSettings
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

/**
 * Controls → Gamepad. Digital button mapping lives in the shared KeyMapper screen; this section
 * edits only physical-controller analog sticks, trigger adaptation, pointer output, calibration,
 * and diagnosis. Touch D-pad/analog visibility belongs to the runtime Virtual Controls menu.
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
        ControllerConfig.parse(form.controller?.takeIf { it.isJsonObject }?.asJsonObject)
    }
    val controllerUnavailable = opaqueController || !resolved.isSupported
    var editorVisible by remember { mutableStateOf(false) }
    var resetVisible by remember { mutableStateOf(false) }

    ConfigSection(
        title = androidx.compose.ui.res.stringResource(R.string.config_controls_gamepad),
        accentTitle = false,
    ) {
        val gamepadEnabled = controllerAvailable && !controllerUnavailable && resolved.enabled
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_enabled_summary),
            checked = gamepadEnabled,
            enabled = controllerAvailable && !controllerUnavailable,
            onCheckedChange = { enabled ->
                if (controllerAvailable && !controllerUnavailable) {
                    updateController(form, onFormChanged) { enabled(enabled) }
                }
            },
        )
        val summary = when {
            !controllerAvailable -> androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_no_controller_summary,
            )
            !resolved.isSupported -> androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_unsupported_summary,
            )
            !resolved.enabled -> androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_summary_disabled,
            )
            else -> androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_summary_enabled,
            )
        }
        ConfigMessageBlock(
            if (opaqueController) androidx.compose.ui.res.stringResource(
                R.string.config_gamepad_opaque_summary,
            ) else summary,
            if (opaqueController || !resolved.isSupported) ConfigMessageLevel.Warning
            else ConfigMessageLevel.Info,
        )
        ConfigActionPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_editor),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_editor_summary),
            enabled = controllerAvailable && !controllerUnavailable,
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
                            .controller(ControllerConfig.defaultNavigation().reset().toJson())
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
    onDiagnosis: () -> Unit,
    onCalibration: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var clickDialogVisible by remember { mutableStateOf(false) }
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
                    val directionOptions = listOf(
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_four),
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_eight),
                        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_numeric_v2),
                    )
                    ConfigChoicePreference(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_direction_mode),
                        description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_direction_mode_summary),
                        selected = directionOptions.getOrElse(
                            directionIndex(draft.leftStick.directionMode),
                        ) { directionOptions[1] },
                        options = directionOptions,
                        enabled = controllerAvailable,
                        onSelected = { index ->
                            val mode = when (index) {
                                0 -> DirectionMode.FOUR
                                2 -> DirectionMode.DIAGONAL_NUMBER
                                else -> DirectionMode.EIGHT
                            }
                            draft = editController(draft) {
                                sticks(
                                    draft.leftStick.copy(directionMode = mode),
                                    draft.rightStick.copy(directionMode = mode),
                                )
                            }
                        },
                    )
                    StickEditor(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_left_stick),
                        settings = draft.leftStick,
                        enabled = controllerAvailable,
                        onChange = { draft = editController(draft) { leftStick(it) } },
                    )
                    StickEditor(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_right_stick),
                        settings = draft.rightStick,
                        enabled = controllerAvailable,
                        onChange = { draft = editController(draft) { rightStick(it) } },
                    )
                    ConfigSection(
                        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_triggers),
                        accentTitle = false,
                    ) {
                        ConfigSwitchPreference(
                            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_triggers_enabled),
                            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_triggers_enabled_summary),
                            checked = draft.triggers.enabled,
                            enabled = controllerAvailable,
                            onCheckedChange = { enabled ->
                                draft = editController(draft) {
                                    triggers(draft.triggers.copy(enabled = enabled))
                                }
                            },
                        )
                        ThresholdSlider(
                            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_trigger_press),
                            value = draft.triggers.pressThreshold.toFloat(),
                            range = 0.05f..1.0f,
                            enabled = controllerAvailable,
                            onValueChange = { value ->
                                val press = value.toDouble()
                                val release = draft.triggers.releaseThreshold
                                    .coerceAtMost(press - 0.01)
                                    .coerceAtLeast(0.0)
                                draft = editController(draft) {
                                    triggers(TriggerSettings(press, release, draft.triggers.enabled))
                                }
                            },
                        )
                        ThresholdSlider(
                            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_trigger_release),
                            value = draft.triggers.releaseThreshold.toFloat(),
                            range = 0.0f..0.90f,
                            enabled = controllerAvailable,
                            onValueChange = { value ->
                                val release = value.toDouble()
                                    .coerceAtMost(draft.triggers.pressThreshold - 0.01)
                                draft = editController(draft) {
                                    triggers(TriggerSettings(
                                        draft.triggers.pressThreshold,
                                        release,
                                        draft.triggers.enabled,
                                    ))
                                }
                            },
                        )
                    }
                    PointerEditor(
                        pointer = draft.pointer,
                        enabled = controllerAvailable,
                        onChange = { pointer -> draft = editController(draft) { pointer(pointer) } },
                        onClickAction = { clickDialogVisible = true },
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
                enabled = controllerAvailable && draft.validate().isValid,
                onClick = { onConfirm(draft) },
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.save))
            }
        },
    )

    if (clickDialogVisible) {
        PointerClickDialog(
            selected = draft.pointer.clickAction,
            onDismissRequest = { clickDialogVisible = false },
            onSelected = { action ->
                draft = editController(draft) {
                    pointer(draft.pointer.copy(clickAction = action))
                }
                clickDialogVisible = false
            },
        )
    }
}

@Composable
private fun StickEditor(
    title: String,
    settings: StickSettings,
    enabled: Boolean,
    onChange: (StickSettings) -> Unit,
) {
    ConfigSection(title = title, accentTitle = false) {
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
            enabled = enabled,
            onSelected = { index ->
                onChange(settings.copy(mode = listOf(
                    StickMode.DIRECTIONS,
                    StickMode.UNASSIGNED,
                    StickMode.POINTER,
                ).getOrElse(index) { StickMode.DIRECTIONS }))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_inner_deadzone),
            value = settings.innerDeadzone.toFloat(),
            range = 0.0f..0.60f,
            enabled = enabled,
            onValueChange = { value ->
                onChange(settings.copy(
                    innerDeadzone = value.toDouble().coerceAtMost(settings.pressRadius - 0.02)
                        .coerceAtLeast(0.0),
                ))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_outer_saturation),
            value = settings.outerSaturation.toFloat(),
            range = 0.20f..1.0f,
            enabled = enabled,
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
            enabled = enabled,
            onValueChange = { value ->
                val press = value.toDouble().coerceAtLeast(settings.innerDeadzone + 0.02)
                onChange(settings.copy(
                    pressRadius = press,
                    releaseRadius = settings.releaseRadius.coerceAtMost(press - 0.01)
                        .coerceAtLeast(settings.innerDeadzone + 0.01),
                ))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_release_radius),
            value = settings.releaseRadius.toFloat(),
            range = 0.05f..0.90f,
            enabled = enabled,
            onValueChange = { value ->
                onChange(settings.copy(
                    releaseRadius = value.toDouble().coerceIn(
                        settings.innerDeadzone + 0.01,
                        settings.pressRadius - 0.01,
                    ),
                ))
            },
        )
        ThresholdSlider(
            label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_angular_hysteresis),
            value = settings.angularHysteresisDegrees.toFloat(),
            range = 0.0f..(settings.directionMode.sectorWidthDegrees.toFloat() / 2.0f - 0.01f)
                .coerceAtLeast(0.01f),
            enabled = enabled,
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
            enabled = enabled,
            onValueChange = { onChange(settings.copy(responseExponent = it.toDouble().coerceAtLeast(0.01))) },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_x),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_x_summary),
            checked = settings.invertX,
            enabled = enabled,
            onCheckedChange = { onChange(settings.copy(invertX = it)) },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_y),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_invert_y_summary),
            checked = settings.invertY,
            enabled = enabled,
            onCheckedChange = { onChange(settings.copy(invertY = it)) },
        )
        ConfigSwitchPreference(
            title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_swap_axes),
            description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_swap_axes_summary),
            checked = settings.swapAxes,
            enabled = enabled,
            onCheckedChange = { onChange(settings.copy(swapAxes = it)) },
        )
    }
}

@Composable
private fun PointerEditor(
    pointer: PointerSettings,
    enabled: Boolean,
    onChange: (PointerSettings) -> Unit,
    onClickAction: () -> Unit,
) {
    ConfigSection(
        title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer),
        accentTitle = false,
    ) {
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
            enabled = enabled,
            onSelected = { index ->
                onChange(pointer.copy(mode = listOf(
                    PointerMode.OFF,
                    PointerMode.CURSOR,
                    PointerMode.TOUCH_JOYSTICK,
                ).getOrElse(index) { PointerMode.OFF }))
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
                enabled = enabled,
                onSelected = { index ->
                    onChange(pointer.copy(sourceStick = if (index == 0) StickId.LEFT else StickId.RIGHT))
                },
            )
            if (pointer.mode == PointerMode.TOUCH_JOYSTICK) {
                val joystickModes = listOf(
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_joystick_fixed),
                    androidx.compose.ui.res.stringResource(R.string.config_gamepad_joystick_floating),
                )
                ConfigChoicePreference(
                    title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_joystick_mode),
                    description = androidx.compose.ui.res.stringResource(R.string.config_gamepad_joystick_mode_summary),
                    selected = joystickModes.getOrElse(
                        if (pointer.joystickMode == VirtualAnalogStickMode.FLOATING) 1 else 0,
                    ) { joystickModes[0] },
                    options = joystickModes,
                    enabled = enabled,
                    onSelected = { index ->
                        onChange(
                            pointer.copy(
                                joystickMode = if (index == 1) VirtualAnalogStickMode.FLOATING
                                else VirtualAnalogStickMode.FIXED,
                            ),
                        )
                    },
                )
            }
            ThresholdSlider(
                label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_speed),
                value = pointer.speed.toFloat(),
                range = 0.25f..4.0f,
                enabled = enabled,
                onValueChange = { onChange(pointer.copy(speed = it.toDouble())) },
            )
            if (pointer.mode == PointerMode.TOUCH_JOYSTICK) {
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_center_x),
                    value = pointer.centerX.toFloat(),
                    range = 0.0f..1.0f,
                    enabled = enabled,
                    onValueChange = { onChange(pointer.copy(centerX = it.toDouble())) },
                )
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_center_y),
                    value = pointer.centerY.toFloat(),
                    range = 0.0f..1.0f,
                    enabled = enabled,
                    onValueChange = { onChange(pointer.copy(centerY = it.toDouble())) },
                )
                ThresholdSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_radius),
                    value = pointer.radius.toFloat(),
                    range = 0.05f..1.0f,
                    enabled = enabled,
                    onValueChange = { onChange(pointer.copy(radius = it.toDouble())) },
                )
            }
            ConfigValuePreference(
                title = androidx.compose.ui.res.stringResource(R.string.config_gamepad_click_binding),
                description = pointerActionLabel(pointer.clickAction),
                value = androidx.compose.ui.res.stringResource(R.string.config_select),
                enabled = enabled,
                onClick = onClickAction,
            )
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                String.format(java.util.Locale.US, "%.2f", value),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = SliderDefaults.colors(),
        )
    }
}

@Composable
private fun PointerClickDialog(
    selected: PointerAction?,
    onDismissRequest: () -> Unit,
    onSelected: (PointerAction?) -> Unit,
) {
    val choices = listOf<PointerAction?>(null) + PointerAction.entries
    AdaptiveAlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_click_binding),
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
                Column {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = { onSelected(choice) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = pointerActionLabel(choice),
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

@Composable
private fun pointerActionLabel(action: PointerAction?): String = when (action) {
    null -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_binding_none)
    PointerAction.CLICK -> androidx.compose.ui.res.stringResource(R.string.config_gamepad_pointer_click)
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
                androidx.compose.ui.res.stringResource(R.string.config_gamepad_mapping_help_body_v2),
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
                    Text(calibrationPhaseLabel(state.phase), style = MaterialTheme.typography.labelMedium)
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

@Composable
private fun calibrationPhaseLabel(phase: String): String = when (phase) {
    "WAIT_NEUTRAL" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_neutral,
    )
    "STICK_RANGE" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_stick_range,
    )
    "TRIGGER_RANGE" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_trigger_range,
    )
    "REVIEW" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_review,
    )
    "COMMITTED" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_committed,
    )
    "CANCELLED" -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_cancelled,
    )
    else -> androidx.compose.ui.res.stringResource(
        R.string.config_gamepad_calibration_phase_unknown,
    )
}

data class GamepadCalibrationUiState(
    val phase: String,
    val text: String,
    val signature: String? = null,
    val canAdvance: Boolean = false,
    val canSave: Boolean = false,
)
