/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.ControllerConfig
import io.github.h3nb.jlmodplus.input.DirectionMode
import io.github.h3nb.jlmodplus.input.StickMode

/**
 * Snapshot-only adapter for the legacy standalone analog preference preview.
 *
 * Production now places [GamepadInputPreferences] directly inside Key Input. Keeping this adapter
 * in screenshotTest source preserves the established standalone reference image until that
 * screenshot is intentionally replaced, without reintroducing the old section into production.
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
    val supported = !opaqueController && resolved.isSupported
    val compatibilityMessage = when {
        opaqueController -> stringResource(R.string.config_gamepad_opaque_summary)
        !resolved.isSupported -> stringResource(R.string.config_gamepad_unsupported_summary)
        else -> null
    }
    val options = listOf(
        stringResource(R.string.config_analog_stick_off),
        stringResource(R.string.config_gamepad_directions_four),
        stringResource(R.string.config_gamepad_directions_eight),
        stringResource(R.string.config_gamepad_directions_numeric_v2),
    )
    val selectedIndex = when {
        !resolved.enabled || resolved.leftStick.mode != StickMode.DIRECTIONS -> 0
        resolved.leftStick.directionMode == DirectionMode.FOUR -> 1
        resolved.leftStick.directionMode == DirectionMode.DIAGONAL_NUMBER -> 3
        else -> 2
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ConfigChoicePreference(
            title = stringResource(R.string.config_analog_stick),
            description = stringResource(R.string.config_analog_stick_summary),
            selected = options[selectedIndex],
            options = options,
            enabled = supported,
            message = compatibilityMessage,
            messageLevel = ConfigMessageLevel.Warning,
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
    }
}
