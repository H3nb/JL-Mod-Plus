/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

/** Compact tab entry; its color chips are decorative, not five competing touch targets. */
@Composable
internal fun ConfigControlsColorsEntry(form: ConfigFormState, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("controls_colors_entry")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = stringResource(R.string.config_controls_virtual_keyboard_colors),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                form.vkForeground,
                form.vkBackground,
                form.vkSelectedForeground,
                form.vkSelectedBackground,
                form.vkOutline,
            ).forEach { value ->
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .background(controlsColor(value), MaterialTheme.shapes.extraSmall)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
                )
            }
        }
    }
}

/** The MIDlet/profile draft and picker remain owned by ConfigActivity; this page owns only navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigControlsColorsPage(
    form: ConfigFormState,
    onBack: () -> Unit,
    onColorPicker: (ConfigFormEvents.ColorField) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                title = { Text(stringResource(R.string.config_controls_virtual_keyboard_colors)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
            ) {
                Column {
                    VirtualControlsColorPreview(form)
                    HorizontalDivider()
                    ColorMatrix(
                        form = form,
                        onColorPicker = onColorPicker,
                    )
                }
            }
            if (form.controlSkinSetId != null) {
                Text(
                    text = stringResource(R.string.config_controls_color_skin_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorMatrix(
    form: ConfigFormState,
    onColorPicker: (ConfigFormEvents.ColorField) -> Unit,
) {
    val normal = stringResource(R.string.config_virtual_controls_preview_normal)
    val pressed = stringResource(R.string.config_virtual_controls_preview_pressed)
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1.3f))
            Text(normal, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(pressed, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        ColorMatrixRow(
            title = stringResource(R.string.config_controls_color_labels),
            normal = form.vkForeground,
            pressed = form.vkSelectedForeground,
            normalLabel = normal,
            pressedLabel = pressed,
            normalTag = "color_normal_labels",
            pressedTag = "color_pressed_labels",
            onNormal = { onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND) },
            onPressed = { onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND) },
        )
        HorizontalDivider()
        ColorMatrixRow(
            title = stringResource(R.string.config_controls_color_buttons),
            normal = form.vkBackground,
            pressed = form.vkSelectedBackground,
            normalLabel = normal,
            pressedLabel = pressed,
            normalTag = "color_normal_button",
            pressedTag = "color_pressed_button",
            onNormal = { onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND) },
            onPressed = { onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND) },
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.PREF_VK_OUTLINE),
                modifier = Modifier.weight(1.3f),
                style = MaterialTheme.typography.bodyMedium,
            )
            ColorMatrixSwatch(
                value = form.vkOutline,
                label = stringResource(R.string.PREF_VK_OUTLINE),
                tag = "color_outline",
                modifier = Modifier.weight(2f),
                onClick = { onColorPicker(ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_OUTLINE) },
            )
        }
    }
}

@Composable
private fun ColorMatrixRow(
    title: String,
    normal: String,
    pressed: String,
    normalLabel: String,
    pressedLabel: String,
    normalTag: String,
    pressedTag: String,
    onNormal: () -> Unit,
    onPressed: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1.3f),
            style = MaterialTheme.typography.bodyMedium,
        )
        ColorMatrixSwatch(
            normal, "$title, $normalLabel", normalTag, Modifier.weight(1f), onNormal,
        )
        ColorMatrixSwatch(
            pressed, "$title, $pressedLabel", pressedTag, Modifier.weight(1f), onPressed,
        )
    }
}

@Composable
private fun ColorMatrixSwatch(
    value: String,
    label: String,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = "$label, #$value" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(controlsColor(value), MaterialTheme.shapes.extraSmall)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
        )
    }
}

private fun controlsColor(value: String): Color {
    val rgb = value.trim().removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or rgb).toInt())
}
