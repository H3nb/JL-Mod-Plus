// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.filepicker.FilePickerResultContract
import io.github.h3nb.jlmodplus.gles.GlslpAssets
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The fixed number of passes controls the visible source slots; only Save changes the form. */
@Composable
internal fun CustomPipelineContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onBack: () -> Unit,
    onShaderPickerOpened: () -> Unit,
) {
    val previous = state.form.shader?.customStages
    val saver = Saver<MutableState<List<CustomShaderStage>>, ArrayList<String>>(
        save = { value ->
            ArrayList(value.value.flatMap { stage ->
                listOf(stage.path, stage.sourceScale.toString(),
                    stage.filterLinear?.toString() ?: "default")
            })
        },
        restore = { values ->
            mutableStateOf(values.chunked(3).map { fields ->
                CustomShaderStage(fields[0], fields[1].toInt(),
                    when (fields[2]) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    })
            })
        },
    )
    var stages by rememberSaveable(previous, saver = saver) {
        mutableStateOf(previous?.takeIf { it.isNotEmpty() } ?: listOf(CustomShaderStage("")))
    }
    val context = LocalContext.current
    val pickerTitle = stringResource(R.string.render_custom_pick_file)
    val increaseDescription = stringResource(R.string.render_custom_add_pass)
    val decreaseDescription = stringResource(R.string.render_custom_remove_pass)
    val scope = rememberCoroutineScope()
    var pickingIndex by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    var scalePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterPicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var validating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stages) { validationError = null }

    val filePicker = rememberLauncherForActivityResult(FilePickerResultContract()) { uris ->
        val index = pickingIndex
        pickingIndex = -1
        val path = relativeShaderPath(state.shaderDirectory, uris.firstOrNull(), listOf(".glsl"))
        if (path != null && index in stages.indices) {
            stages = stages.mapIndexed { i, stage ->
                if (i == index) stage.copy(path = path) else stage
            }
        }
    }

    Text(
        stringResource(R.string.render_custom_order_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.render_custom_pass_count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            TextButton(
                modifier = Modifier.testTag("shader_custom_decrease_passes")
                    .semantics { contentDescription = decreaseDescription },
                enabled = stages.size > 1,
                onClick = { stages = stages.dropLast(1) },
            ) {
                Text("−")
            }
            Text(stages.size.toString(), modifier = Modifier.testTag("shader_custom_pass_count"), style = MaterialTheme.typography.titleMedium)
            TextButton(
                modifier = Modifier.testTag("shader_custom_increase_passes")
                    .semantics { contentDescription = increaseDescription },
                enabled = stages.size < 16,
                onClick = { stages = stages + CustomShaderStage("") },
            ) {
                Text("+")
            }
        }
    }
    stages.forEachIndexed { index, stage ->
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("shader_custom_pass_${index + 1}"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(stringResource(R.string.render_custom_pass_number, index + 1),
                    style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = {
                        state.shaderDirectory?.let { root ->
                            pickingIndex = index
                            onShaderPickerOpened()
                            filePicker.launch(shaderPickerIntent(
                                context, root, listOf(".glsl"),
                                pickerTitle,
                            ))
                        }
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            stage.path.takeIf { it.isNotBlank() }?.substringAfterLast('/')
                                ?: stringResource(R.string.render_custom_pick_file),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (index < stages.lastIndex) {
                    TextButton(onClick = { scalePicker = index }) {
                        Text(stringResource(R.string.render_custom_scale, stage.sourceScale))
                    }
                } else {
                    Text(stringResource(R.string.render_custom_final_viewport),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { filterPicker = index }) {
                    Text(stringResource(R.string.render_custom_filter,
                        when (stage.filterLinear) {
                            true -> stringResource(R.string.render_custom_linear)
                            false -> stringResource(R.string.render_custom_nearest)
                            null -> stringResource(R.string.render_custom_default)
                        }))
                }
            }
        }
    }
    validationError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.render_custom_cancel)) }
        TextButton(
            enabled = stages.all { it.path.isNotBlank() } && !validating,
            onClick = {
                val requested = stages.toList()
                validating = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            GlslpAssets.loadCustom(
                                requested,
                                state.shaderDirectory
                                    ?: throw IOException("Shader directory unavailable"),
                            ).parameterDefinitions()
                        }.exceptionOrNull()
                    }
                    validating = false
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
            },
        ) {
            Text(stringResource(
                if (validating) R.string.render_custom_checking else R.string.render_custom_save,
            ))
        }
    }

    scalePicker?.let { index ->
        ConfigChoiceDialog(
            title = stringResource(R.string.render_custom_output_scale),
            selected = (stages.getOrNull(index)?.sourceScale ?: 1).toString() + "×",
            options = listOf("1×", "2×", "4×"),
            onDismissRequest = { scalePicker = null },
            onSelected = { selected ->
                scalePicker = null
                val scale = listOf(1, 2, 4).getOrNull(selected)
                if (scale != null) stages = stages.mapIndexed { i, stage ->
                    if (i == index) stage.copy(sourceScale = scale) else stage
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
            onSelected = { selection ->
                filterPicker = null
                val filter = when (selection) { 1 -> false; 2 -> true; else -> null }
                stages = stages.mapIndexed { i, stage ->
                    if (i == index) stage.copy(filterLinear = filter) else stage
                }
            },
        )
    }
}
