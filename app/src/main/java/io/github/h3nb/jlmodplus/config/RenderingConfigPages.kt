// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.gles.GlslpPreset
import java.util.LinkedHashMap
import androidx.compose.ui.platform.LocalConfiguration
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class RenderingSubpage { SHADERS, PARAMETERS, PASSES, PIPELINE, CUSTOM }

internal fun selectedShaderLabel(state: ConfigUiState): String {
    val selected = state.form.shader ?: return "Off"
    if (selected.customStages != null) return "Custom pipeline"
    return state.shaders.firstOrNull { it == selected }?.toString()
        ?: "Unavailable: " + (selected.name ?: selected.preset?.substringAfterLast('/')
            ?: selected.fragment?.substringAfterLast('/') ?: "Shader")
}

/**
 * The game-wide texture filter is ignored only when every GLSLP pass
 * specifies its own filter and the last pass draws directly to the viewport.
 * Keep the control visible if inspection fails: the fallback presenter uses it.
 */
internal fun isTextureFilteringApplicable(state: ConfigUiState): Boolean {
    if (state.form.graphicsMode != 1 || (state.form.shader?.preset == null
            && state.form.shader?.customStages == null)) return true
    val summary = state.shaderPipeline ?: return true
    if (summary.error != null || summary.passes.isEmpty()) return true
    if (summary.passes.any { it.linearFilter == null }) return true
    val finalPass = summary.passes.last()
    return !(finalPass.x.basis == GlslpPreset.SizeBasis.VIEWPORT
        && finalPass.y.basis == GlslpPreset.SizeBasis.VIEWPORT
        && finalPass.x.scale == 1.0 && finalPass.y.scale == 1.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenderingSettingsPage(
    page: RenderingSubpage,
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onLegacyTuning: () -> Unit,
    onOpenShaders: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenPasses: () -> Unit,
    onOpenCustom: () -> Unit,
    onBack: () -> Unit,
) {
    val title = when (page) {
        RenderingSubpage.SHADERS -> R.string.render_shader_effects
        RenderingSubpage.PARAMETERS -> R.string.render_shader_parameters
        RenderingSubpage.PASSES -> R.string.render_shader_passes
        RenderingSubpage.PIPELINE -> R.string.render_pipeline_title
        RenderingSubpage.CUSTOM -> R.string.render_custom_pipeline
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
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
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (page) {
                    RenderingSubpage.SHADERS -> ShaderEffectsContent(
                        state, onFormChanged, onLegacyTuning, onOpenParameters, onOpenPasses,
                        onOpenCustom,
                    )
                    RenderingSubpage.PARAMETERS -> ShaderParametersContent(state, onFormChanged)
                    RenderingSubpage.PASSES -> ShaderPassesContent(state)
                    RenderingSubpage.PIPELINE -> RenderingPipelineContent(
                        state, onFormChanged, onOpenShaders, onOpenPasses,
                    )
                    RenderingSubpage.CUSTOM -> CustomPipelineContent(
                        state, onFormChanged, onBack,
                    )
                }
            }
        }
    }
}

/** Selection is the primary action. Tuning and pass inspection never crowd this page. */
@Composable
private fun ShaderEffectsContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onLegacyTuning: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenPasses: () -> Unit,
    onOpenCustom: () -> Unit,
) {
    val form = state.form
    val shader = form.shader
    var presetPickerVisible by rememberSaveable { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.render_shader_preset))
    ActionSurface(
        title = selectedShaderLabel(state),
        subtitle = stringResource(R.string.render_shader_change),
        onClick = { presetPickerVisible = true },
    )
    if (presetPickerVisible) {
        ConfigChoiceDialog(
            title = stringResource(R.string.render_shader_preset),
            selected = selectedShaderLabel(state),
            options = state.shaders.map {
                if (it.fragment == null && it.preset == null) "Off" else it.toString()
            },
            onDismissRequest = { presetPickerVisible = false },
            onSelected = { index ->
                presetPickerVisible = false
                onFormChanged(form.toBuilder()
                    .shader(if (index == 0) null else state.shaders.getOrNull(index)).build())
            },
        )
    }
    ActionSurface(
        title = stringResource(R.string.render_custom_pipeline),
        subtitle = stringResource(R.string.render_custom_pipeline_hint),
        onClick = onOpenCustom,
    )
    if (shader == null) {
        MutedText(stringResource(R.string.render_no_shader))
        return
    }
    if (form.graphicsMode != 1) {
        MutedText(stringResource(R.string.render_shader_requires_gles))
    }
    if (shader.customStages == null && state.shaders.none { it == shader }) {
        MutedText(stringResource(R.string.render_shader_missing), isError = true)
    }
    if (shader.customStages != null) {
        val summary = state.shaderPipeline
        if (summary?.error != null) MutedText(summary.error, isError = true)
        else if (summary != null) MutedText(
            stringResource(R.string.render_custom_stage_count, summary.passes.size),
        )
        return
    }
    if (shader.preset == null) {
        if (shader.hasTunableSettings()) {
            SectionLabel(stringResource(R.string.render_shader_parameters))
            ActionSurface(
                title = stringResource(R.string.render_shader_adjust),
                subtitle = stringResource(R.string.render_legacy_tuning_hint),
                onClick = onLegacyTuning,
            )
        }
        return
    }
    val summary = state.shaderPipeline
    if (summary?.error != null) {
        MutedText(summary.error, isError = true)
        return
    }
    if (summary == null) return

    val adjustable = summary.parameters.count { it.maximum > it.minimum }
    SectionLabel(stringResource(R.string.render_shader_customization))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            if (adjustable > 0) {
                ActionRow(
                    title = stringResource(R.string.render_shader_adjust),
                    subtitle = stringResource(R.string.render_adjustable_count, adjustable),
                    onClick = onOpenParameters,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            ActionRow(
                title = stringResource(R.string.render_shader_passes),
                subtitle = stringResource(R.string.render_pass_summary, summary.passes.size),
                onClick = onOpenPasses,
            )
        }
    }
    MutedText(stringResource(R.string.render_shader_preset_note))
}

