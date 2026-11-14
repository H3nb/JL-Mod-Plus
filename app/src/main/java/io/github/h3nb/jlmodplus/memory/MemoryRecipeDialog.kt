/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

@Composable
internal fun MemoryRecipeDialog(
    saved: SavedVariableRow,
    savedValue: String,
    writeSupported: Boolean,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    var generatedValue by remember(saved.id) { mutableStateOf(savedValue) }
    var freeze by remember { mutableStateOf(false) }
    var activeFreezeIds by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        actions.listActiveMemoryRecipeFreezes { activeFreezeIds = it }
    }
    MemoryEditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_recipes_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.memory_recipes_simple_from_saved, saved.label))
                OutlinedTextField(
                    value = generatedValue,
                    onValueChange = { if (it.length <= 32) generatedValue = it },
                    label = { Text(stringResource(R.string.memory_editor_edit)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (activeFreezeIds.isNotEmpty()) {
                    Text(stringResource(R.string.memory_recipes_active_freezes))
                    activeFreezeIds.forEach { (recipeId, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                actions.clearMemoryRecipeFreeze(recipeId)
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.generateSimpleMemoryRecipe(saved.id, generatedValue, freeze)
                    onDismiss()
                },
                enabled = writeSupported,
            ) {
                Text(stringResource(R.string.memory_recipes_run))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
