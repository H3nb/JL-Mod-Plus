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
import androidx.compose.material3.Slider
import io.github.h3nb.jlmodplus.gles.GlslpPreset
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
import kotlin.math.roundToInt
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
    val selection = state.form.shader
    val previous = selection?.customStages
    val inheritedPreset = selection?.preset?.takeIf { selection.customMode != "legacy" }
    val initialMode = selection?.customMode ?: if (selection?.fragment != null) "legacy" else "glsl"
    var mode by rememberSaveable(selection?.preset, selection?.fragment, previous) {
        mutableStateOf(initialMode)
    }
    val legacyChoices = state.shaders.filter { it.vertex != null && it.fragment != null }
    val saver = Saver<MutableState<List<CustomShaderStage>>, ArrayList<String>>(
        save = { value ->
            ArrayList(value.value.flatMap { stage ->
                listOf(stage.path, stage.sourceScale.toString(),
                    stage.filterLinear?.toString() ?: "default",
                    stage.legacyVertex.orEmpty(),
                    stage.legacyValues?.joinToString(",").orEmpty(),
                    stage.legacyName.orEmpty())
            })
        },
        restore = { values ->
            mutableStateOf(values.chunked(6).map { fields ->
                CustomShaderStage(fields[0], fields[1].toInt(),
                    when (fields[2]) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    },
                    fields[3].ifBlank { null },
                    fields[4].takeIf { it.isNotBlank() }?.split(',')?.map(String::toFloat),
                    fields[5].ifBlank { null })
            })
        },
    )
    val legacyInitial = selection?.fragment?.let { path ->
        CustomShaderStage(path, 1, null, selection.vertex,
            selection.values?.toList(), selection.name)
    }
    var stages by rememberSaveable(selection?.preset, selection?.fragment, previous, saver = saver) {
        mutableStateOf(previous?.takeIf { it.isNotEmpty() }
            ?: listOf(legacyInitial ?: CustomShaderStage("")))
    }
    var loading by remember(selection?.preset, previous) {
        mutableStateOf(inheritedPreset != null && previous == null)
    }
    var loadError by remember { mutableStateOf<String?>(null) }
    var overrides by remember(selection?.glslParameters) {
        mutableStateOf(selection?.glslParameters?.toMap() ?: emptyMap())
    }
    var passParameters by remember { mutableStateOf<Map<Int, List<GlslpPreset.Parameter>>>(emptyMap()) }
    val context = LocalContext.current
    val pickerTitle = stringResource(R.string.render_custom_pick_file)
    val increaseDescription = stringResource(R.string.render_custom_add_pass)
    val decreaseDescription = stringResource(R.string.render_custom_remove_pass)
    val scope = rememberCoroutineScope()
    var pickingIndex by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    var legacyPickerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var scalePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterPicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var validating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inheritedPreset, previous) {
        if (inheritedPreset != null && previous == null) {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GlslpAssets.load(inheritedPreset,
                        state.shaderDirectory ?: throw IOException("Shader folder unavailable"))
                        .passes.map { CustomShaderStage(it.sourcePath, 0, null) }
                }
            }
            loading = false
            result.onSuccess { stages = it }.onFailure {
                loadError = it.message ?: "Unable to read GLSLP preset"
            }
        }
    }
    LaunchedEffect(stages) { validationError = null }
    LaunchedEffect(stages, mode, inheritedPreset, overrides, loading) {
        if (mode != "glsl" || loading || stages.any { it.path.isBlank() }) {
            passParameters = emptyMap()
            return@LaunchedEffect
        }
        passParameters = withContext(Dispatchers.IO) {
            runCatching {
                val preset = GlslpAssets.loadCustom(stages,
                    state.shaderDirectory ?: throw IOException("Shader folder unavailable"),
                    inheritedPreset, "glsl").withParameterOverrides(overrides)
                val definitions = preset.parameterDefinitions()
                preset.passes.mapIndexed { index, pass ->
                    val ids = pass.sourceText.lineSequence().map { it.trim() }
                        .filter { it.startsWith("#pragma parameter ") }
                        .map { it.removePrefix("#pragma parameter ").substringBefore(' ') }
                        .toSet()
                    index to definitions.filter { it.id in ids }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }

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

    Text(stringResource(R.string.render_custom_mode),
        style = MaterialTheme.typography.titleSmall)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("legacy" to R.string.render_custom_mode_legacy,
            "glsl" to R.string.render_custom_mode_glsl).forEach { (value, title) ->
            Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large,
                onClick = {
                    if (mode != value) {
                        mode = value
                        stages = listOf(CustomShaderStage(""))
                        loadError = null
                        overrides = emptyMap()
                    }
                },
                color = if (mode == value) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(stringResource(title), modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (loading) {
        Text(stringResource(R.string.render_custom_loading),
            style = MaterialTheme.typography.bodyMedium)
        return
    }
    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                            if (mode == "legacy") {
                                legacyPickerIndex = index
                            } else {
                                pickingIndex = index
                                onShaderPickerOpened()
                                filePicker.launch(shaderPickerIntent(
                                    context, root, listOf(".glsl"), pickerTitle))
                            }
                        }
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (mode == "legacy") stage.legacyName
                                ?: stringResource(R.string.render_custom_pick_legacy)
                            else stage.path.takeIf { it.isNotBlank() }?.substringAfterLast('/')
                                ?: stringResource(R.string.render_custom_pick_file),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (mode == "legacy") {
                    val chosen = legacyChoices.firstOrNull {
                        it.vertex == stage.legacyVertex && it.fragment == stage.path
                    }
                    chosen?.settings?.forEachIndexed { settingIndex, setting ->
                        if (setting != null && setting.max > setting.min) {
                            val current = (stage.legacyValues?.getOrNull(settingIndex)
                                ?: setting.def).coerceIn(setting.min, setting.max)
                            Text(setting.name ?: "Parameter",
                                style = MaterialTheme.typography.bodyMedium)
                            Slider(value = current, valueRange = setting.min..setting.max,
                                onValueChange = { next ->
                                    val values = (0 until 4).map { slot ->
                                        stage.legacyValues?.getOrNull(slot)
                                            ?: chosen.settings.getOrNull(slot)?.def ?: 0f
                                    }.toMutableList()
                                    values[settingIndex] = next
                                    stages = stages.mapIndexed { i, original ->
                                        if (i == index) original.copy(legacyValues = values)
                                        else original
                                    }
                                })
                        }
                    }
                } else {
                    passParameters[index]?.forEach { parameter ->
                        val value = (overrides[parameter.id] ?: parameter.value)
                            .coerceIn(parameter.minimum, parameter.maximum)
                        Text(parameter.label, style = MaterialTheme.typography.bodyMedium)
                        Slider(value = value, valueRange = parameter.minimum..parameter.maximum,
                            onValueChange = { next ->
                                overrides = overrides + (parameter.id to next)
                            })
                    }
                }
                if (index < stages.lastIndex) {
                    TextButton(onClick = { scalePicker = index }) {
                        Text(if (stage.sourceScale == 0)
                            stringResource(R.string.render_custom_preserve_size)
                        else stringResource(R.string.render_custom_scale, stage.sourceScale))
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
            enabled = stages.all {
                it.path.isNotBlank() && (mode != "legacy" || it.legacyVertex != null)
            } && !validating && loadError == null,
            onClick = {
                val requested = stages.toList()
                val requestedMode = mode
                val presetPath = if (requestedMode == "glsl") inheritedPreset else null
                validating = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            GlslpAssets.loadCustom(
                                requested,
                                state.shaderDirectory
                                    ?: throw IOException("Shader directory unavailable"),
                                presetPath, requestedMode,
                            ).withParameterOverrides(if (requestedMode == "glsl") overrides else null)
                                .parameterDefinitions()
                        }.exceptionOrNull()
                    }
                    validating = false
                    if (requested != stages || requestedMode != mode) return@launch
                    if (error != null) {
                        validationError = error.message ?: "Invalid shader chain"
                    } else {
                        val selected = ShaderInfo("Custom pipeline", "JL-Mod Plus")
                        selected.customStages = requested
                        selected.customMode = requestedMode
                        selected.preset = presetPath
                        selected.glslParameters = if (requestedMode == "glsl") overrides else null
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

    legacyPickerIndex?.let { index ->
        ConfigChoiceDialog(
            title = stringResource(R.string.render_custom_pick_legacy),
            selected = stages.getOrNull(index)?.legacyName.orEmpty(),
            options = legacyChoices.map { it.toString() },
            onDismissRequest = { legacyPickerIndex = null },
            onSelected = { selection ->
                legacyPickerIndex = null
                legacyChoices.getOrNull(selection)?.let { shader ->
                    val values = (0 until 4).map { slot ->
                        shader.values?.getOrNull(slot)
                            ?: shader.settings.getOrNull(slot)?.def ?: 0f
                    }
                    stages = stages.mapIndexed { i, original ->
                        if (i == index) original.copy(path = shader.fragment,
                            legacyVertex = shader.vertex, legacyValues = values,
                            legacyName = shader.toString(), sourceScale = 1)
                        else original
                    }
                }
            },
        )
    }
    scalePicker?.let { index ->
        ConfigChoiceDialog(
            title = stringResource(R.string.render_custom_output_scale),
            selected = (stages.getOrNull(index)?.sourceScale ?: 1).toString() + "×",
            options = if (inheritedPreset != null && mode == "glsl")
                listOf("Preset", "1×", "2×", "4×")
            else listOf("1×", "2×", "4×"),
            onDismissRequest = { scalePicker = null },
            onSelected = { selected ->
                scalePicker = null
                val retain = inheritedPreset != null && mode == "glsl"
                val scale = if (retain && selected == 0) 0
                    else listOf(1, 2, 4).getOrNull(selected - if (retain) 1 else 0)
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
