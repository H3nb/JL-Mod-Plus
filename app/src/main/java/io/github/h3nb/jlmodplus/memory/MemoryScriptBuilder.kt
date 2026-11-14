/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.h3nb.jlmodplus.R
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

internal data class MemoryScriptBuilderDraft(
    val id: String,
    val mode: MemoryScriptBuilderMode,
    val name: String = "",
    val description: String = "",
    val actions: List<MemoryScriptBuilderAction> = emptyList(),
) {
    val hasChanges: Boolean get() = name.isNotEmpty() || description.isNotEmpty() || actions.isNotEmpty()

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
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onChoose(mode) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(modeTitle(mode), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onJson, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.memory_builder_json_editor))
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
    val context = LocalContext.current
    val saveFailedText = stringResource(R.string.memory_scripts_save_failed)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var preview by remember(draft) { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val document = exportText
        exportText = null
        if (uri != null && document != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(document.toByteArray(Charsets.UTF_8))
                    } ?: kotlin.error("Cannot write the selected file")
                }
            }
            result.exceptionOrNull()?.let { error = it.message ?: "Could not export JSON" }
        }
    }
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
                        actions.saveMemoryScript(document, null) { saved ->
                            saving = false
                            if (saved) onSaved(draft.id)
                            else error = saveFailedText
                        }
                    }
                },
                enabled = !saving && draft.name.isNotBlank() && draft.actions.isNotEmpty(),
            ) { Text(stringResource(R.string.memory_scripts_save)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { if (it.length <= 80) onDraft(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.memory_builder_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
                    .testTag("memory-builder-name"),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { if (it.length <= 2048) onDraft(draft.copy(description = it)) },
                label = { Text(stringResource(R.string.memory_scripts_description)) },
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
                Text(stringResource(R.string.memory_builder_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
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
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(enabled = !saving && draft.actions.isNotEmpty(),
                onClick = { generate { preview = it } }) {
                Text(stringResource(R.string.memory_builder_view_json))
            }
            OutlinedButton(enabled = !saving && draft.actions.isNotEmpty(),
                onClick = {
                    generate { document ->
                        exportText = document
                        val name = draft.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
                            .trim('-').ifBlank { "script" }.take(60)
                        export.launch(name + ".json")
                    }
                }) { Text(stringResource(R.string.memory_builder_export_json)) }
        }
    }
    if (picking) BookmarkPicker(state, draft.actions.mapTo(HashSet()) { it.bookmarkId },
        onDismiss = { picking = false },
        onAdd = { added ->
            onDraft(draft.copy(actions = draft.actions + added.map { row ->
                MemoryScriptBuilderAction(bookmarkId = row.id, label = row.label)
            }))
            picking = false
        })
    preview?.let { json ->
        AlertDialog(
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
    onAdd: (List<SavedVariableRow>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val matches = state.savedVariables.filter {
        (query.isBlank() || it.label.contains(query, ignoreCase = true)) && it.id !in used
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_builder_add_bookmarks)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.memory_builder_search_bookmarks)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (matches.isEmpty()) Text(stringResource(R.string.memory_builder_no_bookmarks),
                    style = MaterialTheme.typography.bodySmall)
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
                onClick = { onAdd(state.savedVariables.filter { it.id in selected && it.hasLocator }) }) {
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
