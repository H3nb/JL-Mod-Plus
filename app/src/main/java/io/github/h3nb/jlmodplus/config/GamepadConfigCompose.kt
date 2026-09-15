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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.ControllerConfig
import io.github.h3nb.jlmodplus.input.DirectionMode
import io.github.h3nb.jlmodplus.input.StickMode
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

/**
 * Compact MIDlet analog configuration.
 *
 * Digital keyboard, phone-key and gamepad-button mappings deliberately remain in KeyMapper. This
 * surface only controls how the primary physical analog stick becomes guest directions. It does
 * not depend on a controller being connected, so a profile can be prepared before runtime.
 * Existing advanced/unknown controller fields are preserved unless the user explicitly changes
 * this one setting.
 */
@Suppress("UNUSED_PARAMETER")
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
    val supported = !opaqueController && resolved.isSupported
    val options = listOf(
        androidx.compose.ui.res.stringResource(R.string.config_analog_stick_off),
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_four),
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_eight),
        androidx.compose.ui.res.stringResource(R.string.config_gamepad_directions_numeric_v2),
    )
    val selectedIndex = when {
        !resolved.enabled || resolved.leftStick.mode != StickMode.DIRECTIONS -> 0
        resolved.leftStick.directionMode == DirectionMode.FOUR -> 1
        resolved.leftStick.directionMode == DirectionMode.DIAGONAL_NUMBER -> 3
        else -> 2
    }

    ConfigChoicePreference(
        title = androidx.compose.ui.res.stringResource(R.string.config_analog_stick),
        description = androidx.compose.ui.res.stringResource(R.string.config_analog_stick_summary),
        selected = options[selectedIndex],
        options = options,
        enabled = supported,
        onSelected = { index ->
            if (!supported) return@ConfigChoicePreference
            val nextLeft = when (index) {
                0 -> resolved.leftStick.copy(mode = StickMode.UNASSIGNED)
                1 -> resolved.leftStick.copy(
                    mode = StickMode.DIRECTIONS,
                    directionMode = DirectionMode.FOUR,
                )
                3 -> resolved.leftStick.copy(
                    mode = StickMode.DIRECTIONS,
                    directionMode = DirectionMode.DIAGONAL_NUMBER,
                )
                else -> resolved.leftStick.copy(
                    mode = StickMode.DIRECTIONS,
                    directionMode = DirectionMode.EIGHT,
                )
            }
            val next = resolved.toBuilder()
                .enabled(true)
                .leftStick(nextLeft)
                .buildOrNull()
                ?: resolved
            onFormChanged(form.toBuilder().controller(next.toJson()).build())
        },
    )

    if (!supported) {
        ConfigMessageBlock(
            androidx.compose.ui.res.stringResource(
                if (opaqueController) R.string.config_gamepad_opaque_summary
                else R.string.config_gamepad_unsupported_summary,
            ),
            ConfigMessageLevel.Warning,
        )
    }
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
                androidx.compose.ui.res.stringResource(R.string.config_controller_help_body_compact),
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
