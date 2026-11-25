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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size

internal enum class QuickSetting {
    Resolution, Orientation, RenderResolution, Scale, Renderer, VirtualKeys
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
    onOpenQuickSetting: (QuickSetting) -> Unit,
    onRequestAction: (ConfigAction) -> Unit,
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
    val renderResolutions = renderResolutionOptions()
    val tiles = listOf(
        Triple(QuickSetting.Resolution, R.string.config_screen_size,
            form.screenWidth + " × " + form.screenHeight),
        Triple(QuickSetting.Orientation, R.string.PREF_ORIENTATION, orientation),
        Triple(QuickSetting.RenderResolution, R.string.internal_resolution_title,
            renderResolutions.getOrElse(form.internalResolutionScale) { renderResolutions[1] }),
        Triple(QuickSetting.Scale, R.string.pref_screen_scale_type, scale),
        Triple(QuickSetting.Renderer, R.string.pref_graphics_mode_title, renderer),
        Triple(QuickSetting.VirtualKeys, R.string.config_controls_virtual_keyboard,
            stringResource(if (form.showKeyboard) R.string.config_overview_enabled
                else R.string.config_overview_disabled)),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val minTileWidth = 154.dp * fontScale
        val columns = ((maxWidth + 8.dp) / (minTileWidth + 8.dp)).toInt().coerceIn(1, 3)
        FlowRow(
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tiles.forEach { (setting, title, value) ->
                Surface(
                    modifier = Modifier.weight(1f).fillMaxRowHeight().heightIn(min = 72.dp)
                        .testTag("config_quick_" + setting.name.lowercase())
                        .clickable(role = Role.Button) {
                            onOpenQuickSetting(setting)
                        },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                when (setting) {
                                    QuickSetting.Resolution -> R.drawable.ic_config_graphics
                                    QuickSetting.Orientation -> R.drawable.ic_screen_lock_rotation
                                    QuickSetting.Scale -> R.drawable.ic_auto_mode
                                    QuickSetting.Renderer -> R.drawable.ic_settings
                                    QuickSetting.VirtualKeys -> R.drawable.ic_virtual_controls
                                    QuickSetting.RenderResolution -> R.drawable.ic_editor_group_resize
                                },
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(value, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    ConfigQuickHeading(R.string.config_all_settings)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            ConfigDestination.entries.filter { it != ConfigDestination.Basic }
                .forEachIndexed { index, destination ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("config_category_" + destination.name.lowercase())
                            .clickable(role = Role.Button) { onOpenCategory(destination) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(destination.icon), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp))
                        Text(stringResource(destination.label), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge)
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                }
        }
    }
    ConfigGlobalActionsSection(isProfile, onRequestAction)
}

@Composable
private fun ConfigQuickHeading(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
            .padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
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
                onFormChanged(form.toBuilder()
                    .screenWidth(size.width.toString())
                    .screenHeight(size.height.toString()).build())
            },
            onAdd = events::onAddResolutionPreset,
            onRemove = events::onRemoveResolutionPreset,
        )
        return
    }
    val options = when (setting) {
        QuickSetting.Orientation -> stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES).toList()
        QuickSetting.RenderResolution -> renderResolutionOptions()
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
        QuickSetting.RenderResolution -> form.internalResolutionScale
        QuickSetting.Scale -> form.screenScaleType
        QuickSetting.Renderer -> form.graphicsMode
        QuickSetting.VirtualKeys -> if (form.showKeyboard) 0 else 1
        else -> 0
    }
    val title = when (setting) {
        QuickSetting.Orientation -> R.string.PREF_ORIENTATION
        QuickSetting.RenderResolution -> R.string.internal_resolution_title
        QuickSetting.Scale -> R.string.pref_screen_scale_type
        QuickSetting.Renderer -> R.string.pref_graphics_mode_title
        QuickSetting.VirtualKeys -> R.string.config_controls_virtual_keyboard
        else -> return
    }
    ConfigChoiceDialog(
        title = stringResource(title),
        description = if (setting == QuickSetting.RenderResolution)
            stringResource(R.string.internal_resolution_description) else null,
        selected = options.getOrElse(index) {
            if (setting == QuickSetting.RenderResolution) options[1] else options.firstOrNull().orEmpty()
        },
        options = options,
        onDismissRequest = onDismiss,
        onSelected = { choice ->
            onDismiss()
            val builder = form.toBuilder()
            when (setting) {
                QuickSetting.Orientation -> builder.orientation(choice)
                QuickSetting.RenderResolution -> builder.internalResolutionScale(choice)
                QuickSetting.Scale -> builder.screenScaleType(choice)
                QuickSetting.Renderer -> builder.graphicsMode(choice)
                QuickSetting.VirtualKeys -> builder.showKeyboard(choice == 0)
                else -> Unit
            }
            onFormChanged(builder.build())
        },
    )
}

@Composable
internal fun renderResolutionOptions(): List<String> =
    listOf(stringResource(R.string.internal_resolution_auto)) + (1..10).map { "${it}×" }
