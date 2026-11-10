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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.abs

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

/** The catalog explains effects; the active stack shows effective settings. */
@Composable
private fun effectStackSummary(
    entry: EffectEntry, definition: EffectCatalog.Effect?, legacy: ShaderInfo?,
): String {
    if (definition != null) {
        val choices = definition.parameters.filter { it.options.isNotEmpty() }
        if (choices.isNotEmpty()) {
            val labels = mutableListOf<String>()
            for (parameter in choices.take(2)) {
                val value = entry.parameters[parameter.id] ?: parameter.initial
                val index = round(value).toInt().coerceIn(0, parameter.options.lastIndex)
                labels.add(stringResource(parameter.options[index]))
            }
            return labels.joinToString(" · ")
        }
        val modified = definition.parameters.filter { parameter ->
            val value = entry.parameters[parameter.id]
            value != null && value.isFinite() && abs(value - parameter.initial) > 0.0001f
        }
        if (modified.isEmpty()) return stringResource(R.string.render_effect_default_settings)
        val locale = androidx.core.os.ConfigurationCompat.getLocales(
            androidx.compose.ui.platform.LocalConfiguration.current
        ).get(0) ?: java.util.Locale.ROOT
        val labels = mutableListOf<String>()
        for (parameter in modified.take(2)) {
            labels.add(stringResource(parameter.label) + " " +
                "%.2f".format(locale, entry.parameters.getValue(parameter.id)))
        }
        if (modified.size > 2) labels.add(stringResource(
            R.string.render_effect_summary_more, modified.size - 2))
        return labels.joinToString(" · ")
    }
    val adjustable = legacy?.settings?.count { it != null && it.max > it.min } ?: 0
    if (adjustable > 0) return stringResource(R.string.render_adjustable_count, adjustable)
    val author = entry.user?.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
    if (author != null) return stringResource(R.string.render_effect_legacy_author, author)
    return entry.user?.fragment ?: stringResource(R.string.render_effect_user)
}

@Composable
private fun legacyCatalogSubtitle(shader: ShaderInfo): String {
    val author = shader.getAuthor()
        ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
    return if (author != null) stringResource(R.string.render_effect_legacy_author, author)
    else stringResource(R.string.render_effect_legacy_source, shader.fragment ?: "")
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
    val root = state.shaderDirectory
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.render_effect_removed)
    val undoLabel = stringResource(R.string.render_effect_undo)
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

    fun remove(key: String) {
        val index = form.effects.indexOfFirst { it.key == key }
        if (index < 0) return
        val removed = form.effects[index]
        apply(form.effects.filterNot { it.key == key })
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val outcome = snackbar.showSnackbar(removedMessage, undoLabel,
                withDismissAction = false, duration = SnackbarDuration.Short)
            if (outcome == SnackbarResult.ActionPerformed &&
                    form.effects.none { it.key == key }) {
                val restored = form.effects.toMutableList()
                restored.add(index.coerceIn(0, restored.size), removed)
                apply(restored)
            }
        }
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
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data = data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
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
                    onRemove = ::remove,
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
                            ShaderCatalog.matchSelection(state.shaders, user)
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
    val moreLabel = stringResource(R.string.render_effect_more_actions)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val step = with(density) { 20.dp.toPx() }

    if (effects.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_add), contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.render_effect_empty_title),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.render_effect_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAdd, modifier = Modifier.widthIn(min = 200.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.render_effect_add))
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "intro") {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.render_effect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                ShaderCatalog.matchSelection(shaders, user)
            }
            val title = definition?.let { stringResource(it.title) }
                ?: entry.user?.name ?: entry.builtinId.orEmpty()
            val missing = entry.builtinId != null && definition == null
            val summary = effectStackSummary(entry, definition, legacy)
            val rowKey = "effect:" + entry.key
            var menuExpanded by remember(entry.key) { mutableStateOf(false) }
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
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (missing) {
                            Text(stringResource(R.string.render_effect_missing, title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert),
                                contentDescription = moreLabel)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (index > 0) {
                                DropdownMenuItem(
                                    text = { Text(upLabel) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_upward),
                                        contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onMove(index, index - 1)
                                    },
                                )
                            }
                            if (index < effects.lastIndex) {
                                DropdownMenuItem(
                                    text = { Text(downLabel) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_downward),
                                        contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onMove(index, index + 1)
                                    },
                                )
                            }
                            if (effects.size > 1) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(removeLabel) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onRemove(entry.key)
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
                item(key = "catalog-hint") {
                    Text(stringResource(R.string.render_effect_catalog_hint),
                        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(builtInEffects, key = { it.id }) { effect ->
                    EffectCatalogRow(
                        title = stringResource(effect.title),
                        description = stringResource(effect.description),
                        onClick = { onAdd(EffectEntry.builtin(effect.id)) },
                    )
                }
            } else {
                item(key = "user-file") {
                    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onPickUserFile,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.render_effect_user_add))
                        }
                        Text(stringResource(R.string.render_effect_user_folder_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            description = legacyCatalogSubtitle(variant),
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
                                description = legacyCatalogSubtitle(shader),
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
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_add), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
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
                    definition.parameters.forEach { parameter ->
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
                    TextButton(onClick = { onChange(entry.copy(parameters = emptyMap())) }) {
                        Text(stringResource(R.string.render_effect_restore))
                    }
                }
            } else if (entry.user != null) {
                val user = entry.user
                val author = user.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
                if (author != null) {
                    Text(stringResource(R.string.render_effect_legacy_author, author),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.render_effect_legacy_source, user.fragment ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (user.upscaling || user.outputResolution) {
                    Text(stringResource(R.string.render_effect_legacy_scale_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (knownLegacy == null) {
                    Text(stringResource(R.string.render_effect_legacy_metadata_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
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
