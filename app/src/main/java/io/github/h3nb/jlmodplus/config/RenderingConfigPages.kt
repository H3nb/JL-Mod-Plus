// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.gles.GlslpPreset
import java.util.LinkedHashMap
import kotlin.math.roundToInt

internal enum class RenderingSubpage { SHADERS, PIPELINE }

internal fun selectedShaderLabel(state: ConfigUiState): String {
    val selected = state.form.shader ?: return "Off"
    return state.shaders.firstOrNull { it == selected }?.toString()
        ?: "Unavailable: ${selected}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenderingSettingsPage(
    page: RenderingSubpage,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onLegacyTuning: () -> Unit,
    onOpenShaders: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (page == RenderingSubpage.SHADERS)
                        R.string.render_shader_effects else R.string.render_pipeline_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (page == RenderingSubpage.SHADERS) {
                    ShaderEffectsContent(state, onFormChanged, onLegacyTuning)
                } else {
                    RenderingPipelineContent(state, onFormChanged, onOpenShaders)
                }
            }
        }
    }
}

@Composable
private fun ShaderEffectsContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onLegacyTuning: () -> Unit,
) {
    val form = state.form
    val selectedIndex = state.shaders.indexOfFirst { it == form.shader }
    ConfigSection(stringResource(R.string.render_shader_preset)) {
        ConfigChoicePreference(
            title = stringResource(R.string.render_shader_preset),
            description = stringResource(R.string.render_shader_picker_help),
            selected = selectedShaderLabel(state),
            options = state.shaders.map { if (it.fragment == null && it.preset == null)
                "Off" else it.toString() },
            onSelected = { index ->
                onFormChanged(form.toBuilder()
                    .shader(if (index == 0) null else state.shaders.getOrNull(index)).build())
            },
        )
        if (form.graphicsMode != 1 && form.shader != null) {
            Text(
                stringResource(R.string.render_shader_requires_gles),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (selectedIndex < 0 && form.shader != null) {
            Text(
                stringResource(R.string.render_shader_missing),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    val shader = form.shader ?: return
    if (shader.preset != null) {
        val summary = state.shaderPipeline
        if (summary?.error != null) {
            ConfigSection(stringResource(R.string.render_pipeline_unavailable)) {
                Text(
                    summary.error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (summary != null) {
            if (summary.parameters.isNotEmpty()) {
                ConfigSection(stringResource(R.string.render_shader_parameters)) {
                    summary.parameters.forEach { parameter ->
                        ShaderParameterControl(parameter, shader, form, onFormChanged)
                    }
                    TextButton(
                        modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                        onClick = {
                            val updated = shader.copyForDialog()
                            updated.glslParameters = null
                            onFormChanged(form.toBuilder().shader(updated).build())
                        },
                    ) {
                        Text(stringResource(R.string.render_shader_reset_parameters))
                    }
                }
            }
            ConfigSection(stringResource(R.string.render_shader_passes)) {
                ShaderPassDetails(summary, form.screenFilter)
            }
        }
    } else if (shader.hasTunableSettings()) {
        ConfigSection(stringResource(R.string.render_shader_parameters)) {
            ConfigActionPreference(
                title = stringResource(R.string.shader_tuning),
                description = stringResource(R.string.config_help_shader_tuning),
                onClick = onLegacyTuning,
            )
        }
    }
}

@Composable
private fun ShaderParameterControl(
    parameter: GlslpPreset.Parameter,
    shader: ShaderInfo,
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    val value = (shader.glslParameters?.get(parameter.id) ?: parameter.value)
        .coerceIn(parameter.minimum, parameter.maximum)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = parameter.label.ifBlank { parameter.id },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = "%.2f".format(value), style = MaterialTheme.typography.labelMedium)
        }
        if (parameter.maximum > parameter.minimum) {
            val intervals = ((parameter.maximum - parameter.minimum) / parameter.step).roundToInt()
            Slider(
                value = value,
                onValueChange = { selected ->
                    val updated = shader.copyForDialog()
                    val overrides = LinkedHashMap(shader.glslParameters ?: emptyMap())
                    overrides[parameter.id] = selected
                    updated.glslParameters = overrides
                    onFormChanged(form.toBuilder().shader(updated).build())
                },
                valueRange = parameter.minimum..parameter.maximum,
                steps = if (intervals in 2..500) intervals - 1 else 0,
            )
        }
    }
}

@Composable
private fun ShaderPassDetails(summary: ShaderPipelineSummary, defaultLinear: Boolean) {
    var expanded by rememberSaveable(summary.passes.size) { mutableStateOf(false) }
    ConfigActionPreference(
        title = stringResource(R.string.render_shader_pass_count, summary.passes.size),
        description = stringResource(R.string.render_shader_pass_hint),
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        summary.passes.forEachIndexed { index, pass ->
            val filter = when (pass.linearFilter) {
                true -> "Linear"
                false -> "Nearest"
                null -> if (defaultLinear) "Default (Linear)" else "Default (Nearest)"
            }
            val name = pass.sourcePath.substringAfterLast('/')
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("${index + 1}. $name", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${stringResource(R.string.render_pass_filter)}: $filter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${stringResource(R.string.render_pass_output)}: ${axisDescription(pass.x)} × ${axisDescription(pass.y)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun axisDescription(axis: GlslpPreset.Axis): String = when (axis.basis) {
    GlslpPreset.SizeBasis.SOURCE -> "Source ${axis.scale}×"
    GlslpPreset.SizeBasis.VIEWPORT -> "Viewport ${axis.scale}×"
    GlslpPreset.SizeBasis.ABSOLUTE -> "${axis.scale.roundToInt()} px"
}

@Composable
private fun RenderingPipelineContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onOpenShaders: () -> Unit,
) {
    val form = state.form
    ConfigSection(stringResource(R.string.render_game_output)) {
        ConfigValuePreference(
            title = stringResource(R.string.config_screen_size),
            description = stringResource(R.string.render_logical_screen_help),
            value = "${form.screenWidth} × ${form.screenHeight}",
            onClick = {},
            enabled = false,
        )
        ConfigValuePreference(
            title = stringResource(R.string.render_internal_resolution),
            description = stringResource(R.string.render_internal_unavailable),
            value = "Native (1×)",
            onClick = {},
            enabled = false,
        )
    }
    PipelineArrow()
    ConfigSection(stringResource(R.string.render_display_renderer)) {
        val options = stringArrayResource(R.array.pref_graphics_mode_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_graphics_mode_title),
            description = stringResource(R.string.config_help_graphics_mode),
            selected = options.getOrElse(form.graphicsMode) { options.firstOrNull().orEmpty() },
            options = options,
            onSelected = { onFormChanged(form.toBuilder().graphicsMode(it).build()) },
        )
    }
    PipelineArrow()
    ConfigSection(stringResource(R.string.render_shader_processing)) {
        ConfigValuePreference(
            title = stringResource(R.string.render_shader_effects),
            description = if (form.graphicsMode == 1)
                stringResource(R.string.render_shader_picker_help)
            else stringResource(R.string.render_shader_requires_gles),
            value = selectedShaderLabel(state),
            enabled = true,
            onClick = onOpenShaders,
        )
        val summary = state.shaderPipeline
        if (form.graphicsMode == 1 && form.shader?.preset != null && summary?.error == null) {
            if (summary != null) ShaderPassDetails(summary, form.screenFilter)
        } else if (summary?.error != null && form.graphicsMode == 1) {
            Text(
                summary.error,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    PipelineArrow()
    ConfigSection(stringResource(R.string.render_display_scaling)) {
        val scales = stringArrayResource(R.array.pref_scale_type_entries).toList()
        ConfigChoicePreference(
            title = stringResource(R.string.pref_screen_scale_type),
            description = stringResource(R.string.config_help_scale_type),
            selected = scales.getOrElse(form.screenScaleType) { scales.firstOrNull().orEmpty() },
            options = scales,
            onSelected = { onFormChanged(form.toBuilder().screenScaleType(it).build()) },
        )
        if (form.screenScaleType == 0) {
            ConfigNumberPreference(
                title = stringResource(R.string.PREF_SCALE_RATIO),
                description = stringResource(R.string.config_help_scale_ratio),
                value = form.screenScaleRatio,
                fallbackLabel = "100",
                valueSuffix = "%",
                validIntegerRange = 1..1000,
                onValueChange = { onFormChanged(form.toBuilder().screenScaleRatio(it).build()) },
            )
        }
        ConfigChoicePreference(
            title = stringResource(R.string.render_texture_filtering),
            description = stringResource(R.string.render_texture_filtering_help),
            selected = if (form.screenFilter) "Linear" else "Nearest",
            options = listOf("Nearest", "Linear"),
            onSelected = { onFormChanged(form.toBuilder().screenFilter(it == 1).build()) },
        )
        Text(
            stringResource(R.string.render_viewport_note),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PipelineArrow()
    ConfigSection(stringResource(R.string.render_composition)) {
        Text(
            stringResource(R.string.render_composition_help),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PipelineArrow() {
    Text(
        "↓",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
