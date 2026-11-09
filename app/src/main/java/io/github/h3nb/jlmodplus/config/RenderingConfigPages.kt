// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.filepicker.FilePickerResultContract
import io.github.h3nb.jlmodplus.gles.EffectCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

internal enum class RenderingSubpage { SHADERS }

private enum class EffectPage { STACK, CATALOG, PARAMETERS }

@Composable
internal fun selectedShaderLabel(state: ConfigUiState): String =
    if (state.form.effects.isEmpty()) stringResource(R.string.render_shader_off)
    else stringResource(R.string.render_effect_count, state.form.effects.size)

internal fun isTextureFilteringApplicable(state: ConfigUiState): Boolean = true

private fun passCount(effects: List<EffectEntry>): Int =
    effects.sumOf { entry ->
        if (entry.builtinId != null) EffectCatalog.get(entry.builtinId)?.passes?.size ?: 1 else 1
    }

private fun moved(effects: List<EffectEntry>, from: Int, to: Int): List<EffectEntry> =
    effects.toMutableList().apply { add(to, removeAt(from)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenderingSettingsPage(
    state: ConfigUiState,
    onFormChanged: (ConfigFormState) -> Unit,
    onShaderPickerOpened: () -> Unit,
    onBack: () -> Unit,
) {
    val form by rememberUpdatedState(state.form)
    val root = state.shaderDirectory
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(EffectPage.STACK) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogTab by rememberSaveable { mutableStateOf(0) }
    var legacyVariants by remember { mutableStateOf<List<ShaderInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val legacyError = stringResource(R.string.render_effect_legacy_file_error)
    val limitError = stringResource(R.string.render_effect_limit)
    val pickerTitle = stringResource(R.string.render_effect_user_file)
    val selected = state.form.effects.firstOrNull { it.key == selectedKey }
    val definition = selected?.builtinId?.let(EffectCatalog::get)
    val selectedTitle = definition?.let { stringResource(it.title) } ?: selected?.user?.name.orEmpty()

    fun apply(effects: List<EffectEntry>) {
        onFormChanged(form.toBuilder().effects(effects).build())
    }

    fun add(entry: EffectEntry) {
        val candidate = form.effects + entry
        if (passCount(candidate) > 16) {
            error = limitError
        } else {
            apply(candidate)
            error = null
            legacyVariants = emptyList()
            page = EffectPage.STACK
        }
    }

    val picker = rememberLauncherForActivityResult(FilePickerResultContract()) { result ->
        val path = relativeShaderPath(root, result.firstOrNull(), listOf(".ini"))
        if (path != null && root != null) {
            scope.launch {
                val variants = withContext(Dispatchers.IO) {
                    runCatching { ShaderCatalog.readLegacyFile(root, path) }
                }
                variants.onSuccess { shaders ->
                    when (shaders.size) {
                        0 -> error = legacyError
                        1 -> ShaderSelection.fromInfo(shaders.single())?.let {
                            add(EffectEntry.legacy(it))
                        }
                        else -> {
                            catalogTab = 1
                            legacyVariants = shaders
                            page = EffectPage.CATALOG
                        }
                    }
                }.onFailure { error = it.message ?: legacyError }
            }
        } else if (result.isNotEmpty()) {
            error = legacyError
        }
    }

    val back: () -> Unit = {
        if (page == EffectPage.STACK) onBack()
        else page = EffectPage.STACK
    }
    BackHandler(enabled = page != EffectPage.STACK) { page = EffectPage.STACK }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(when (page) {
                        EffectPage.STACK -> stringResource(R.string.render_shader_effects)
                        EffectPage.CATALOG -> stringResource(R.string.render_effect_add)
                        EffectPage.PARAMETERS -> selectedTitle
                    })
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (page) {
                EffectPage.STACK -> EffectStackContent(
                    effects = state.form.effects,
                    shaders = state.shaders,
                    graphicsMode = state.form.graphicsMode,
                    onAdd = {
                        catalogTab = 0
                        error = null
                        page = EffectPage.CATALOG
                    },
                    onSelect = { key ->
                        selectedKey = key
                        page = EffectPage.PARAMETERS
                    },
                    onRemove = { key ->
                        apply(form.effects.filterNot { it.key == key })
                    },
                    onMove = { from, to ->
                        val effects = form.effects
                        if (from in effects.indices && to in effects.indices && from != to) {
                            apply(moved(effects, from, to))
                        }
                    },
                )
                EffectPage.CATALOG -> EffectCatalogContent(
                    shaders = state.shaders,
                    tab = catalogTab,
                    onTabChanged = { catalogTab = it },
                    legacyVariants = legacyVariants,
                    error = error,
                    onAdd = ::add,
                    onPickUserFile = {
                        if (root != null) {
                            onShaderPickerOpened()
                            picker.launch(shaderPickerIntent(context, root, listOf(".ini"), pickerTitle))
                        } else error = legacyError
                    },
                )
                EffectPage.PARAMETERS -> if (selected != null) {
                    EffectParametersContent(
                        entry = selected,
                        definition = definition,
                        knownLegacy = selected.user?.let { user ->
                            state.shaders.firstOrNull {
                                it.vertex == user.vertex && it.fragment == user.fragment
                            }
                        },
                        onChange = { changed ->
                            apply(form.effects.map { if (it.key == changed.key) changed else it })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectStackContent(
    effects: List<EffectEntry>,
    shaders: List<ShaderInfo>,
    graphicsMode: Int,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestEffects by rememberUpdatedState(effects)
    val latestMove by rememberUpdatedState(onMove)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val upLabel = stringResource(R.string.render_effect_move_up)
    val downLabel = stringResource(R.string.render_effect_move_down)
    val removeLabel = stringResource(R.string.render_effect_remove)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val step = with(density) { 20.dp.toPx() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "intro") {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (effects.isEmpty()) {
                    Text(stringResource(R.string.render_effect_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.render_effect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (graphicsMode != 1) {
                    Text(stringResource(R.string.render_effect_gles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.render_effect_add))
                }
            }
        }
        itemsIndexed(effects, key = { _, entry -> "effect:" + entry.key }) { index, entry ->
            val definition = entry.builtinId?.let(EffectCatalog::get)
            val legacy = entry.user?.let { user ->
                shaders.firstOrNull { it.vertex == user.vertex && it.fragment == user.fragment }
            }
            val title = definition?.let { stringResource(it.title) }
                ?: entry.user?.name ?: entry.builtinId.orEmpty()
            val missing = definition == null && (entry.user == null || legacy == null)
            val rowKey = "effect:" + entry.key
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)
                    .zIndex(if (draggingKey == entry.key) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (draggingKey == entry.key) dragOffset else 0f
                    }
                    .pointerInput(entry.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = entry.key
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDrag = drag@{ change, amount ->
                                change.consume()
                                if (draggingKey != entry.key) return@drag
                                dragOffset += amount.y
                                val layout = listState.layoutInfo
                                val current = layout.visibleItemsInfo.firstOrNull {
                                    it.key == rowKey
                                } ?: return@drag
                                val center = current.offset + current.size / 2f + dragOffset
                                val other = if (dragOffset > 0f) {
                                    layout.visibleItemsInfo.lastOrNull {
                                        it.index > current.index &&
                                            (it.key as? String)?.startsWith("effect:") == true &&
                                            center > it.offset + it.size / 2f
                                    }
                                } else {
                                    layout.visibleItemsInfo.firstOrNull {
                                        it.index < current.index &&
                                            (it.key as? String)?.startsWith("effect:") == true &&
                                            center < it.offset + it.size / 2f
                                    }
                                }
                                if (other != null) {
                                    val from = latestEffects.indexOfFirst {
                                        it.key == entry.key
                                    }
                                    val targetKey = (other.key as String).removePrefix("effect:")
                                    val to = latestEffects.indexOfFirst { it.key == targetKey }
                                    if (from >= 0 && to >= 0 && from != to) {
                                        val shift = if (to > from) {
                                            other.offset + other.size - current.offset - current.size
                                        } else other.offset - current.offset
                                        dragOffset -= shift.toFloat()
                                        latestMove(from, to)
                                    }
                                }
                                val direction = when {
                                    center < layout.viewportStartOffset + edge -> -step
                                    center > layout.viewportEndOffset - edge -> step
                                    else -> 0f
                                }
                                if (direction != 0f) {
                                    scope.launch {
                                        val scrolled = listState.scrollBy(direction)
                                        if (draggingKey == entry.key) dragOffset += scrolled
                                    }
                                }
                            },
                        )
                    },
                onClick = { onSelect(entry.key) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (definition != null) stringResource(definition.description)
                                else stringResource(R.string.render_effect_user),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onRemove(entry.key) }) {
                            Icon(painterResource(R.drawable.ic_delete),
                                contentDescription = removeLabel,
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (missing) {
                        Text(stringResource(R.string.render_effect_missing, title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                        ) {
                            Icon(painterResource(R.drawable.ic_arrow_upward),
                                contentDescription = upLabel)
                        }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < effects.lastIndex,
                        ) {
                            Icon(painterResource(R.drawable.ic_arrow_downward),
                                contentDescription = downLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectCatalogContent(
    shaders: List<ShaderInfo>,
    tab: Int,
    onTabChanged: (Int) -> Unit,
    legacyVariants: List<ShaderInfo>,
    error: String?,
    onAdd: (EffectEntry) -> Unit,
    onPickUserFile: () -> Unit,
) {
    val resources = LocalResources.current
    val builtInEffects = EffectCatalog.all().sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { resources.getString(it.title) },
    )
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { onTabChanged(0) },
                text = { Text(stringResource(R.string.render_effect_builtin)) })
            Tab(selected = tab == 1, onClick = { onTabChanged(1) },
                text = { Text(stringResource(R.string.render_effect_user)) })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (error != null) {
                item(key = "error") {
                    Text(error, modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (tab == 0) {
                items(builtInEffects, key = { it.id }) { effect ->
                    EffectCatalogRow(
                        title = stringResource(effect.title),
                        description = stringResource(effect.description),
                        onClick = { onAdd(EffectEntry.builtin(effect.id)) },
                    )
                }
            } else {
                item(key = "user-file") {
                    FilledTonalButton(onClick = onPickUserFile,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.render_effect_user_add))
                    }
                }
                if (legacyVariants.isNotEmpty()) {
                    item(key = "variants") {
                        Text(stringResource(R.string.render_legacy_select_variant),
                            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    items(legacyVariants) { variant ->
                        EffectCatalogRow(
                            title = variant.toString(),
                            description = stringResource(R.string.render_effect_user),
                            onClick = {
                                ShaderSelection.fromInfo(variant)?.let {
                                    onAdd(EffectEntry.legacy(it))
                                }
                            },
                        )
                    }
                } else {
                    val available = shaders.filter { it.vertex != null && it.fragment != null }
                    if (available.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.render_effect_user_empty),
                                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        items(available) { shader ->
                            EffectCatalogRow(
                                title = shader.toString(),
                                description = stringResource(R.string.render_effect_user),
                                onClick = {
                                    ShaderSelection.fromInfo(shader)?.let {
                                        onAdd(EffectEntry.legacy(it))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectCatalogRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EffectParametersContent(
    entry: EffectEntry,
    definition: EffectCatalog.Effect?,
    knownLegacy: ShaderInfo?,
    onChange: (EffectEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (definition != null) {
                Text(stringResource(definition.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (definition.parameters.isEmpty()) {
                    Text(stringResource(R.string.render_effect_no_parameters))
                } else {
                    var advanced by rememberSaveable(entry.key) { mutableStateOf(false) }
                    val isCrt = definition.id == "crt-easymode"
                    val primary = listOf("SCANLINE_STRENGTH", "MASK_STRENGTH",
                        "BRIGHT_BOOST", "SHARPNESS_H", "SHARPNESS_V")
                    val visible = if (isCrt && !advanced) primary.mapNotNull { id ->
                        definition.parameters.firstOrNull { it.id == id }
                    } else definition.parameters
                    visible.forEach { parameter ->
                        val value = entry.parameters[parameter.id] ?: parameter.initial
                        if (parameter.options.isNotEmpty()) {
                            val selected = round(value).toInt().coerceIn(
                                0, parameter.options.lastIndex)
                            var expanded by remember(entry.key, parameter.id) {
                                mutableStateOf(false)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(parameter.label),
                                    style = MaterialTheme.typography.bodyMedium)
                                Box {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { expanded = true },
                                    ) {
                                        Text(stringResource(parameter.options[selected]))
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        parameter.options.forEachIndexed { index, label ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(label)) },
                                                onClick = {
                                                    expanded = false
                                                    onChange(entry.copy(parameters =
                                                        entry.parameters + (parameter.id to index.toFloat())))
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            EffectParameterSlider(
                                label = stringResource(parameter.label),
                                value = value,
                                minimum = parameter.minimum,
                                maximum = parameter.maximum,
                                step = parameter.step,
                            ) { changed ->
                                onChange(entry.copy(parameters =
                                    entry.parameters + (parameter.id to changed)))
                            }
                        }
                    }
                    if (isCrt) {
                        TextButton(onClick = { advanced = !advanced }) {
                            Text(stringResource(if (advanced) R.string.render_effect_basic
                                else R.string.render_effect_advanced))
                        }
                    }
                    TextButton(onClick = { onChange(entry.copy(parameters = emptyMap())) }) {
                        Text(stringResource(R.string.render_effect_restore))
                    }
                }
            } else if (entry.user != null && knownLegacy != null) {
                val user = entry.user
                val adjustable = knownLegacy.settings.withIndex().filter { (_, setting) ->
                    setting != null && setting.max > setting.min
                }
                if (adjustable.isEmpty()) {
                    Text(stringResource(R.string.render_effect_no_parameters))
                } else {
                    adjustable.forEach { (slot, setting) ->
                        val settingInfo = requireNotNull(setting)
                        EffectParameterSlider(
                            label = settingInfo.name ?: stringResource(R.string.render_effect_parameter_number, slot + 1),
                            value = user.values?.getOrNull(slot) ?: settingInfo.def,
                            minimum = settingInfo.min,
                            maximum = settingInfo.max,
                            step = settingInfo.step,
                        ) { value ->
                            val values = MutableList(4) { i ->
                                user.values?.getOrNull(i)
                                    ?: knownLegacy.settings.getOrNull(i)?.def ?: 0f
                            }
                            values[slot] = value
                            onChange(entry.copy(user = user.copy(values = values)))
                        }
                    }
                    TextButton(onClick = {
                        onChange(entry.copy(user = user.copy(
                            values = List(4) { i -> knownLegacy.settings.getOrNull(i)?.def ?: 0f }
                        )))
                    }) {
                        Text(stringResource(R.string.render_effect_restore))
                    }
                }
            } else {
                Text(stringResource(R.string.render_effect_missing,
                    entry.user?.name ?: entry.builtinId.orEmpty()),
                    color = MaterialTheme.colorScheme.error)
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
    val locale = androidx.core.os.ConfigurationCompat.getLocales(
        androidx.compose.ui.platform.LocalConfiguration.current
    ).get(0) ?: java.util.Locale.ROOT
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(locale, draft),
                style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = draft, valueRange = minimum..maximum,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                val adjusted = if (step > 0f) minimum + round((draft - minimum) / step) * step
                    else draft
                onCommit(adjusted.coerceIn(minimum, maximum))
            },
        )
    }
}
