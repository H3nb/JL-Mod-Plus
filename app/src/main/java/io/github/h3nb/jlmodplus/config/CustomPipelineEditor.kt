// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.gles.GlslpAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Draft only: the form and linked profile are not changed until Save. */
@Composable
internal fun CustomPipelineContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onBack: () -> Unit,
) {
    val previous = state.form.shader?.customStages
    val draftSaver = Saver<MutableState<List<CustomShaderStage>>, ArrayList<String>>(
        save = { value ->
            ArrayList(value.value.flatMap { stage ->
                listOf(stage.path, stage.sourceScale.toString(),
                    stage.filterLinear?.toString() ?: "default")
            })
        },
        restore = { values ->
            mutableStateOf(values.chunked(3).map { parts ->
                CustomShaderStage(parts[0], parts[1].toInt(),
                    when (parts[2]) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    })
            })
        },
    )
    var stages by rememberSaveable(previous, saver = draftSaver) {
        mutableStateOf(previous?.toList() ?: emptyList())
    }
    val candidates = state.shaders.filter { shader ->
        shader.preset?.endsWith(".glsl", ignoreCase = true) == true
            && shader.preset?.startsWith("builtin:") != true
    }
    var addPicker by rememberSaveable { mutableStateOf(false) }
    var scalePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterPicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var validating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stages) { validationError = null }

    Text(
        stringResource(R.string.render_custom_order_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (stages.isEmpty()) {
        Text(
            stringResource(R.string.render_custom_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    stages.forEachIndexed { index, stage ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stage.path.substringAfterLast('/'),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index < stages.lastIndex) {
                    TextButton(onClick = { scalePicker = index }) {
                        Text(stringResource(R.string.render_custom_scale, stage.sourceScale))
                    }
                } else {
                    Text(
                        stringResource(R.string.render_custom_final_viewport),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { filterPicker = index }) {
                    Text(stringResource(
                        R.string.render_custom_filter,
                        when (stage.filterLinear) {
                            true -> stringResource(R.string.render_custom_linear)
                            false -> stringResource(R.string.render_custom_nearest)
                            null -> stringResource(R.string.render_custom_default)
                        },
                    ))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = index > 0, onClick = {
                        stages = stages.toMutableList().apply {
                            add(index - 1, removeAt(index))
                        }
                    }) { Text(stringResource(R.string.render_custom_up)) }
                    TextButton(enabled = index < stages.lastIndex, onClick = {
                        stages = stages.toMutableList().apply {
                            add(index + 1, removeAt(index))
                        }
                    }) { Text(stringResource(R.string.render_custom_down)) }
                    TextButton(onClick = {
                        stages = stages.toMutableList().apply { removeAt(index) }
                    }) {
                        Text(stringResource(R.string.render_custom_remove))
                    }
                }
            }
        }
    }
    if (stages.size < 16 && candidates.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = { addPicker = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.render_custom_add),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.render_custom_add_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (candidates.isEmpty()) {
        Text(
            stringResource(R.string.render_custom_no_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    validationError?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.render_custom_cancel)) }
        TextButton(enabled = stages.isNotEmpty() && !validating, onClick = {
            val requested = stages.toList()
            validating = true
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching {
                        val root = state.shaderDirectory
                            ?: throw IOException("Shader directory unavailable")
                        GlslpAssets.loadCustom(requested, root).parameterDefinitions()
                    }.exceptionOrNull()
                }
                validating = false
                // The user may change a stage while validation is running.
                if (requested != stages) return@launch
                if (error != null) {
                    validationError = error.message ?: "Invalid shader chain"
                } else {
                    val selected = ShaderInfo("Custom pipeline", "JL-Mod Plus")
                    selected.customStages = requested
                    onFormChanged(state.form.toBuilder().shader(selected).build())
                    onBack()
                }
            }
        }) {
            Text(stringResource(if (validating) R.string.render_custom_checking
                else R.string.render_custom_save))
        }
    }

    if (addPicker) ConfigChoiceDialog(
        title = stringResource(R.string.render_custom_add),
        selected = "",
        options = candidates.map { it.preset ?: "" },
        onDismissRequest = { addPicker = false },
        onSelected = { selection ->
            addPicker = false
            candidates.getOrNull(selection)?.preset?.let { path ->
                stages = stages + CustomShaderStage(path)
            }
        },
    )
    scalePicker?.let { index ->
        ConfigChoiceDialog(
            title = stringResource(R.string.render_custom_output_scale),
            selected = (stages.getOrNull(index)?.sourceScale ?: 1).toString() + "×",
            options = listOf("1×", "2×", "4×"),
            onDismissRequest = { scalePicker = null },
            onSelected = { selected ->
                scalePicker = null
                val scale = listOf(1, 2, 4).getOrNull(selected)
                if (scale != null) {
                    stages = stages.mapIndexed { i, stage ->
                        if (i == index) stage.copy(sourceScale = scale) else stage
                    }
                }
            },
        )
    }
    filterPicker?.let { index ->
        val names = listOf(
            stringResource(R.string.render_custom_default),
            stringResource(R.string.render_custom_nearest),
            stringResource(R.string.render_custom_linear),
        )
        ConfigChoiceDialog(
            title = stringResource(R.string.render_pass_filter),
            selected = when (stages.getOrNull(index)?.filterLinear) {
                true -> names[2]
                false -> names[1]
                null -> names[0]
            },
            options = names,
            onDismissRequest = { filterPicker = null },
            onSelected = { selected ->
                filterPicker = null
                val filter = when (selected) { 1 -> false; 2 -> true; else -> null }
                stages = stages.mapIndexed { i, stage ->
                    if (i == index) stage.copy(filterLinear = filter) else stage
                }
            },
        )
    }
}
