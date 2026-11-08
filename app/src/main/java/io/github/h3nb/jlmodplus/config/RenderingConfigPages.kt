// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.filepicker.FilePickerResultContract
import io.github.h3nb.jlmodplus.gles.EffectCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

internal enum class RenderingSubpage { SHADERS }

@Composable
internal fun selectedShaderLabel(state: ConfigUiState): String {
    val active = state.form.effects.count { it.enabled }
    return if (active == 0) stringResource(R.string.render_shader_off)
        else stringResource(R.string.render_effect_count, active)
}

internal fun isTextureFilteringApplicable(state: ConfigUiState): Boolean = true

private fun passCount(effects: List<EffectEntry>): Int =
    effects.filter { it.enabled }.sumOf {
        if (it.builtinId != null) EffectCatalog.get(it.builtinId)?.passes?.size ?: 1 else 1
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenderingSettingsPage(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onShaderPickerOpened: () -> Unit,
    onBack: () -> Unit,
) {
    val form by rememberUpdatedState(state.form)
    val scope = rememberCoroutineScope()
    val root = state.shaderDirectory
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var legacyVariants by remember { mutableStateOf<List<ShaderInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val unavailable = stringResource(R.string.render_effect_legacy_file_error)
    val limitError = stringResource(R.string.render_effect_limit)
    val legacyPickerTitle = stringResource(R.string.render_effect_user_file)
    val currentEffects = state.form.effects
    fun apply(items: List<EffectEntry>) = onFormChanged(form.toBuilder().effects(items).build())
    fun add(entry: EffectEntry) {
        val proposed = form.effects + entry
        if (passCount(proposed) > 16) error = limitError
        else {
            error = null
            apply(proposed)
            showCatalog = false
        }
    }

    val picker = rememberLauncherForActivityResult(FilePickerResultContract()) { result ->
        val path = relativeShaderPath(root, result.firstOrNull(), listOf(".ini"))
        if (path != null && root != null) {
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { ShaderCatalog.readLegacyFile(root, path) }
                }
                found.onSuccess { variants ->
                    when (variants.size) {
                        0 -> error = unavailable
                        1 -> ShaderSelection.fromInfo(variants.single())?.let {
                            add(EffectEntry.legacy(it))
                        }
                        else -> { legacyVariants = variants; showCatalog = true }
                    }
                }.onFailure { error = it.message ?: unavailable }
            }
        } else if (result.isNotEmpty()) {
            error = unavailable
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.render_shader_effects)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.render_effect_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.form.graphicsMode != 1) {
                    Text(stringResource(R.string.render_effect_gles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (currentEffects.isEmpty()) {
                    Text(stringResource(R.string.render_effect_empty),
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    currentEffects.forEachIndexed { index, entry ->
                        val definition = EffectCatalog.get(entry.builtinId)
                        val knownLegacy = entry.user?.let { user ->
                            state.shaders.firstOrNull {
                                it.vertex == user.vertex && it.fragment == user.fragment
                            }
                        }
                        val name = definition?.let { stringResource(it.title) }
                            ?: entry.user?.name ?: entry.builtinId.orEmpty()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (definition != null)
                                                stringResource(definition.description)
                                            else stringResource(R.string.render_effect_user),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = entry.enabled,
                                        onCheckedChange = { enabled ->
                                            val updated = currentEffects.toMutableList()
                                            updated[index] = entry.copy(enabled = enabled)
                                            if (passCount(updated) <= 16) apply(updated)
                                            else error = limitError
                                        },
                                    )
                                }
                                if (definition == null && (entry.user == null || knownLegacy == null)) {
                                    Text(stringResource(R.string.render_effect_missing, name),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        val copy = currentEffects.toMutableList()
                                        java.util.Collections.swap(copy, index, index - 1)
                                        apply(copy)
                                    }, enabled = index > 0) {
                                        Text(stringResource(R.string.render_effect_move_up))
                                    }
                                    TextButton(onClick = {
                                        val copy = currentEffects.toMutableList()
                                        java.util.Collections.swap(copy, index, index + 1)
                                        apply(copy)
                                    }, enabled = index < currentEffects.lastIndex) {
                                        Text(stringResource(R.string.render_effect_move_down))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        expandedKey = if (expandedKey == entry.key) null else entry.key
                                    }) {
                                        Text(stringResource(R.string.render_effect_edit))
                                    }
                                    TextButton(onClick = {
                                        apply(currentEffects.filterIndexed { i, _ -> i != index })
                                    }) {
                                        Text(stringResource(R.string.render_effect_remove))
                                    }
                                }
                                if (expandedKey == entry.key) {
                                    if (definition != null) {
                                        definition.parameters.forEach { p ->
                                            val value = entry.parameters[p.id] ?: p.initial
                                            EffectParameterSlider(
                                                label = stringResource(p.label),
                                                value = value, minimum = p.minimum,
                                                maximum = p.maximum, step = p.step,
                                            ) { changed ->
                                                val next = entry.copy(parameters =
                                                    entry.parameters + (p.id to changed))
                                                apply(currentEffects.mapIndexed { i, e ->
                                                    if (i == index) next else e
                                                })
                                            }
                                        }
                                        TextButton(onClick = {
                                            apply(currentEffects.mapIndexed { i, e ->
                                                if (i == index) entry.copy(parameters = emptyMap()) else e
                                            })
                                        }) { Text(stringResource(R.string.render_effect_restore)) }
                                    } else if (knownLegacy != null && entry.user != null) {
                                        val user = entry.user
                                        knownLegacy.settings.forEachIndexed { slot, setting ->
                                            if (setting != null && setting.max > setting.min) {
                                                val value = user.values?.getOrNull(slot) ?: setting.def
                                                EffectParameterSlider(
                                                    label = setting.name ?: "Setting ${slot + 1}",
                                                    value = value, minimum = setting.min,
                                                    maximum = setting.max,
                                                    step = setting.step,
                                                ) { changed ->
                                                    val values = MutableList(4) { j ->
                                                        user.values?.getOrNull(j)
                                                            ?: knownLegacy.settings.getOrNull(j)?.def ?: 0f
                                                    }
                                                    values[slot] = changed
                                                    val next = entry.copy(user = user.copy(values = values))
                                                    apply(currentEffects.mapIndexed { i, e ->
                                                        if (i == index) next else e
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (currentEffects.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { apply(currentEffects.map { it.copy(enabled = false) }) }) {
                            Text(stringResource(R.string.render_effect_disable_all))
                        }
                        TextButton(onClick = {
                            val all = currentEffects.map { it.copy(enabled = true) }
                            if (passCount(all) <= 16) apply(all) else error = limitError
                        }) { Text(stringResource(R.string.render_effect_enable_all)) }
                    }
                }
                if (error != null) {
                    Text(requireNotNull(error), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { showCatalog = !showCatalog },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.render_effect_add),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                if (showCatalog) {
                    Text(stringResource(R.string.render_effect_builtin),
                        style = MaterialTheme.typography.titleMedium)
                    EffectCatalog.all().forEach { effect ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { add(EffectEntry.builtin(effect.id)) },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(stringResource(effect.title),
                                    style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(effect.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.render_effect_user),
                        style = MaterialTheme.typography.titleMedium)
                    state.shaders.filter { it.fragment != null && it.vertex != null }
                        .forEach { shader ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    ShaderSelection.fromInfo(shader)?.let {
                                        add(EffectEntry.legacy(it))
                                    }
                                },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Text(shader.toString(), Modifier.padding(14.dp))
                            }
                        }
                    TextButton(onClick = {
                        if (root != null) {
                            onShaderPickerOpened()
                            picker.launch(shaderPickerIntent(context, root, listOf(".ini"),
                                legacyPickerTitle))
                        } else error = unavailable
                    }) { Text(stringResource(R.string.render_effect_user_add)) }
                    if (legacyVariants.isNotEmpty()) {
                        Text(stringResource(R.string.render_legacy_select_variant),
                            style = MaterialTheme.typography.titleSmall)
                        legacyVariants.forEach { shader ->
                            TextButton(onClick = {
                                ShaderSelection.fromInfo(shader)?.let {
                                    add(EffectEntry.legacy(it))
                                }
                                legacyVariants = emptyList()
                            }) { Text(shader.toString()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectParameterSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    step: Float,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value, minimum, maximum) {
        mutableFloatStateOf(value.coerceIn(minimum, maximum))
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(java.util.Locale.ROOT, draft),
                style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = draft, valueRange = minimum..maximum,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                val adjusted = if (step > 0f) round(draft / step) * step else draft
                onCommit(adjusted.coerceIn(minimum, maximum))
            },
        )
    }
}