@Composable
private fun ShaderParametersContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    val shader = state.form.shader ?: return
    val summary = state.shaderPipeline ?: return
    if (summary.error != null) {
        MutedText(summary.error, isError = true)
        return
    }
    Text(
        selectedShaderLabel(state),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    MutedText(stringResource(R.string.render_parameters_hint))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            summary.parameters.forEach { parameter ->
                if (parameter.maximum > parameter.minimum) {
                    ShaderParameterControl(parameter, shader, state.form, onFormChanged)
                } else {
                    // Some upstream presets encode a non-editable group heading as a parameter.
                    Text(
                        parameter.label.trim().trim('=').trim().ifEmpty { parameter.id },
                        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = {
                    val updated = shader.copyForDialog()
                    updated.glslParameters = null
                    onFormChanged(state.form.toBuilder().shader(updated).build())
                },
            ) {
                Text(stringResource(R.string.render_shader_reset_parameters))
            }
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
    val configuration = LocalConfiguration.current
    @Suppress("DEPRECATION")
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        configuration.locales[0] else configuration.locale
    val savedValue = (shader.glslParameters?.get(parameter.id) ?: parameter.value)
        .coerceIn(parameter.minimum, parameter.maximum)
    var draft by rememberSaveable(shader.preset, parameter.id) { mutableFloatStateOf(savedValue) }
    LaunchedEffect(savedValue) { draft = savedValue }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                parameter.label.ifBlank { parameter.id },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                String.format(locale, "%.2f", draft),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft.coerceIn(parameter.minimum, parameter.maximum),
            onValueChange = { draft = it },
            onValueChangeFinished = {
                val snapped = (parameter.minimum +
                    ((draft - parameter.minimum) / parameter.step).roundToInt() * parameter.step)
                    .coerceIn(parameter.minimum, parameter.maximum)
                draft = snapped
                if (abs(snapped - savedValue) > 0.000001f) {
                    val updated = shader.copyForDialog()
                    val overrides = LinkedHashMap(shader.glslParameters ?: emptyMap())
                    overrides[parameter.id] = snapped
                    updated.glslParameters = overrides
                    onFormChanged(form.toBuilder().shader(updated).build())
                }
            },
            valueRange = parameter.minimum..parameter.maximum,
            // Hundreds of discrete Material3 ticks overwhelm the track on phones.
            steps = 0,
        )
    }
}

@Composable
private fun ShaderPassesContent(state: ConfigUiState) {
    val summary = state.shaderPipeline ?: return
    if (summary.error != null) {
        MutedText(summary.error, isError = true)
        return
    }
    Text(
        selectedShaderLabel(state),
        style = MaterialTheme.typography.titleMedium,
    )
    MutedText(stringResource(R.string.render_pass_readonly))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        PassList(summary, state.form.screenFilter)
    }
}

