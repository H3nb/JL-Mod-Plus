// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.gles.GlslpPreset
import java.util.LinkedHashMap
import io.github.h3nb.jlmodplus.filepicker.FilePickerResultContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.compose.ui.platform.LocalConfiguration
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class RenderingSubpage { SHADERS, PARAMETERS, PASSES, CUSTOM }

@Composable
internal fun selectedShaderLabel(state: ConfigUiState): String {
    val selected = state.form.shader ?: return stringResource(R.string.render_shader_off)
    if (selected.customStages != null) return stringResource(R.string.render_custom_pipeline)
    return state.shaders.firstOrNull {
        (selected.preset != null && it.preset == selected.preset) ||
            (selected.vertex != null && selected.fragment != null &&
                it.vertex == selected.vertex && it.fragment == selected.fragment)
    }?.toString() ?: selected.name?.takeIf { it.isNotBlank() }
        ?: selected.preset?.substringAfterLast('/')
        ?: selected.fragment?.substringAfterLast('/')
        ?: stringResource(R.string.render_shader_off)
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
    onOpenParameters: () -> Unit,
    onOpenPasses: () -> Unit,
    onOpenCustom: () -> Unit,
    onBack: () -> Unit,
) {
    val title = when (page) {
        RenderingSubpage.SHADERS -> R.string.render_shader_effects
        RenderingSubpage.PARAMETERS -> R.string.render_shader_parameters
        RenderingSubpage.PASSES -> R.string.render_shader_passes
        RenderingSubpage.CUSTOM -> R.string.render_shader_effects
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
                    RenderingSubpage.SHADERS, RenderingSubpage.CUSTOM -> ShaderEffectsContent(
                        state, onFormChanged, onLegacyTuning, onOpenParameters, onOpenPasses,
                        onOpenCustom, onBack, page == RenderingSubpage.CUSTOM,
                    )
                    RenderingSubpage.PARAMETERS -> ShaderParametersContent(state, onFormChanged)
                    RenderingSubpage.PASSES -> ShaderPassesContent(state)
                }
            }
        }
    }
}

