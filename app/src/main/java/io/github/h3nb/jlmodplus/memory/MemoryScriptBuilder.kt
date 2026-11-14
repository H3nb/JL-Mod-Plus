/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.h3nb.jlmodplus.R
import java.util.UUID
import kotlinx.serialization.json.JsonObject

internal enum class MemoryScriptBuilderMode(val recipeMode: String) {
    FREEZE("toggle"),
    FORM("form"),
    ACTIONS("oneShot"),
}

internal data class MemoryScriptBuilderAction(
    val bookmarkId: String,
    val label: String,
    val value: String = "",
    val operation: String = "set",
    val min: String = "",
    val max: String = "",
    val originalTarget: JsonObject,
)

internal data class MemoryScriptBuilderSnapshot(
    val name: String,
    val description: String,
    val actions: List<MemoryScriptBuilderAction>,
)

internal data class MemoryScriptBuilderDraft(
    val id: String,
    val mode: MemoryScriptBuilderMode,
    val name: String = "",
    val description: String = "",
    val actions: List<MemoryScriptBuilderAction> = emptyList(),
    val replacingId: String? = null,
    val baseline: MemoryScriptBuilderSnapshot? = null,
) {
    val hasChanges: Boolean get() = baseline?.let {
        name != it.name || description != it.description || actions != it.actions
    } ?: (name.isNotEmpty() || description.isNotEmpty() || actions.isNotEmpty())

    companion object {
        fun create(mode: MemoryScriptBuilderMode) = MemoryScriptBuilderDraft(
            "builder." + UUID.randomUUID(), mode,
        )
    }
}

@Composable
private fun modeTitle(mode: MemoryScriptBuilderMode): String = stringResource(when (mode) {
    MemoryScriptBuilderMode.FREEZE -> R.string.memory_scripts_mode_toggle
    MemoryScriptBuilderMode.FORM -> R.string.memory_scripts_mode_form
    MemoryScriptBuilderMode.ACTIONS -> R.string.memory_scripts_mode_once
})

