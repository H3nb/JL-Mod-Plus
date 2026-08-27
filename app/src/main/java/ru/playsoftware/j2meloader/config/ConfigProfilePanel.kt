/*
* Licensed under the Apache License, Version 2.0 (the "License");
*/
package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.rememberLazyListCanScrollForward

@Composable
internal fun ConfigProfilePanel(
    status: ConfigUiState.ProfileStatus,
    templates: List<ConfigUiState.ProfileTemplate>,
    events: ConfigFormEvents,
) {
    var managerVisible by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var updateTarget by remember { mutableStateOf<String?>(null) }

    val settingsSource = when {
        status.settingsProfile != null -> status.settingsProfile
        status.settingsBuiltIn -> stringResource(R.string.profile_builtin_settings)
        else -> stringResource(R.string.profile_custom)
    }
    val keyboardSource = status.keyboardProfile ?: stringResource(R.string.profile_app_specific)
    val settingsUpdateProfile = status.settingsProfile?.takeIf { status.settingsModified }
    val keyboardUpdateProfile = status.keyboardProfile
        ?.takeIf { status.keyboardModified && it != settingsUpdateProfile }
    val hasLocalComponent =
        (status.settingsProfile == null && !status.settingsBuiltIn) || status.keyboardProfile == null

    ConfigSection(title = stringResource(R.string.profile_section_title)) {
        ProfileComponentRow(
            title = stringResource(R.string.action_settings),
            source = settingsSource,
            modified = status.settingsModified,
            updateProfile = settingsUpdateProfile,
            keepLabel = if (status.settingsModified &&
                (status.settingsProfile != null || status.settingsBuiltIn)
            ) {
                stringResource(R.string.profile_keep_settings_for_app)
            } else {
                null
            },
            onRequestUpdate = { updateTarget = it },
            onKeep = events::onKeepSettingsForApp,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
        ProfileComponentRow(
            title = stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
            source = keyboardSource,
            modified = status.keyboardModified,
            updateProfile = keyboardUpdateProfile,
            keepLabel = if (status.keyboardModified && status.keyboardProfile != null) {
                stringResource(R.string.profile_keep_keyboard_for_app)
            } else {
                null
            },
            onRequestUpdate = { updateTarget = it },
            onKeep = events::onKeepKeyboardForApp,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 6.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.hasModifiedComponents() || hasLocalComponent) {
                TextButton(onClick = events::onSaveAsProfile) {
                    Text(stringResource(R.string.profile_save_as_profile))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { managerVisible = true }) {
                Text(stringResource(R.string.profile_change_profile))
            }
        }
    }

    if (managerVisible) {
        ConfigProfileManagerDialog(
            status = status,
            templates = templates,
            events = events,
            onDismissRequest = { managerVisible = false },
            onCreate = {
                managerVisible = false
                events.onSaveAsProfile()
            },
            onRename = { name -> renameTarget = name },
            onRequestUpdate = { name -> updateTarget = name },
        )
    }

    renameTarget?.let { oldName ->
        ConfigProfileNameDialog(
            title = stringResource(R.string.action_context_rename),
            initialName = oldName,
            existingNames = templates.map { it.name },
            onDismissRequest = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                events.onRenameTemplate(oldName, name)
            },
        )
    }

    updateTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { updateTarget = null },
            title = { Text(stringResource(R.string.profile_update_global_title, name)) },
            text = { Text(stringResource(R.string.profile_update_global_message)) },
            dismissButton = {
                TextButton(onClick = { updateTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    updateTarget = null
                    events.onUpdateTemplate(name)
                }) {
                    Text(stringResource(R.string.profile_update_profile))
                }
            },
        )
    }
}