@Composable
private fun PassList(summary: ShaderPipelineSummary, defaultLinear: Boolean) {
    Column {
        summary.passes.forEachIndexed { index, pass ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    (index + 1).toString().padStart(2, '0'),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        pass.sourcePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val filter = when (pass.linearFilter) {
                        true -> "Linear"
                        false -> "Nearest"
                        null -> if (defaultLinear) "Default · Linear" else "Default · Nearest"
                    }
                    Text(
                        "$filter  ·  ${passSizeDescription(pass)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun passSizeDescription(pass: ShaderPipelineSummary.Pass): String {
    val x = axisDescription(pass.x)
    val y = axisDescription(pass.y)
    return if (x == y) x else "$x × $y"
}

private fun axisDescription(axis: GlslpPreset.Axis): String = when (axis.basis) {
    GlslpPreset.SizeBasis.SOURCE -> "Source ${axis.scale}×"
    GlslpPreset.SizeBasis.VIEWPORT -> "Viewport ${axis.scale}×"
    GlslpPreset.SizeBasis.ABSOLUTE -> "${axis.scale.roundToInt()} px"
}

/** One compact vertical route, not a nested wall of settings cards. */
@Composable
private fun RenderingPipelineContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onOpenShaders: () -> Unit,
    onOpenPasses: () -> Unit,
) {
    val form = state.form
    val renderers = stringArrayResource(R.array.pref_graphics_mode_entries).toList()
    val scales = stringArrayResource(R.array.pref_scale_type_entries).toList()
    var rendererPickerVisible by rememberSaveable { mutableStateOf(false) }
    var scaleControlsVisible by rememberSaveable { mutableStateOf(false) }
    var passPreviewVisible by rememberSaveable { mutableStateOf(false) }

    MutedText(stringResource(R.string.render_pipeline_intro))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            PipelineStage(
                number = "01",
                label = stringResource(R.string.render_game_output),
                value = "${form.screenWidth} × ${form.screenHeight}",
                note = stringResource(R.string.render_native_short),
            )
            StageConnector()
            PipelineStage(
                number = "02",
                label = stringResource(R.string.render_display_renderer),
                value = if (form.graphicsMode == 1) "OpenGL ES"
                    else renderers.getOrElse(form.graphicsMode) { renderers.firstOrNull().orEmpty() },
                onClick = { rendererPickerVisible = true },
            )
            if (form.graphicsMode == 1) {
                StageConnector()
                val summary = state.shaderPipeline
                PipelineStage(
                    number = "03",
                    label = stringResource(R.string.render_shader_processing),
                    value = selectedShaderLabel(state),
                    note = if (form.shader?.preset != null && summary != null && summary.error == null)
                        stringResource(R.string.render_pass_summary, summary.passes.size)
                    else null,
                    onClick = onOpenShaders,
                )
                if (summary?.error == null && !summary?.passes.isNullOrEmpty()) {
                    TextButton(
                        modifier = Modifier.padding(start = 38.dp),
                        onClick = { passPreviewVisible = !passPreviewVisible },
                    ) {
                        Text(stringResource(if (passPreviewVisible)
                            R.string.render_collapse_passes else R.string.render_expand_passes))
                    }
                    if (passPreviewVisible) {
                        Surface(
                            modifier = Modifier.padding(start = 38.dp, bottom = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column {
                                PassList(summary!!, form.screenFilter)
                                TextButton(
                                    modifier = Modifier.align(Alignment.End),
                                    onClick = onOpenPasses,
                                ) {
                                    Text(stringResource(R.string.render_view_all_passes))
                                }
                            }
                        }
                    }
                }
            }
            StageConnector()
            PipelineStage(
                number = if (form.graphicsMode == 1) "04" else "03",
                label = stringResource(R.string.render_display_viewport),
                value = scales.getOrElse(form.screenScaleType) { scales.firstOrNull().orEmpty() },
                note = if (isTextureFilteringApplicable(state))
                    stringResource(
                        R.string.render_pipeline_filter_summary,
                        if (form.screenFilter) "Linear" else "Nearest",
                    ) else null,
                onClick = { scaleControlsVisible = !scaleControlsVisible },
            )
            if (scaleControlsVisible) {
                Column(modifier = Modifier.padding(start = 38.dp)) {
                    ConfigChoicePreference(
                        title = stringResource(R.string.pref_screen_scale_type),
                        description = null,
                        selected = scales.getOrElse(form.screenScaleType) { scales.firstOrNull().orEmpty() },
                        options = scales,
                        compact = true,
                        onSelected = { onFormChanged(form.toBuilder().screenScaleType(it).build()) },
                    )
                    if (form.screenScaleType == 0) {
                        ConfigNumberPreference(
                            title = stringResource(R.string.PREF_SCALE_RATIO),
                            description = "",
                            value = form.screenScaleRatio,
                            fallbackLabel = "100",
                            valueSuffix = "%",
                            validIntegerRange = 1..1000,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            onValueChange = {
                                onFormChanged(form.toBuilder().screenScaleRatio(it).build())
                            },
                        )
                    }
                    if (isTextureFilteringApplicable(state)) {
                        ConfigChoicePreference(
                            title = stringResource(R.string.render_texture_filtering),
                            description = null,
                            selected = if (form.screenFilter) "Linear" else "Nearest",
                            options = listOf("Nearest", "Linear"),
                            compact = true,
                            onSelected = {
                                onFormChanged(form.toBuilder().screenFilter(it == 1).build())
                            },
                        )
                    }
                }
            }
        }
    }
    MutedText(stringResource(R.string.render_pipeline_note))

    if (rendererPickerVisible) {
        ConfigChoiceDialog(
            title = stringResource(R.string.pref_graphics_mode_title),
            selected = renderers.getOrElse(form.graphicsMode) { renderers.firstOrNull().orEmpty() },
            options = renderers,
            onDismissRequest = { rendererPickerVisible = false },
            onSelected = {
                rendererPickerVisible = false
                onFormChanged(form.toBuilder().graphicsMode(it).build())
            },
        )
    }
}

@Composable
private fun PipelineStage(
    number: String,
    label: String,
    value: String,
    note: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
    else Modifier
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (note != null) Text(
                note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
            )
        }
        if (onClick != null) Text("›", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StageConnector() {
    Text(
        "│",
        modifier = Modifier.padding(start = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun ActionSurface(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ActionRow(title, subtitle, onClick)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 2.dp, top = 5.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun MutedText(text: String, isError: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}
