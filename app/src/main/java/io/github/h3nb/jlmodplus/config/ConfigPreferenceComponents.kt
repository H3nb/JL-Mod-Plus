/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.PreferenceNavigationRow
import io.github.h3nb.jlmodplus.ui.PreferenceSwitchRow
import io.github.h3nb.jlmodplus.ui.PreferenceTextContent
import io.github.h3nb.jlmodplus.ui.PreferenceChevron
import io.github.h3nb.jlmodplus.ui.PreferenceNotice
import io.github.h3nb.jlmodplus.ui.PreferenceMessageLevel

internal typealias ConfigMessageLevel = PreferenceMessageLevel


internal data class ConfigSectionReveal(val title: String, val onReached: () -> Unit)
internal val LocalConfigSectionReveal = compositionLocalOf<ConfigSectionReveal?> { null }

@Composable
internal fun ConfigSection(
    title: String,
    highlighted: Boolean = false,
    accentTitle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal = LocalConfigSectionReveal.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal, title) {
        if (reveal?.title == title) {
            requester.bringIntoView()
            reveal.onReached()
        }
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (accentTitle) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            border = if (highlighted) BorderStroke(
                1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
            ) else null,
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun ConfigNavigationPreference(
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    PreferenceNavigationRow(title = title, value = value, onClick = onClick)
}

/** One accessible target for a preference that opens an editor or action. */
@Composable
internal fun ConfigCompactValuePreference(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    description: String? = null,
) {
    PreferenceNavigationRow(
        title = title,
        value = value,
        description = description,
        enabled = enabled,
        destructive = destructive,
        onClick = onClick,
        modifier = modifier,
        minHeight = 56.dp,
        verticalPadding = 8.dp,
    )
}

@Composable
internal fun ConfigValuePreference(
    title: String,
    description: String?,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
    compact: Boolean = true,
    showInlineDescription: Boolean = true,
) {
    Column(modifier = if (compact) modifier else modifier.fillMaxWidth()) {
        if (compact) {
            ConfigCompactValuePreference(
                title = title,
                value = value,
                description = if (showInlineDescription) description else null,
                enabled = enabled,
                onClick = onClick,
            )
        } else {
            PreferenceNavigationRow(
                title = title,
                value = value,
                description = description,
                enabled = enabled,
                onClick = onClick,
            )
        }
        if (message != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                PreferenceNotice(message, messageLevel)
            }
        }
    }
}

@Composable
internal fun ConfigSwitchPreference(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PreferenceSwitchRow(
            title = title,
            description = description,
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        if (message != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                PreferenceNotice(message, messageLevel)
            }
        }
    }
}

@Composable
internal fun ConfigChoicePreference(
    title: String,
    description: String?,
    selected: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
    onImport: (() -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    compact: Boolean = true,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<String?>(null) }
    var deleteFile by remember { mutableStateOf<String?>(null) }
    ConfigValuePreference(
        title = title,
        description = description,
        value = selected,
        enabled = enabled && options.isNotEmpty(),
        message = message,
        messageLevel = messageLevel,
        compact = compact,
        showInlineDescription = false,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )
    if (dialogVisible) {
        ConfigChoiceDialog(
            title = title,
            description = description,
            selected = selected,
            options = options,
            onDismissRequest = { dialogVisible = false },
            onSelected = { index ->
                dialogVisible = false
                onSelected(index)
            },
            onImport = onImport?.let { action ->
                { dialogVisible = false; action() }
            },
            onRename = onRename?.let {
                { name: String -> dialogVisible = false; renameFile = name }
            },
            onDelete = onDelete?.let {
                { name: String -> dialogVisible = false; deleteFile = name }
            },
        )
    }
    renameFile?.let { file ->
        val extension = file.substringAfterLast('.', "")
        val originalStem = if (extension.isEmpty()) file else file.dropLast(extension.length + 1)
        var draft by remember(file) { mutableStateOf(originalStem) }
        AlertDialog(
            onDismissRequest = { renameFile = null },
            title = { Text(stringResource(R.string.action_context_rename)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.config_asset_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (extension.isNotEmpty()) Text(".$extension")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank() && draft.trim() != originalStem,
                    onClick = {
                        renameFile = null
                        onRename?.invoke(file, draft)
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameFile = null }) {
                    Text(stringResource(R.string.CANCEL_CMD))
                }
            },
        )
    }
    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.config_asset_delete_confirmation, file)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFile = null
                    onDelete?.invoke(file)
                }) { Text(stringResource(R.string.action_context_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFile = null }) {
                    Text(stringResource(R.string.CANCEL_CMD))
                }
            },
        )
    }
}

@Composable
internal fun ConfigNumberPreference(
    title: String,
    description: String,
    value: String,
    fallbackLabel: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueSuffix: String? = null,
    resetValue: String? = null,
    validIntegerRange: IntRange? = null,
    compact: Boolean = true,
) {
    var dialogVisible by remember(value) { mutableStateOf(false) }
    val display = buildString {
        append(value.ifEmpty { fallbackLabel })
        if (value.isNotEmpty() && valueSuffix != null) {
            if (valueSuffix != "%") append(" ")
            append(valueSuffix)
        }
    }
    ConfigValuePreference(
        title = title,
        description = description,
        value = display,
        compact = compact,
        showInlineDescription = false,
        enabled = enabled,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )
    if (dialogVisible) {
        ConfigNumberDialog(
            title = title,
            description = description,
            initialValue = value,
            label = null,
            keyboardType = keyboardType,
            valueSuffix = valueSuffix,
            resetValue = resetValue,
            validIntegerRange = validIntegerRange,
            onDismissRequest = { dialogVisible = false },
            onConfirm = { next ->
                dialogVisible = false
                onValueChange(next)
            },
        )
    }
}

@Composable
internal fun ConfigSliderPreference(
    title: String,
    description: String,
    value: Int,
    valueRange: IntRange,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    valueSuffix: String = "",
) {
    var dialogVisible by remember(value) { mutableStateOf(false) }
    ConfigValuePreference(
        title = title,
        description = description,
        value = "$value$valueSuffix",
        compact = compact,
        showInlineDescription = false,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )
    if (dialogVisible) {
        ConfigSliderDialog(
            title = title,
            description = description,
            initialValue = value,
            valueRange = valueRange,
            onDismissRequest = { dialogVisible = false },
            onConfirm = { next ->
                dialogVisible = false
                onSelected(next)
            },
        )
    }
}

@Composable
internal fun ConfigColorPreference(
    title: String,
    description: String?,
    value: String,
    onClick: () -> Unit,
    displayLabel: String? = null,
) {
    PreferenceNavigationRow(
        title = title,
        value = displayLabel ?: "#${value.ifEmpty { "000000" }}",
        description = description,
        onClick = onClick,
        trailingContent = if (displayLabel == null) {
            {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(configColor(value))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
                )
            }
        } else null,
    )
}

@Composable
internal fun ConfigActionPreference(
    title: String,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreferenceTextContent(
                title = title,
                description = description,
                destructive = destructive,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            PreferenceChevron(enabled = enabled)
        }
    }
}

@Composable
internal fun ConfigDisclosurePreference(
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { onExpandedChange(!expanded) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreferenceTextContent(title = title, description = description, modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down,
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ConfigMessageBlock(text: String, level: ConfigMessageLevel = ConfigMessageLevel.Info) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        PreferenceNotice(text, level)
    }
}

private fun configColor(value: String): Color {
    val rgb = value.trim().removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF) ?: return Color.Black
    return Color((0xFF000000L or rgb).toInt())
}