/** A shader mode is chosen directly on the page; file selection uses the scoped picker. */
@Composable
private fun ShaderEffectsContent(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onLegacyTuning: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenPasses: () -> Unit,
    onOpenCustom: () -> Unit,
    onCancelCustom: () -> Unit,
    editingCustom: Boolean,
) {
    val shader = state.form.shader
    val root = state.shaderDirectory
    val context = LocalContext.current
    val legacyEmptyMessage = stringResource(R.string.render_legacy_empty)
    val missingShaderMessage = stringResource(R.string.render_shader_missing)
    val legacyPickerTitle = stringResource(R.string.render_legacy_mode)
    val presetPickerTitle = stringResource(R.string.render_preset_mode)
    val legacy = shader?.fragment != null && shader.vertex != null
    val preset = shader?.preset != null
    val custom = shader?.customStages != null

    var legacyPath by rememberSaveable { mutableStateOf<String?>(null) }
    var legacyRequest by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var legacyOptions by androidx.compose.runtime.remember {
        mutableStateOf<List<ShaderInfo>>(emptyList())
    }
    var legacyError by rememberSaveable { mutableStateOf<String?>(null) }

    val legacyPicker = rememberLauncherForActivityResult(FilePickerResultContract()) { results ->
        relativeShaderPath(root, results.firstOrNull(), listOf(".ini"))?.let { path ->
            legacyOptions = emptyList()
            legacyError = null
            legacyPath = path
            legacyRequest++
        }
    }
    val presetPicker = rememberLauncherForActivityResult(FilePickerResultContract()) { results ->
        relativeShaderPath(root, results.firstOrNull(), listOf(".glslp"))?.let { path ->
            val picked = ShaderInfo(path.substringAfterLast('/'), "external")
            picked.preset = path
            legacyOptions = emptyList()
            legacyError = null
            onFormChanged(state.form.toBuilder().shader(picked).build())
            if (editingCustom) onCancelCustom()
        }
    }

    LaunchedEffect(root, legacyPath, legacyRequest) {
        val path = legacyPath ?: return@LaunchedEffect
        try {
            val options = withContext(Dispatchers.IO) {
                ShaderCatalog.readLegacyFile(root ?: throw IOException("Shader folder unavailable"), path)
            }
            if (options.isEmpty()) {
                legacyError = legacyEmptyMessage
            } else if (options.size == 1) {
                onFormChanged(state.form.toBuilder().shader(options.first()).build())
                if (editingCustom) onCancelCustom()
                legacyOptions = emptyList()
            } else {
                legacyOptions = options
            }
        } catch (error: IOException) {
            legacyOptions = emptyList()
            legacyError = error.message ?: missingShaderMessage
        }
        legacyPath = null
    }

    SectionLabel(stringResource(R.string.render_active_shader))
    Text(
        selectedShaderLabel(state),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
    MutedText(stringResource(R.string.render_gles_shader_default))
    SectionLabel(stringResource(R.string.render_shader_mode))

    ShaderModeRow(
        title = stringResource(R.string.render_shader_off),
        description = stringResource(R.string.render_shader_off_help),
        selected = shader == null && !editingCustom,
        onClick = {
            legacyOptions = emptyList()
            legacyError = null
            if (shader != null) onFormChanged(state.form.toBuilder().shader(null).build())
            if (editingCustom) onCancelCustom()
        },
    )
    ShaderModeRow(
        title = stringResource(R.string.render_legacy_mode),
        description = stringResource(R.string.render_legacy_mode_help),
        selected = legacy && !editingCustom,
        onClick = {
            if (root != null) legacyPicker.launch(shaderPickerIntent(
                context, root, listOf(".ini"), legacyPickerTitle,
            ))
        },
    )
    if (legacyOptions.isNotEmpty()) {
        Text(
            stringResource(R.string.render_legacy_select_variant),
            style = MaterialTheme.typography.titleSmall,
        )
        legacyOptions.forEach { option ->
            ShaderModeRow(
                title = option.toString(),
                description = stringResource(R.string.render_legacy_variant_description),
                selected = false,
                onClick = {
                    legacyOptions = emptyList()
                    onFormChanged(state.form.toBuilder().shader(option).build())
                    if (editingCustom) onCancelCustom()
                },
            )
        }
    }
    legacyError?.let { MutedText(it, isError = true) }
    ShaderModeRow(
        title = stringResource(R.string.render_preset_mode),
        description = stringResource(R.string.render_preset_mode_help),
        selected = preset && !editingCustom,
        onClick = {
            if (root != null) presetPicker.launch(shaderPickerIntent(
                context, root, listOf(".glslp"), presetPickerTitle,
            ))
        },
    )
    ShaderModeRow(
        title = stringResource(R.string.render_custom_pipeline),
        description = stringResource(R.string.render_custom_pipeline_hint),
        selected = custom || editingCustom,
        onClick = onOpenCustom,
    )
    if (editingCustom) {
        CustomPipelineContent(state, onFormChanged, onCancelCustom)
        return
    }

    if (legacy && shader.hasTunableSettings()) {
        ActionSurface(
            title = stringResource(R.string.render_shader_adjust),
            subtitle = stringResource(R.string.render_legacy_tuning_hint),
            onClick = onLegacyTuning,
        )
    }
    if (preset) {
        val summary = state.shaderPipeline
        if (summary?.error != null) {
            MutedText(summary.error, isError = true)
        } else if (summary != null) {
            val adjustable = summary.parameters.count { it.maximum > it.minimum }
            if (adjustable > 0) {
                ActionSurface(
                    title = stringResource(R.string.render_shader_adjust),
                    subtitle = stringResource(R.string.render_adjustable_count, adjustable),
                    onClick = onOpenParameters,
                )
            }
            if (summary.passes.size > 1) {
                ActionSurface(
                    title = stringResource(R.string.render_shader_passes),
                    subtitle = stringResource(R.string.render_pass_summary, summary.passes.size),
                    onClick = onOpenPasses,
                )
            }
        }
    }
    if (custom) {
        ActionSurface(
            title = stringResource(R.string.render_edit_custom_pipeline),
            subtitle = stringResource(R.string.render_custom_stage_count,
                shader.customStages?.size ?: 0),
            onClick = onOpenCustom,
        )
        state.shaderPipeline?.error?.let { MutedText(it, isError = true) }
    }
}

@Composable
private fun ShaderModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.render_mode_selected),
                )
            }
        }
    }
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