@Composable
internal fun MemoryScriptTypeChooser(
    onChoose: (MemoryScriptBuilderMode) -> Unit,
    onJson: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.memory_scripts_back)) }
            Text(stringResource(R.string.memory_scripts_choose_type),
                style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.memory_scripts_choose_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (mode in MemoryScriptBuilderMode.entries) {
            val description = when (mode) {
                MemoryScriptBuilderMode.FREEZE -> R.string.memory_builder_freeze_hint
                MemoryScriptBuilderMode.FORM -> R.string.memory_builder_form_hint
                MemoryScriptBuilderMode.ACTIONS -> R.string.memory_builder_actions_hint
            }
            val icon = when (mode) {
                MemoryScriptBuilderMode.FREEZE -> R.drawable.ic_lock
                MemoryScriptBuilderMode.FORM -> R.drawable.ic_memory_editor_edit_value
                MemoryScriptBuilderMode.ACTIONS -> R.drawable.ic_auto_mode
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onChoose(mode) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painterResource(icon), contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(modeTitle(mode), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onJson),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_auto_mode), contentDescription = null)
                Text(stringResource(R.string.memory_builder_json_editor),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun MemoryScriptBuilderScreen(
    draft: MemoryScriptBuilderDraft,
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDraft: (MemoryScriptBuilderDraft) -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onEditJson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val saveFailedText = stringResource(R.string.memory_scripts_save_failed)
    var picking by remember { mutableStateOf(false) }
    var preview by remember(draft) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun generate(onReady: (String) -> Unit) {
        error = null
        actions.generateMemoryScript(draft) { document, failure ->
            if (document == null) error = failure ?: "Could not generate script"
            else onReady(document)
        }
    }
    val section = when (draft.mode) {
        MemoryScriptBuilderMode.FREEZE -> R.string.memory_builder_frozen_values
        MemoryScriptBuilderMode.FORM -> R.string.memory_builder_fields
        MemoryScriptBuilderMode.ACTIONS -> R.string.memory_builder_actions
    }
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !saving) {
                Text(stringResource(R.string.memory_scripts_back))
            }
            Text(modeTitle(draft.mode), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = {
                    generate { document ->
                        saving = true
                        actions.saveMemoryScript(document, draft.replacingId) { saved ->
                            saving = false
                            if (saved) onSaved(draft.id)
                            else error = saveFailedText
                        }
                    }
                },
                enabled = !saving && draft.name.isNotBlank() && draft.actions.isNotEmpty(),
            ) { Text(stringResource(R.string.memory_scripts_save)) }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { if (it.length <= 80) onDraft(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.memory_builder_name)) },
                enabled = !saving,
                singleLine = true, modifier = Modifier.fillMaxWidth()
                    .testTag("memory-builder-name"),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { if (it.length <= 2048) onDraft(draft.copy(description = it)) },
                label = { Text(stringResource(R.string.memory_scripts_description)) },
                enabled = !saving,
                minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(when (draft.mode) {
                MemoryScriptBuilderMode.FREEZE -> R.string.memory_builder_freeze_hint
                MemoryScriptBuilderMode.FORM -> R.string.memory_builder_form_hint
                MemoryScriptBuilderMode.ACTIONS -> R.string.memory_builder_actions_hint
            }), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(section), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = { picking = true }) {
                    Text(stringResource(R.string.memory_builder_add_bookmarks))
                }
            }
            if (draft.actions.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.memory_builder_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { actions.setVariablesTab(false) }) {
                            Text(stringResource(R.string.memory_builder_find_values))
                        }
                        TextButton(onClick = { actions.setVariablesTab(true) }) {
                            Text(stringResource(R.string.memory_builder_open_saved))
                        }
                    }
                }
            }
            draft.actions.forEach { item ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.label, modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            TextButton(enabled = !saving, onClick = {
                                onDraft(draft.copy(actions = draft.actions.filterNot {
                                    it.bookmarkId == item.bookmarkId
                                }))
                            }) { Text(stringResource(R.string.memory_editor_remove)) }
                        }
                        when (draft.mode) {
                            MemoryScriptBuilderMode.FORM -> {
                                OutlinedTextField(
                                    value = item.label,
                                    onValueChange = { changed ->
                                        if (changed.length <= 80) onDraft(draft.copy(
                                            actions = draft.actions.map {
                                                if (it.bookmarkId == item.bookmarkId)
                                                    it.copy(label = changed) else it
                                            },
                                        ))
                                    },
                                    label = { Text(stringResource(R.string.memory_builder_field_label)) },
                                    enabled = !saving,
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = item.min,
                                        onValueChange = { changed ->
                                            if (changed.length <= 20) onDraft(draft.copy(
                                                actions = draft.actions.map {
                                                    if (it.bookmarkId == item.bookmarkId)
                                                        it.copy(min = changed) else it
                                                },
                                            ))
                                        },
                                        label = { Text(stringResource(R.string.memory_builder_min)) },
                                        enabled = !saving,
                                        singleLine = true, modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = item.max,
                                        onValueChange = { changed ->
                                            if (changed.length <= 20) onDraft(draft.copy(
                                                actions = draft.actions.map {
                                                    if (it.bookmarkId == item.bookmarkId)
                                                        it.copy(max = changed) else it
                                                },
                                            ))
                                        },
                                        label = { Text(stringResource(R.string.memory_builder_max)) },
                                        enabled = !saving,
                                        singleLine = true, modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            MemoryScriptBuilderMode.FREEZE,
                            MemoryScriptBuilderMode.ACTIONS -> {
                                if (draft.mode == MemoryScriptBuilderMode.ACTIONS) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (operation in listOf("set", "add", "subtract")) {
                                            val label = when (operation) {
                                                "set" -> R.string.memory_builder_set
                                                "add" -> R.string.memory_builder_add
                                                else -> R.string.memory_builder_subtract
                                            }
                                            FilterChip(
                                                selected = item.operation == operation,
                                                enabled = !saving,
                                                onClick = { onDraft(draft.copy(
                                                    actions = draft.actions.map {
                                                        if (it.bookmarkId == item.bookmarkId)
                                                            it.copy(operation = operation) else it
                                                    },
                                                )) },
                                                label = { Text(stringResource(label)) },
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = item.value,
                                    onValueChange = { changed ->
                                        if (changed.length <= 32) onDraft(draft.copy(
                                            actions = draft.actions.map {
                                                if (it.bookmarkId == item.bookmarkId)
                                                    it.copy(value = changed) else it
                                            },
                                        ))
                                    },
                                    label = { Text(stringResource(
                                        if (draft.mode == MemoryScriptBuilderMode.FREEZE)
                                            R.string.memory_builder_keep_at
                                        else R.string.memory_builder_amount,
                                    )) },
                                    enabled = !saving,
                                    singleLine = true, modifier = Modifier.fillMaxWidth()
                                        .testTag("memory-builder-value"),
                                )
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(enabled = !saving && draft.actions.isNotEmpty(),
            onClick = { generate { preview = it } }) {
            Text(stringResource(R.string.memory_builder_view_json))
        }
    }
    if (picking) BookmarkPicker(state, draft.actions.mapTo(HashSet()) { it.bookmarkId },
        onDismiss = { picking = false },
        onOpenSaved = {
            picking = false
            actions.setVariablesTab(true)
        },
        onAdd = { added ->
            runCatching {
                val captured = actions.captureMemoryScriptBuilderActions(added)
                onDraft(draft.copy(actions = draft.actions + captured))
            }.exceptionOrNull()?.let { it.message ?: "Could not add Bookmarks" }
        })
    preview?.let { json ->
        MemoryEditorDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.memory_builder_view_json)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(json, style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { preview = null; onEditJson(json) }) {
                    Text(stringResource(R.string.memory_builder_edit_json))
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) {
                    Text(stringResource(R.string.memory_scripts_back))
                }
            },
        )
    }
}

@Composable
private fun BookmarkPicker(
    state: MemoryEditorUiState,
    used: Set<String>,
    onDismiss: () -> Unit,
    onOpenSaved: () -> Unit,
    onAdd: (List<SavedVariableRow>) -> String?,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val matches = state.savedVariables.filter {
        (query.isBlank() || it.label.contains(query, ignoreCase = true)) && it.id !in used
    }
    MemoryEditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_builder_add_bookmarks)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.memory_builder_search_bookmarks)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (matches.isEmpty()) {
                    Text(stringResource(R.string.memory_builder_no_bookmarks),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSaved) {
                        Text(stringResource(R.string.memory_builder_open_saved))
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(matches, key = SavedVariableRow::id) { row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = row.id in selected,
                                    enabled = row.hasLocator,
                                    onValueChange = { checked ->
                                        selected = if (checked) selected + row.id else selected - row.id
                                    },
                                ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = row.id in selected,
                                onCheckedChange = null, enabled = row.hasLocator)
                            Column {
                                Text(row.label)
                                Text(if (row.hasLocator) row.path
                                    else stringResource(R.string.memory_builder_needs_relink),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(),
                onClick = {
                    error = onAdd(state.savedVariables.filter { it.id in selected && it.hasLocator })
                    if (error == null) onDismiss()
                }) {
                Text(stringResource(R.string.memory_builder_add_selected))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
