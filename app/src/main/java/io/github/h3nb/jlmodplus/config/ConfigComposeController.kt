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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.TransientNoticeHost
import io.github.h3nb.jlmodplus.ui.TransientNoticeState

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