@Composable
private fun ProfileComponentRow(
    title: String,
    source: String,
    modified: Boolean,
    updateProfile: String?,
    keepLabel: String?,
    onRequestUpdate: (String) -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (modified) {
                Text(
                    text = stringResource(R.string.profile_modified_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (updateProfile != null || keepLabel != null) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    if (updateProfile != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_update_profile)) },
                            onClick = {
                                expanded = false
                                onRequestUpdate(updateProfile)
                            },
                        )
                    }
                    if (keepLabel != null) {
                        DropdownMenuItem(
                            text = { Text(keepLabel) },
                            onClick = {
                                expanded = false
                                onKeep()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigProfileManagerDialog(
    status: ConfigUiState.ProfileStatus,
    templates: List<ConfigUiState.ProfileTemplate>,
    events: ConfigFormEvents,
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onRequestUpdate: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ConfigUiState.ProfileTemplate?>(null) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 560.dp)
                    .heightIn(max = 680.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.profile_manager_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCreate) {
                            Text(stringResource(R.string.profile_new_profile))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    val listState = rememberLazyListState()
                    val canScrollForward = rememberLazyListCanScrollForward(listState)
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                        ) {
                            item(key = "__built_in__") {
                                BuiltInProfileRow(
                                    isInUse = status.settingsBuiltIn,
                                    isModified = status.settingsBuiltIn && status.settingsModified,
                                    isDefault = status.defaultProfile == null,
                                    onApply = {
                                        events.onApplyBuiltInTemplate()
                                        onDismissRequest()
                                    },
                                    onSetDefault = if (status.defaultProfile == null) null else ({
                                        events.onSetDefaultTemplate(null)
                                    }),
                                )
                            }
                            items(templates, key = { it.name }) { profile ->
                                UserProfileRow(
                                    profile = profile,
                                    isInUse = status.usesProfile(profile.name),
                                    isModified = status.isProfileModified(profile.name),
                                    events = events,
                                    onDismissManager = onDismissRequest,
                                    onRename = onRename,
                                    onRequestUpdate = onRequestUpdate,
                                    onRequestDelete = { deleteTarget = it },
                                )
                            }
                        }
                        ScrollableContentHint(
                            visible = canScrollForward,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.profile_delete_profile_message, profile.name)) },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        events.onDeleteTemplate(profile.name)
                    },
                ) {
                    Text(
                        stringResource(R.string.action_context_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun BuiltInProfileRow(
    isInUse: Boolean,
    isModified: Boolean,
    isDefault: Boolean,
    onApply: () -> Unit,
    onSetDefault: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    ProfileManagerRowContent(
        name = stringResource(R.string.profile_builtin_settings),
        summary = stringResource(R.string.profile_builtin_settings_summary),
        isInUse = isInUse,
        isModified = isModified,
        isDefault = isDefault,
        onClick = onApply,
        trailing = if (onSetDefault == null) null else {
            {
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.more),
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.set_as_default)) },
                            onClick = {
                                expanded = false
                                onSetDefault()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun UserProfileRow(
    profile: ConfigUiState.ProfileTemplate,
    isInUse: Boolean,
    isModified: Boolean,
    events: ConfigFormEvents,
    onDismissManager: () -> Unit,
    onRename: (String) -> Unit,
    onRequestUpdate: (String) -> Unit,
    onRequestDelete: (ConfigUiState.ProfileTemplate) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ProfileManagerRowContent(
        name = profile.name,
        summary = profileSummary(profile),
        isInUse = isInUse,
        isModified = isModified,
        isDefault = profile.isDefault,
        onClick = {
            events.onApplyTemplate(profile.name)
            onDismissManager()
        },
        trailing = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    if (profile.hasSettings && profile.hasKeyboard) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_apply_settings_only)) },
                            onClick = {
                                expanded = false
                                events.onApplyTemplateComponents(profile.name, true, false)
                                onDismissManager()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_apply_keyboard_only)) },
                            onClick = {
                                expanded = false
                                events.onApplyTemplateComponents(profile.name, false, true)
                                onDismissManager()
                            },
                        )
                    }
                    if (isModified) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_update_profile)) },
                            onClick = {
                                expanded = false
                                onRequestUpdate(profile.name)
                            },
                        )
                    }
                    if (!profile.isDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.set_as_default)) },
                            onClick = {
                                expanded = false
                                events.onSetDefaultTemplate(profile.name)
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_context_rename)) },
                        onClick = {
                            expanded = false
                            onRename(profile.name)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_context_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            expanded = false
                            onRequestDelete(profile)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun ProfileManagerRowContent(
    name: String,
    summary: String,
    isInUse: Boolean,
    isModified: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, top = 11.dp, end = 8.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isInUse) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isModified) {
                    "$summary · ${stringResource(R.string.profile_modified_badge)}"
                } else {
                    summary
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (isInUse) {
                Text(
                    text = stringResource(R.string.profile_in_use),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isDefault) {
                Text(
                    text = stringResource(R.string.profile_default_badge_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun profileSummary(profile: ConfigUiState.ProfileTemplate): String {
    val settings = if (profile.hasSettings) stringResource(R.string.action_settings) else null
    val keyboard = if (profile.hasKeyboard) stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS) else null
    return listOfNotNull(settings, keyboard).takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
        ?: stringResource(R.string.profile_template_summary)
}

@Composable
private fun ConfigProfileNameDialog(
    title: String,
    initialName: String,
    existingNames: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmed = value.trim()
    val duplicate = existingNames.any {
        it.equals(trimmed, ignoreCase = true) && !it.equals(initialName, ignoreCase = false)
    }
    val valid = trimmed.isNotEmpty() && !duplicate && trimmed != initialName
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.profile_name_label_v2)) },
                    isError = duplicate,
                )
                if (duplicate) {
                    Text(
                        text = stringResource(R.string.profile_name_exists_v2),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(trimmed) }) {
                Text(stringResource(R.string.save))
            }
        },
    )
}
