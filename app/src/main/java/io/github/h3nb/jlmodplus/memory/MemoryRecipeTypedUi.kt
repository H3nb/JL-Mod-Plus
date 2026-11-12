/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

internal fun recipeModeLabel(mode: String, context: android.content.Context): String = when (mode) {
    "toggle" -> context.getString(R.string.memory_scripts_mode_toggle)
    "form" -> context.getString(R.string.memory_scripts_mode_form)
    "oneShot" -> context.getString(R.string.memory_scripts_mode_once)
    else -> context.getString(R.string.memory_scripts_mode_legacy)
}

@Composable
internal fun TypedMemoryRecipeDetail(
    script: MemoryScriptRow,
    metadata: MemoryScriptMetadata,
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val restoredInputs = remember(script.id, script.document, state.runtimeToken) {
        MemoryEditorSessionNavigation.script(state.runtimeToken).takeIf {
            it.detailId == script.id && it.detailInputs.isNotEmpty()
        }?.detailInputs.orEmpty().filterKeys { it in metadata.parameters }
    }
    var inputs by remember(script.id, script.document, state.runtimeToken) {
        mutableStateOf(metadata.parameters + restoredInputs)
    }
    var currentValues by remember(script.id, state.runtimeToken) {
        mutableStateOf<List<MemoryRecipeReadValue>?>(null)
    }
    var expectedValues by remember(script.id, state.runtimeToken) {
        mutableStateOf<List<String>?>(null)
    }
    var snapshotTicket by remember(script.id, state.runtimeToken) {
        mutableStateOf<String?>(null)
    }
    var readError by remember(script.id, state.runtimeToken) { mutableStateOf<String?>(null) }
    var reading by remember(script.id, state.runtimeToken) { mutableStateOf(false) }
    var busy by remember(script.id, state.runtimeToken) { mutableStateOf(false) }
    var deleting by remember(script.id) { mutableStateOf(false) }
    var requestId by remember(script.id, state.runtimeToken) { mutableStateOf(0L) }
    val active = state.scriptActiveFreezes.any { it.first == script.id }

    fun refresh(requestInputs: Map<String, String>, useActualValues: Boolean = false) {
        val request = ++requestId
        reading = true
        currentValues = null
        expectedValues = null
        snapshotTicket = null
        readError = null
        actions.readCurrentMemoryRecipe(script.document, requestInputs) { result, error ->
            if (request == requestId) {
                reading = false
                readError = error
                currentValues = result
                val grouped = result.orEmpty().groupBy(MemoryRecipeReadValue::name)
                val consistent = grouped.values.all { group ->
                    group.map(MemoryRecipeReadValue::value).distinct().size == 1
                }
                if (metadata.mode == "form" && result != null && consistent) {
                    expectedValues = result.map(MemoryRecipeReadValue::value)
                    snapshotTicket = result.firstOrNull()?.snapshotTicket
                    if (useActualValues) {
                        inputs = requestInputs + metadata.fields.filterNot(MemoryScriptInput::selector)
                            .mapNotNull { field ->
                                grouped[field.name]?.firstOrNull()?.value?.let { field.name to it }
                            }.toMap()
                        MemoryEditorSessionNavigation.rememberScriptDetail(
                            state.runtimeToken, script.id, inputs, false,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(script.document, script.id, state.runtimeToken, state.supported) {
        if (metadata.mode == "form" && state.supported && restoredInputs.isEmpty())
            refresh(inputs, useActualValues = true)
    }

    Column(modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.memory_scripts_back)) }
            Text(script.name, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onEdit) { Text(stringResource(R.string.memory_scripts_edit)) }
        }
        Text(stringResource(when (metadata.mode) {
            "toggle" -> R.string.memory_scripts_mode_toggle
            "form" -> R.string.memory_scripts_mode_form
            else -> R.string.memory_scripts_mode_once
        }), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.memory_scripts_current_title),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !reading && !busy && state.supported && !state.busy,
                    onClick = { refresh(inputs) },
                ) { Text(stringResource(R.string.memory_scripts_refresh)) }
            }
            when {
                reading -> Text(stringResource(R.string.memory_scripts_reading))
                readError != null -> Text(readError.orEmpty(),
                    color = MaterialTheme.colorScheme.error)
                currentValues != null -> currentValues.orEmpty()
                    .groupBy(MemoryRecipeReadValue::name).forEach { (field, copies) ->
                        val distinct = copies.map(MemoryRecipeReadValue::value).distinct()
                        val label = metadata.fields.firstOrNull { it.name == field }?.label ?: field
                        Text("$label: " + if (distinct.size == 1) distinct.first()
                            else stringResource(R.string.memory_scripts_inconsistent,
                                distinct.joinToString(" / ")),
                            color = if (distinct.size == 1) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error)
                    }
                else -> Text(stringResource(R.string.memory_scripts_refresh_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            if (metadata.mode != "toggle") metadata.fields.forEach { field ->
                OutlinedTextField(
                    value = inputs[field.name].orEmpty(),
                    onValueChange = { next ->
                        if (!busy && next.length <= 32) {
                            val updated = inputs + (field.name to next)
                            inputs = updated
                            MemoryEditorSessionNavigation.rememberScriptDetail(
                                state.runtimeToken, script.id, inputs, false,
                            )
                            if (field.selector) refresh(updated, useActualValues = true)
                        }
                    },
                    label = { Text(field.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("memory-script-input-${field.name}"),
                    enabled = !busy && !reading,
                )
            }
            when (metadata.mode) {
                "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = active, enabled = !busy && !state.busy &&
                        (active || state.writeSupported), onCheckedChange = { enabled ->
                        if (enabled) {
                            busy = true
                            actions.runMemoryRecipeChecked(
                                script.document, inputs, true, null,
                            ) { busy = false }
                        } else actions.clearMemoryRecipeFreeze(script.id)
                    })
                    Text(stringResource(if (active) R.string.memory_scripts_freeze_active
                        else R.string.memory_scripts_mode_toggle))
                }
                "form" -> {
                    if (expectedValues == null) Text(
                        stringResource(R.string.memory_scripts_refresh_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(modifier = Modifier.testTag("memory-script-apply"),
                        enabled = expectedValues != null && snapshotTicket != null && !busy && !reading &&
                        !state.busy && state.writeSupported, onClick = {
                        val snapshot = expectedValues ?: return@Button
                        busy = true
                        actions.runMemoryRecipeChecked(
                            script.document, inputs, false, snapshot, snapshotTicket,
                        ) { success ->
                            busy = false
                            expectedValues = null
                            snapshotTicket = null
                            if (success) refresh(inputs, useActualValues = true)
                        }
                    }) { Text(stringResource(R.string.memory_scripts_apply)) }
                }
                "oneShot" -> Button(enabled = !busy && !state.busy &&
                        state.writeSupported, onClick = {
                    busy = true
                    actions.runMemoryRecipeChecked(script.document, inputs, false, null) {
                        busy = false
                        if (it) refresh(inputs)
                    }
                }) { Text(stringResource(R.string.memory_recipes_run)) }
            }
            TextButton(onClick = { deleting = true }) {
                Text(stringResource(R.string.memory_scripts_delete),
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (deleting) MemoryEditorDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.memory_scripts_delete)) },
        text = { Text(stringResource(R.string.memory_scripts_delete_confirm, script.name)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                actions.deleteMemoryScript(script.id) { if (it) onDeleted() }
            }) { Text(stringResource(R.string.memory_scripts_delete)) }
        },
        dismissButton = {
            TextButton(onClick = { deleting = false }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
