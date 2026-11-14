/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A disposable editor draft. Only explicit Save writes the installed-game catalog. */
internal data class MemoryScriptDraft(
    val replacingId: String?,
    val initialText: String,
    val text: String,
) {
    val dirty: Boolean get() = text != initialText
}

internal data class MemoryScriptInput(val name: String, val label: String, val selector: Boolean)

internal data class MemoryScriptMetadata(
    val id: String,
    val name: String,
    val parameters: Map<String, String>,
    val mode: String = "legacy",
    val fields: List<MemoryScriptInput> = emptyList(),
    val quickRun: Boolean = true,
    val description: String = "",
)

internal fun memoryScriptMetadata(document: String): MemoryScriptMetadata {
    MemoryRecipeRuntime.validateDocument(document)
    val obj = Json.parseToJsonElement(document) as? JsonObject ?: error("Expected JSON object")
    val version = (obj["formatVersion"] as? JsonPrimitive)?.content
    val mode = when (version) {
        "1" -> "legacy"
        "2" -> (obj["mode"] as? JsonPrimitive)?.content ?: error("Missing recipe mode")
        else -> error("Unsupported Memory Recipe version")
    }
    val id = (obj["id"] as? JsonPrimitive)?.content.orEmpty()
    if (!id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) error("Invalid recipe ID")
    val name = (obj["name"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) ?: id
    val description = (obj["description"] as? JsonPrimitive)?.content.orEmpty()
    val game = obj["game"] as? JsonObject ?: error("Missing game")
    if ((game["mainClass"] as? JsonPrimitive)?.content.isNullOrBlank())
        error("Missing game.mainClass")
    val actions = obj["actions"] as? JsonArray ?: error("Actions must be an array")
    if (actions.isEmpty()) error("Recipe has no actions")
    val inputs = obj["inputs"] as? JsonObject ?: JsonObject(emptyMap())
    if (inputs.size > 64) error("Too many recipe parameters")
    val selectors = actions.asSequence().mapNotNull { it as? JsonObject }
        .flatMap { (it["path"] as? JsonArray).orEmpty().asSequence() }
        .mapNotNull { (it as? JsonObject)?.get("indexInput") as? JsonPrimitive }
        .map(JsonPrimitive::content).toSet()
    val fields = inputs.map { (key, value) ->
        val spec = value as? JsonObject ?: error("Invalid parameter")
        MemoryScriptInput(
            key, (spec["label"] as? JsonPrimitive)?.content ?: key, key in selectors,
        )
    }
    return MemoryScriptMetadata(id, name, inputs.mapValues { (_, entry) ->
        val specification = entry as? JsonObject ?: error("Invalid parameter")
        (specification["default"] as? JsonPrimitive)?.content
            ?: (specification["min"] as? JsonPrimitive)?.content ?: "0"
    }, mode, fields, inputs.values.all { value ->
        (value as JsonObject).containsKey("default")
    }, description)
}

/** Scripts are JSON recipes: no arbitrary code execution and no auto-run on Save. */
@Composable
internal fun RuntimeScriptsTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    selectedId: String?,
    onSelectedId: (String?) -> Unit,
    draft: MemoryScriptDraft?,
    onDraft: (MemoryScriptDraft?) -> Unit,
    onBackFromEditor: () -> Unit,
    builder: MemoryScriptBuilderDraft?,
    onBuilder: (MemoryScriptBuilderDraft?) -> Unit,
    onBackFromBuilder: () -> Unit,
    choosingType: Boolean,
    onChoosingTypeChange: (Boolean) -> Unit,
    initialTargets: List<MemoryScriptBuilderAction>,
    onTargetsUsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val saveErrorText = stringResource(R.string.memory_scripts_save_failed)
    val clipboardErrorText = stringResource(R.string.memory_scripts_clipboard_invalid)
    val cachedDetail = remember(selectedId, state.runtimeToken) {
        MemoryEditorSessionNavigation.script(state.runtimeToken).takeIf {
            it.detailId == selectedId
        }
    }
    var inputs by remember(selectedId, state.runtimeToken) {
        mutableStateOf(cachedDetail?.detailInputs.orEmpty())
    }
    var freeze by remember(selectedId, state.runtimeToken) {
        mutableStateOf(cachedDetail?.detailFreeze ?: false)
    }
    var deleting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var currentValues by remember(selectedId, state.runtimeToken) {
        mutableStateOf<List<MemoryRecipeReadValue>?>(null)
    }
    var currentError by remember(selectedId, state.runtimeToken) { mutableStateOf<String?>(null) }
    var readingCurrent by remember(selectedId, state.runtimeToken) { mutableStateOf(false) }
    var readGeneration by remember(selectedId, state.runtimeToken) { mutableStateOf(0L) }
    val selected = state.scripts.firstOrNull { it.id == selectedId }
    val metadata = selected?.let { runCatching { memoryScriptMetadata(it.document) }.getOrNull() }
    fun editScript(script: MemoryScriptRow) {
        val visual = actions.restoreMemoryScriptBuilder(script.document)
        if (visual != null) onBuilder(visual)
        else onDraft(MemoryScriptDraft(script.id, script.document, script.document))
    }

    LaunchedEffect(state.scripts, selectedId) {
        if (state.scriptsLoaded && selectedId != null && selected == null && draft == null)
            onSelectedId(null)
    }
    LaunchedEffect(selected?.document) {
        val saved = MemoryEditorSessionNavigation.script(state.runtimeToken)
            .takeIf { it.detailId == selectedId }
        val defaults = metadata?.parameters.orEmpty()
        inputs = defaults + saved?.detailInputs.orEmpty().filterKeys { it in defaults }
        freeze = saved?.detailFreeze ?: false
        currentValues = null
        currentError = null
        readGeneration++
    }

    if (builder != null) {
        MemoryScriptBuilderScreen(
            draft = builder, state = state, actions = actions,
            onDraft = { onBuilder(it) },
            onBack = onBackFromBuilder,
            onSaved = {
                onSelectedId(it)
                onBuilder(null)
            },
            onEditJson = {
                val original = builder.replacingId?.let { id ->
                    state.scripts.firstOrNull { script -> script.id == id }?.document
                }.orEmpty()
                onDraft(MemoryScriptDraft(builder.replacingId, original, it))
                onBuilder(null)
            },
            modifier = modifier,
        )
        return
    }

    BackHandler(enabled = choosingType) {
        onChoosingTypeChange(false)
        onTargetsUsed()
    }
    if (choosingType) {
        MemoryScriptTypeChooser(
            onChoose = {
                onChoosingTypeChange(false)
                onSelectedId(null)
                onBuilder(MemoryScriptBuilderDraft.create(it).copy(actions = initialTargets))
                onTargetsUsed()
            },
            onJson = {
                onChoosingTypeChange(false)
                onSelectedId(null)
                onDraft(MemoryScriptDraft(null, "", ""))
                onTargetsUsed()
            },
            onBack = {
                onChoosingTypeChange(false)
                onTargetsUsed()
            },
            modifier = modifier,
        )
        return
    }

    if (draft != null) {
        val parsed = remember(draft.text) { runCatching { memoryScriptMetadata(draft.text) } }
        BoxWithConstraints(modifier.fillMaxSize()) {
        val compactEditor = maxHeight < 280.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactEditor) 4.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !saving, onClick = onBackFromEditor) {
                    Text(stringResource(R.string.memory_scripts_back))
                }
                Text(
                    stringResource(if (draft.replacingId == null) R.string.memory_scripts_new
                        else R.string.memory_scripts_edit),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !saving && draft.text.isNotBlank() &&
                        draft.text.toByteArray(Charsets.UTF_8).size <= MemoryEngineContract.MAX_RECIPE_BYTES &&
                        parsed.isSuccess &&
                        (draft.replacingId == null || parsed.getOrNull()?.id == draft.replacingId),
                    onClick = {
                        saving = true
                        localError = null
                        actions.saveMemoryScript(draft.text, draft.replacingId) { success ->
                            saving = false
                            if (success) {
                                onSelectedId(parsed.getOrNull()?.id)
                                onDraft(null)
                            } else localError = saveErrorText
                        }
                    },
                ) { Text(stringResource(R.string.memory_scripts_save)) }
            }
            if (!compactEditor) Text(stringResource(R.string.memory_scripts_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = draft.text,
                onValueChange = {
                    if (!saving && it.toByteArray(Charsets.UTF_8).size <= MemoryEngineContract.MAX_RECIPE_BYTES) {
                        onDraft(draft.copy(text = it))
                        localError = null
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("memory-script-editor"),
                label = { Text("JSON") },
                placeholder = { Text(stringResource(R.string.memory_scripts_paste_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                enabled = !saving,
                minLines = if (compactEditor) 2 else 6,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${draft.text.toByteArray(Charsets.UTF_8).size}/${MemoryEngineContract.MAX_RECIPE_BYTES} B",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clip = clipboard?.primaryClip
                    val pasted = if (clip != null && clip.itemCount > 0)
                        clip.getItemAt(0).coerceToText(context)?.toString() else null
                    if (pasted != null && pasted.toByteArray(Charsets.UTF_8).size <= MemoryEngineContract.MAX_RECIPE_BYTES) {
                        onDraft(draft.copy(text = pasted))
                        localError = null
                    } else localError = clipboardErrorText
                }) { Text(stringResource(R.string.memory_scripts_paste)) }
            }
            if (draft.text.isNotBlank()) parsed.exceptionOrNull()?.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            localError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        return
    }

    if (selected != null && metadata != null && metadata.mode != "legacy") {
        TypedMemoryRecipeDetail(
            script = selected, metadata = metadata, state = state, actions = actions,
            onBack = { onSelectedId(null) },
            onEdit = { editScript(selected) },
            onDeleted = { onSelectedId(null) },
            modifier = modifier,
        )
        return
    }

    if (selected != null) {
        Column(modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelectedId(null) }) {
                    Text(stringResource(R.string.memory_scripts_back))
                }
                Text(selected.name, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { editScript(selected) }) {
                    Text(stringResource(R.string.memory_scripts_edit))
                }
            }
            Text(selected.id, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metadata?.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(stringResource(R.string.memory_scripts_description),
                        style = MaterialTheme.typography.titleSmall)
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.memory_scripts_current_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(enabled = !readingCurrent && state.supported && !state.busy &&
                        metadata != null, onClick = {
                        val generation = ++readGeneration
                        readingCurrent = true
                        currentValues = null
                        currentError = null
                        actions.readCurrentMemoryRecipe(selected.document, inputs) { values, error ->
                            if (generation == readGeneration) {
                                readingCurrent = false
                                currentValues = values
                                currentError = error
                            }
                        }
                    }) { Text(stringResource(R.string.memory_scripts_refresh)) }
                }
                when {
                    readingCurrent -> Text(stringResource(R.string.memory_scripts_reading))
                    currentError != null -> Text(currentError.orEmpty(),
                        color = MaterialTheme.colorScheme.error)
                    currentValues != null -> {
                        currentValues.orEmpty().groupBy(MemoryRecipeReadValue::name)
                            .forEach { (name, values) ->
                                val distinct = values.map(MemoryRecipeReadValue::value).distinct()
                                val shown = if (distinct.size == 1) distinct.single()
                                    else stringResource(R.string.memory_scripts_inconsistent,
                                        distinct.joinToString(" / "))
                                Text("$name: $shown",
                                    color = if (distinct.size == 1)
                                        MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.error)
                            }
                    }
                    else -> Text(stringResource(R.string.memory_scripts_refresh_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if (metadata == null) Text(stringResource(R.string.memory_scripts_invalid),
                    color = MaterialTheme.colorScheme.error)
                metadata?.parameters?.forEach { (name, _) ->
                    OutlinedTextField(
                        value = inputs[name].orEmpty(),
                        onValueChange = {
                            if (it.length <= 32) {
                                inputs = inputs + (name to it)
                                MemoryEditorSessionNavigation.rememberScriptDetail(
                                    state.runtimeToken, selected.id, inputs, freeze,
                                )
                                currentValues = null
                                currentError = null
                                readGeneration++
                                readingCurrent = false
                            }
                        },
                        singleLine = true, label = { Text(name) }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = freeze, onCheckedChange = {
                        freeze = it
                        MemoryEditorSessionNavigation.rememberScriptDetail(
                            state.runtimeToken, selected.id, inputs, freeze,
                        )
                    })
                    Text(stringResource(R.string.memory_recipes_freeze),
                        style = MaterialTheme.typography.bodySmall)
                }
                val active = state.scriptActiveFreezes.any { it.first == selected.id }
                if (active) Text(stringResource(R.string.memory_scripts_freeze_active),
                    color = MaterialTheme.colorScheme.primary)
                Button(enabled = !state.busy && state.writeSupported && metadata != null,
                    onClick = {
                        currentValues = null
                        currentError = null
                        readGeneration++
                        readingCurrent = false
                        actions.runMemoryRecipe(selected.document, inputs, freeze, false)
                    }) { Text(stringResource(R.string.memory_recipes_run)) }
                if (active) OutlinedButton(onClick = {
                    actions.clearMemoryRecipeFreeze(selected.id)
                }) { Text(stringResource(R.string.memory_recipes_stop_freeze)) }
                TextButton(onClick = { deleting = true }) {
                    Text(stringResource(R.string.memory_scripts_delete),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (deleting) {
            MemoryEditorDialog(
                onDismissRequest = { deleting = false },
                title = { Text(stringResource(R.string.memory_scripts_delete)) },
                text = { Text(stringResource(R.string.memory_scripts_delete_confirm, selected.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = false
                        actions.deleteMemoryScript(selected.id) { if (it) onSelectedId(null) }
                    }) { Text(stringResource(R.string.memory_scripts_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        return
    }

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.memory_scripts_title),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { onChoosingTypeChange(true) }) {
                Text(stringResource(R.string.memory_scripts_new))
            }
        }
        state.scriptsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.scripts.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_scripts_empty),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.memory_scripts_paste_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onChoosingTypeChange(true) }) {
                    Text(stringResource(R.string.memory_scripts_new))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.scripts, key = MemoryScriptRow::id) { script ->
                    val scriptMetadata = remember(script.document) {
                        runCatching { memoryScriptMetadata(script.document) }.getOrNull()
                    }
                    var launching by remember(script.id, state.runtimeToken) { mutableStateOf(false) }
                    val active = state.scriptActiveFreezes.any { it.first == script.id }
                    Surface(modifier = Modifier.fillMaxWidth().clickable { onSelectedId(script.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(script.name, style = MaterialTheme.typography.titleSmall)
                                val modeLabel = scriptMetadata?.mode?.let {
                                    recipeModeLabel(it, context)
                                } ?: script.id
                                val status = if (active) stringResource(R.string.memory_scripts_active) else null
                                Text(if (status == null) modeLabel else "$modeLabel · $status",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                scriptMetadata?.description?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            when (scriptMetadata?.mode) {
                                "toggle" -> Switch(checked = active,
                                    modifier = Modifier.testTag("memory-script-toggle-${script.id}"),
                                    enabled = !launching && !state.busy &&
                                        (active || state.writeSupported),
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            launching = true
                                            actions.runMemoryRecipeChecked(
                                                script.document, scriptMetadata.parameters, true, null,
                                            ) { launching = false }
                                        } else actions.clearMemoryRecipeFreeze(script.id)
                                    })
                                "oneShot" -> if (scriptMetadata.quickRun) Button(
                                    modifier = Modifier.testTag("memory-script-run-${script.id}"),
                                    enabled = !launching && !state.busy &&
                                        state.writeSupported, onClick = {
                                    launching = true
                                    actions.runMemoryRecipeChecked(
                                        script.document, scriptMetadata.parameters, false, null,
                                    ) { launching = false }
                                }) { Text(stringResource(R.string.memory_recipes_run)) }
                                else TextButton(onClick = { onSelectedId(script.id) }) {
                                    Text(stringResource(R.string.memory_scripts_edit_values))
                                }
                                "form" -> TextButton(onClick = { onSelectedId(script.id) }) {
                                    Text(stringResource(R.string.memory_scripts_edit_values))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
