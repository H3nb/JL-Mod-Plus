/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Minimal recipe launcher: simple Saved-based authoring or loading a documented JSON recipe. */
@Composable
internal fun MemoryRecipeDialog(
    saved: SavedVariableRow?,
    savedValue: String,
    writeSupported: Boolean,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generatedValue by remember(saved?.id) { mutableStateOf(savedValue) }
    var document by remember { mutableStateOf<String?>(null) }
    var displayName by remember { mutableStateOf("") }
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var freeze by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var activeFreezeIds by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var currentValues by remember { mutableStateOf<List<MemoryRecipeReadValue>?>(null) }
    var currentError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        actions.listActiveMemoryRecipeFreezes { activeFreezeIds = it }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            // A failed replacement import must never leave an older executable document selected.
            document = null
            displayName = ""
            inputs = emptyMap()
            loadError = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val length = stream.read(buffer)
                            if (length < 0) break
                            if (output.size() + length > 24_576) error("Recipe exceeds 24 KiB")
                            output.write(buffer, 0, length)
                        }
                        output.toByteArray()
                    } ?: error("Could not open recipe")
                }
                val text = bytes.toString(Charsets.UTF_8)
                val parsed = Json.parseToJsonElement(text) as? JsonObject
                    ?: error("Invalid recipe")
                val fields = parsed["inputs"] as? JsonObject ?: JsonObject(emptyMap())
                if (fields.size > 8) error("Recipe has too many inputs")
                val values = fields.mapValues { (_, entry) ->
                    val details = entry as? JsonObject ?: error("Invalid recipe input")
                    (details["default"] as? JsonPrimitive)?.content
                        ?: (details["min"] as? JsonPrimitive)?.content
                        ?: "0"
                }
                document = text
                currentValues = null
                currentError = null
                displayName = (parsed["name"] as? JsonPrimitive)?.content ?: "Imported recipe"
                inputs = values
                loadError = null
            } catch (error: Exception) {
                loadError = error.message ?: "Could not read recipe"
            }
        }
    }
    MemoryEditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_recipes_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (saved != null && document == null) {
                    Text(stringResource(R.string.memory_recipes_simple_from_saved, saved.label))
                    OutlinedTextField(
                        value = generatedValue,
                        onValueChange = { if (it.length <= 32) generatedValue = it },
                        label = { Text(stringResource(R.string.memory_editor_edit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { picker.launch("*/*") }) {
                    Text(stringResource(R.string.memory_recipes_import))
                }
                document?.let { loaded ->
                    Text(displayName)
                    inputs.forEach { (name, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { changed ->
                                if (changed.length <= 32) {
                                    inputs = inputs + (name to changed)
                                    currentValues = null
                                    currentError = null
                                }
                            },
                            label = { Text(name) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = {
                        actions.readCurrentMemoryRecipe(loaded, inputs) { values, error ->
                            currentValues = values
                            currentError = error
                        }
                    }) {
                        Text(stringResource(R.string.memory_scripts_refresh))
                    }
                    currentError?.let { Text(it) }
                    currentValues?.groupBy(MemoryRecipeReadValue::name)?.forEach { (name, values) ->
                        val distinct = values.map(MemoryRecipeReadValue::value).distinct()
                        Text("$name: " + distinct.joinToString(" / "))
                    }
                }
                if (activeFreezeIds.isNotEmpty()) {
                    Text(stringResource(R.string.memory_recipes_active_freezes))
                    activeFreezeIds.forEach { (id, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                actions.clearMemoryRecipeFreeze(id)
                                actions.listActiveMemoryRecipeFreezes { activeFreezeIds = it }
                            }) {
                                Text(stringResource(R.string.memory_recipes_stop_freeze))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = freeze, onCheckedChange = { freeze = it })
                    Text(stringResource(R.string.memory_recipes_freeze))
                }
                loadError?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    document != null -> actions.runMemoryRecipe(document!!, inputs, freeze, false)
                    saved != null -> actions.generateSimpleMemoryRecipe(saved.id, generatedValue, freeze)
                }
                onDismiss()
            }, enabled = writeSupported && loadError == null &&
                (document != null || saved != null)) {
                Text(stringResource(R.string.memory_recipes_run))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
