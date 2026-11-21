/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size

internal enum class QuickSetting {
    Resolution, Orientation, Scale, Renderer, VirtualKeys, Fps
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConfigQuickSettings(
    state: ConfigUiState,
    isProfile: Boolean,
    events: ConfigFormEvents,
    onUsePreset: () -> Unit,
    onSavePreset: () -> Unit,
    onStopFollowing: () -> Unit,
    onOpenCategory: (ConfigDestination) -> Unit,
    onOpenPerformance: () -> Unit,
    onOpenQuickSetting: (QuickSetting) -> Unit,
) {
    if (isProfile) {
        Text(stringResource(R.string.config_overview_profile_draft_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    } else {
        PresetSummary(
            state = state,
            onUsePreset = onUsePreset,
            onSavePreset = onSavePreset,
            onStopFollowing = onStopFollowing,
            events = events,
        )
    }

    ConfigQuickHeading(R.string.config_quick_settings)
    val form = state.form
    val orientation = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
        .getOrNull(form.orientation).orEmpty()
    val scale = stringArrayResource(R.array.pref_scale_type_entries)
        .getOrNull(form.screenScaleType).orEmpty()
    val renderer = stringArrayResource(R.array.pref_graphics_mode_entries)
        .getOrNull(form.graphicsMode).orEmpty()
    val tiles = listOf(
        Triple(QuickSetting.Resolution, R.string.config_screen_size,
            form.screenWidth + " × " + form.screenHeight),
        Triple(QuickSetting.Orientation, R.string.PREF_ORIENTATION, orientation),
        Triple(QuickSetting.Scale, R.string.pref_screen_scale_type, scale),
        Triple(QuickSetting.Renderer, R.string.pref_graphics_mode_title, renderer),
        Triple(QuickSetting.VirtualKeys, R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
            stringResource(if (form.showKeyboard) R.string.config_overview_enabled
                else R.string.config_overview_disabled)),
        Triple(QuickSetting.Fps, R.string.PREF_LIMIT_FPS,
            if (form.fpsLimit == "0" || form.fpsLimit.isBlank())
                stringResource(R.string.fps_limit_maximum) else form.fpsLimit + " FPS"),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 3 else 2
        FlowRow(
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tiles.forEach { (setting, title, value) ->
                Surface(
                    modifier = Modifier.weight(1f).heightIn(min = 96.dp)
                        .testTag("config_quick_" + setting.name.lowercase())
                        .clickable(role = Role.Button) {
                            if (setting == QuickSetting.Fps) onOpenPerformance()
                            else onOpenQuickSetting(setting)
                        },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                when (setting) {
                                    QuickSetting.Resolution, QuickSetting.Orientation,
                                    QuickSetting.Scale -> R.drawable.ic_config_graphics
                                    QuickSetting.Renderer, QuickSetting.Fps -> R.drawable.ic_settings
                                    QuickSetting.VirtualKeys -> R.drawable.ic_config_controls
                                },
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(value, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    ConfigQuickHeading(R.string.config_all_settings)
    ConfigDestination.entries.filter { it != ConfigDestination.Basic }.forEach { destination ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("config_category_" + destination.name.lowercase())
                .clickable(role = Role.Button) { onOpenCategory(destination) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(destination.icon), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Text(stringResource(destination.label), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ConfigQuickHeading(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun ConfigQuickSettingDialog(
    setting: QuickSetting,
    state: ConfigUiState,
    events: ConfigFormEvents,
    onDismiss: () -> Unit,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    val form = state.form
    if (setting == QuickSetting.Resolution) {
        val selected = form.screenWidth.toIntOrNull()?.let { width ->
            form.screenHeight.toIntOrNull()?.let { height -> Size(width, height) }
        }
        ScreenPresetDialog(
            presets = state.screenPresets,
            removablePresets = state.removableScreenPresets,
            selectedPreset = selected,
            onDismissRequest = onDismiss,
            onSelected = { size ->
                onDismiss()
                onFormChanged(form.toBuilder()
                    .screenWidth(size.width.toString())
                    .screenHeight(size.height.toString()).build())
            },
            onSwap = {
                onDismiss()
                onFormChanged(form.toBuilder()
                    .screenWidth(form.screenHeight).screenHeight(form.screenWidth).build())
            },
            onAdd = events::onAddResolutionPreset,
            onRemove = events::onRemoveResolutionPreset,
        )
        return
    }
    val options = when (setting) {
        QuickSetting.Orientation -> stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
        QuickSetting.Scale -> stringArrayResource(R.array.pref_scale_type_entries).toList()
        QuickSetting.Renderer -> stringArrayResource(R.array.pref_graphics_mode_entries).toList()
        QuickSetting.VirtualKeys -> listOf(
            stringResource(R.string.config_overview_enabled),
            stringResource(R.string.config_overview_disabled),
        )
        else -> return
    }
    val index = when (setting) {
        QuickSetting.Orientation -> form.orientation
        QuickSetting.Scale -> form.screenScaleType
        QuickSetting.Renderer -> form.graphicsMode
        QuickSetting.VirtualKeys -> if (form.showKeyboard) 0 else 1
        else -> 0
    }
    val title = when (setting) {
        QuickSetting.Orientation -> R.string.PREF_ORIENTATION
        QuickSetting.Scale -> R.string.pref_screen_scale_type
        QuickSetting.Renderer -> R.string.pref_graphics_mode_title
        QuickSetting.VirtualKeys -> R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS
        else -> return
    }
    ConfigChoiceDialog(
        title = stringResource(title),
        selected = options.getOrElse(index) { options.firstOrNull().orEmpty() },
        options = options,
        onDismissRequest = onDismiss,
        onSelected = { choice ->
            onDismiss()
            val builder = form.toBuilder()
            when (setting) {
                QuickSetting.Orientation -> builder.orientation(choice)
                QuickSetting.Scale -> builder.screenScaleType(choice)
                QuickSetting.Renderer -> builder.graphicsMode(choice)
                QuickSetting.VirtualKeys -> builder.showKeyboard(choice == 0)
                else -> Unit
            }
            onFormChanged(builder.build())
        },
    )
}
