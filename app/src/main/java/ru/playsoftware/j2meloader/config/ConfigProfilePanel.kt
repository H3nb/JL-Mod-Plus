/*
* Licensed under the Apache License, Version 2.0 (the "License");
*/
package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

private sealed interface TemplateNameRequest {
    data object Create : TemplateNameRequest
    data class Rename(val oldName: String) : TemplateNameRequest
}

@Composable
internal fun ConfigProfilePanel(
    status: ConfigUiState.ProfileStatus,
    templates: List<ConfigUiState.ProfileTemplate>,
    events: ConfigFormEvents,
) {
    var managerVisible by rememberSaveable { mutableStateOf(false) }
    var nameRequest by remember { mutableStateOf<TemplateNameRequest?>(null) }
    val currentTitle = when {
        status.modified && status.sourceProfile != null -> status.sourceProfile
        status.activeProfile != null -> status.activeProfile
        status.builtInDefault -> stringResource(R.string.profile_builtin_settings)
        else -> stringResource(R.string.profile_custom)
    }
    val currentSummary = when {
        status.modified -> stringResource(R.string.profile_modified_summary)
        status.activeProfile != null -> stringResource(R.string.profile_active_summary)
        status.builtInDefault -> stringResource(R.string.profile_builtin_settings_summary)
        else -> stringResource(R.string.profile_custom_summary)
    }
    val defaultSuffix = when {
        status.builtInDefault && status.defaultProfile == null -> stringResource(R.string.profile_default_badge)
        status.activeProfile != null && status.activeProfile == status.defaultProfile -> stringResource(R.string.profile_default_badge)
        status.modified && status.sourceProfile == status.defaultProfile -> stringResource(R.string.profile_default_badge)
        else -> null
    }
    val summary = if (defaultSuffix == null) currentSummary else "$currentSummary · $defaultSuffix"

    ConfigSection(title = stringResource(R.string.profiles)) {
        ConfigValuePreference(
            title = currentTitle,
            description = summary,
            value = stringResource(R.string.profile_choose_or_manage),
            onClick = { managerVisible = true },
        )
        if (status.modified && status.sourceProfile != null) {
            ConfigActionPreference(
                title = stringResource(R.string.profile_update_template),
                description = stringResource(R.string.profile_update_template_summary, status.sourceProfile),
                onClick = { events.onUpdateTemplate(status.sourceProfile) },
            )
            ConfigActionPreference(
                title = stringResource(R.string.profile_save_as_new_template),
                description = stringResource(R.string.profile_save_as_new_template_summary),
                onClick = { nameRequest = TemplateNameRequest.Create },
            )
        } else if (status.activeProfile == null && !status.builtInDefault) {
            ConfigActionPreference(
                title = stringResource(R.string.profile_save_as_new_template),
                description = stringResource(R.string.profile_save_as_new_template_summary),
                onClick = { nameRequest = TemplateNameRequest.Create },
            )
        }
    }

    if (managerVisible) {
        ConfigTemplateManagerDialog(
            status = status,
            templates = templates,
            events = events,
            onDismissRequest = { managerVisible = false },
            onCreate = { nameRequest = TemplateNameRequest.Create },
            onRename = { name -> nameRequest = TemplateNameRequest.Rename(name) },
        )
    }

    nameRequest?.let { request ->
        ConfigTemplateNameDialog(
            title = stringResource(
            if (request is TemplateNameRequest.Rename) R.string.action_context_rename
            else R.string.profile_save_as_new_template,
            ),
            initialName = (request as? TemplateNameRequest.Rename)?.oldName.orEmpty(),
            existingNames = templates.map { it.name },
            onDismissRequest = { nameRequest = null },
            onConfirm = { name ->
                nameRequest = null
                when (request) {
                    TemplateNameRequest.Create -> events.onSaveTemplate(name)
                    is TemplateNameRequest.Rename -> events.onRenameTemplate(request.oldName, name)
                }
            },
        )
    }
}

@Composable
private fun ConfigTemplateManagerDialog(
    status: ConfigUiState.ProfileStatus,
    templates: List<ConfigUiState.ProfileTemplate>,
    events: ConfigFormEvents,
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
) {
    var actionTarget by remember { mutableStateOf<ConfigUiState.ProfileTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<ConfigUiState.ProfileTemplate?>(null) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).heightIn(max = 680.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.profile_templates_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.profile_templates_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        item(key = "__built_in__") {
                            TemplateRow(
                                name = stringResource(R.string.profile_builtin_settings),
                                summary = stringResource(R.string.profile_builtin_settings_summary),
                                isActive = status.builtInDefault,
                                isModifiedSource = false,
                                isDefault = status.defaultProfile == null,
                                onClick = {
                                    events.onApplyBuiltInTemplate()
                                    onDismissRequest()
                                },
                                onMore = if (status.defaultProfile == null) null else ({ events.onSetDefaultTemplate(null) }),
                                builtIn = true,
                            )
                        }
                        items(templates, key = { it.name }) { template ->
                            TemplateRow(
                                name = template.name,
                                summary = stringResource(R.string.profile_template_summary),
                                isActive = status.activeProfile == template.name,
                                isModifiedSource = status.modified && status.sourceProfile == template.name,
                                isDefault = template.isDefault,
                                onClick = {
                                    events.onApplyTemplate(template.name)
                                    onDismissRequest()
                                },
                                onMore = { actionTarget = template },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCreate) { Text(stringResource(R.string.profile_save_current_template)) }
                        TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
        }
    }

    actionTarget?.let { template ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (!template.isDefault) {
                        TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            actionTarget = null
                            events.onSetDefaultTemplate(template.name)
                        },
                        ) { Text(stringResource(R.string.set_as_default), modifier = Modifier.fillMaxWidth()) }
                    }
                    if (status.modified && status.sourceProfile == template.name) {
                        TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            actionTarget = null
                            events.onUpdateTemplate(template.name)
                        },
                        ) { Text(stringResource(R.string.profile_update_template), modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        actionTarget = null
                        onRename(template.name)
                    },
                    ) { Text(stringResource(R.string.action_context_rename), modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        actionTarget = null
                        deleteTarget = template
                    },
                    ) { Text(stringResource(R.string.action_context_delete), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
        )
    }

    deleteTarget?.let { template ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.profile_delete_template_message, template.name)) },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(
                onClick = {
                    deleteTarget = null
                    events.onDeleteTemplate(template.name)
                },
                ) { Text(stringResource(R.string.action_context_delete), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun TemplateRow(
    name: String,
    summary: String,
    isActive: Boolean,
    isModifiedSource: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    builtIn: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                text = buildString {
                    append(summary)
                    if (isActive) append(" · ").append(stringResource(R.string.profile_active_badge))
                    else if (isModifiedSource) append(" · ").append(stringResource(R.string.profile_modified_badge))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isDefault) {
            Text(
                text = stringResource(R.string.profile_default_badge_short),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (onMore != null) {
            if (builtIn) {
                TextButton(onClick = onMore) { Text(stringResource(R.string.set_as_default)) }
            } else {
                IconButton(onClick = onMore) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigTemplateNameDialog(
    title: String,
    initialName: String,
    existingNames: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmed = value.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) && !it.equals(initialName, ignoreCase = false) }
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
                label = { Text(stringResource(R.string.profile_name_label)) },
                isError = duplicate,
                )
                if (duplicate) {
                    Text(
                    text = stringResource(R.string.profile_name_exists),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(trimmed) }) { Text(stringResource(R.string.save)) }
        },
    )
}
