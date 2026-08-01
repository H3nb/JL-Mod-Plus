/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.applist

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.Config
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class AppsListComposeController(
    context: Context,
    private val callback: Callback,
) {
    companion object {
        const val LAYOUT_TYPE_LIST = 0
        const val LAYOUT_TYPE_GRID = 1
    }

    interface Callback {
        fun onAppClick(item: AppItem)
        fun onAddClick()
        fun onContextAction(item: AppItem, actionId: Int)
        fun onSearchQueryChanged(query: String)
        fun onLayoutChanged(layoutType: Int)
        fun onToolbarAction(actionId: Int)
    }

    private val root = FrameLayout(context)
    private val composeView = ComposeView(context)
    private var itemsState by mutableStateOf<List<AppItem>>(emptyList())
    private var emptyMessageState by mutableStateOf("")
    private var layoutState by mutableIntStateOf(LAYOUT_TYPE_GRID)
    private var searchExpandedState by mutableStateOf(false)
    private var searchQueryState by mutableStateOf("")
    private val shortcutSupported = androidx.core.content.pm.ShortcutManagerCompat
        .isRequestPinShortcutSupported(context)

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            AppComposeTheme {
                AppsListContent(
                    items = itemsState,
                    emptyMessage = emptyMessageState,
                    layoutType = layoutState,
                    shortcutSupported = shortcutSupported,
                    onAddClick = callback::onAddClick,
                    onAppClick = callback::onAppClick,
                    onContextAction = callback::onContextAction,
                    searchExpanded = searchExpandedState,
                    searchQuery = searchQueryState,
                    onSearchExpandedChanged = { searchExpandedState = it },
                    onSearchQueryChanged = { searchQueryState = it },
                    onSearchQueryDebounced = callback::onSearchQueryChanged,
                    onLayoutToggle = {
                        callback.onLayoutChanged(toggleLayout())
                    },
                    onToolbarAction = callback::onToolbarAction,
                )
            }
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun getRootView(): View = root

    fun setItems(items: List<AppItem>, emptyMessage: String) {
        itemsState = items.toList()
        emptyMessageState = emptyMessage
    }

    fun setLayout(layoutType: Int) {
        layoutState = layoutType
    }

    fun getLayoutType(): Int = layoutState

    fun toggleLayout(): Int {
        layoutState = if (layoutState == LAYOUT_TYPE_GRID) {
            LAYOUT_TYPE_LIST
        } else {
            LAYOUT_TYPE_GRID
        }
        return layoutState
    }

    fun collapseSearch(): Boolean {
        if (!searchExpandedState) {
            return false
        }
        searchQueryState = ""
        searchExpandedState = false
        return true
    }
}

private val appIconCache = object : LruCache<String, Bitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsListContent(
    items: List<AppItem>,
    emptyMessage: String,
    layoutType: Int,
    shortcutSupported: Boolean,
    onAddClick: () -> Unit,
    onAppClick: (AppItem) -> Unit,
    onContextAction: (AppItem, Int) -> Unit,
    searchExpanded: Boolean = false,
    searchQuery: String = "",
    onSearchExpandedChanged: (Boolean) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchQueryDebounced: (String) -> Unit = {},
    onLayoutToggle: () -> Unit = {},
    onToolbarAction: (Int) -> Unit = {},
    initialContextItem: AppItem? = null,
    imagePathOf: (AppItem) -> String? = { it.imagePathExt },
    canReinstall: (AppItem) -> Boolean = {
        File(it.pathExt + Config.MIDLET_RES_FILE).exists()
    },
) {
    var contextItem by androidx.compose.runtime.remember {
        mutableStateOf(initialContextItem)
    }
    LaunchedEffect(searchQuery) {
        delay(300)
        onSearchQueryDebounced(searchQuery)
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeToolbar(
                layoutType = layoutType,
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                onSearchExpandedChanged = onSearchExpandedChanged,
                onSearchQueryChanged = onSearchQueryChanged,
                onLayoutToggle = onLayoutToggle,
                onToolbarAction = onToolbarAction,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (items.isEmpty()) {
                    Text(
                        text = emptyMessage,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                    )
                } else if (layoutType == AppsListComposeController.LAYOUT_TYPE_GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 88.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = items,
                            key = { it.id },
                            contentType = { "app-grid" },
                        ) { item ->
                            AppGridItem(
                                item = item,
                                shortcutSupported = shortcutSupported,
                                contextItem = contextItem,
                                onAppClick = onAppClick,
                                onContextItemChanged = { contextItem = it },
                                onContextAction = onContextAction,
                                imagePathOf = imagePathOf,
                                canReinstall = canReinstall,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = items,
                            key = { it.id },
                            contentType = { "app-list" },
                        ) { item ->
                            AppListItem(
                                item = item,
                                shortcutSupported = shortcutSupported,
                                contextItem = contextItem,
                                onAppClick = onAppClick,
                                onContextItemChanged = { contextItem = it },
                                onContextAction = onContextAction,
                                imagePathOf = imagePathOf,
                                canReinstall = canReinstall,
                            )
                        }
                    }
                }
                FloatingActionButton(
                    onClick = onAddClick,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_white),
                        contentDescription = stringResource(R.string.add),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeToolbar(
    layoutType: Int,
    searchExpanded: Boolean,
    searchQuery: String,
    onSearchExpandedChanged: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onLayoutToggle: () -> Unit,
    onToolbarAction: (Int) -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val contentColor = MaterialTheme.colorScheme.onSecondary
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = contentColor,
        shadowElevation = 4.dp,
    ) {
        if (searchExpanded) {
            SearchToolbar(
                query = searchQuery,
                contentColor = contentColor,
                onQueryChanged = onSearchQueryChanged,
                onClose = {
                    onSearchQueryChanged("")
                    onSearchExpandedChanged(false)
                },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onSearchExpandedChanged(true) }) {
                    SearchGlyph(contentColor)
                }
                IconButton(onClick = onLayoutToggle) {
                    Icon(
                        painter = painterResource(
                            if (layoutType == AppsListComposeController.LAYOUT_TYPE_LIST) {
                                R.drawable.ic_action_apps_view_grid
                            } else {
                                R.drawable.ic_action_apps_view_list
                            },
                        ),
                        contentDescription = stringResource(R.string.pref_apps_view),
                        tint = contentColor,
                    )
                }
                IconButton(onClick = { onToolbarAction(R.id.action_sort) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_sort),
                        contentDescription = stringResource(R.string.pref_app_sort_title),
                        tint = contentColor,
                    )
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        MoreGlyph(contentColor)
                    }
                    HomeOverflowMenu(
                        expanded = overflowExpanded,
                        onDismiss = { overflowExpanded = false },
                        onAction = {
                            overflowExpanded = false
                            onToolbarAction(it)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchToolbar(
    query: String,
    contentColor: Color,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(contentColor)
        BasicTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester),
            textStyle = TextStyle(color = contentColor, fontSize = 18.sp),
            cursorBrush = SolidColor(contentColor),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
            ),
        )
        IconButton(onClick = onClose) {
            CloseGlyph(contentColor)
        }
    }
}

@Composable
private fun HomeOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        HomeOverflowMenuItems(onAction)
    }
}

@Composable
private fun HomeOverflowMenuItems(onAction: (Int) -> Unit) {
    val actions = listOf(
        R.id.action_about to R.string.about,
        R.id.action_settings to R.string.action_settings,
        R.id.action_profiles to R.string.profiles,
        R.id.action_help to R.string.help,
        R.id.action_save_log to R.string.save_log,
        R.id.action_exit_app to R.string.exit,
    )
    actions.forEach { (actionId, titleId) ->
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(titleId),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            onClick = { onAction(actionId) },
        )
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .padding(2.dp),
    ) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension * 0.3f,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun MoreGlyph(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val radius = 1.7.dp.toPx()
        listOf(0.25f, 0.5f, 0.75f).forEach { position ->
            drawCircle(color, radius, Offset(size.width / 2f, size.height * position))
        }
    }
}

@Composable
private fun CloseGlyph(color: Color) {
    Canvas(modifier = Modifier.size(24.dp).padding(4.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
    }
}

@Composable
private fun AppGridItem(
    item: AppItem,
    shortcutSupported: Boolean,
    contextItem: AppItem?,
    onAppClick: (AppItem) -> Unit,
    onContextItemChanged: (AppItem?) -> Unit,
    onContextAction: (AppItem, Int) -> Unit,
    imagePathOf: (AppItem) -> String?,
    canReinstall: (AppItem) -> Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onAppClick(item) },
                onLongClick = { onContextItemChanged(item) },
            )
            .padding(horizontal = 2.dp, vertical = 5.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(imagePathOf(item), 48.dp)
            Text(
                text = item.title,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (contextItem?.id == item.id) {
            val reinstallAvailable = remember(item.id, item.path) { canReinstall(item) }
            AppContextMenu(
                item,
                shortcutSupported,
                reinstallAvailable,
                contextItem,
                onContextItemChanged,
                onContextAction,
            )
        }
    }
}

@Composable
private fun AppListItem(
    item: AppItem,
    shortcutSupported: Boolean,
    contextItem: AppItem?,
    onAppClick: (AppItem) -> Unit,
    onContextItemChanged: (AppItem?) -> Unit,
    onContextAction: (AppItem, Int) -> Unit,
    imagePathOf: (AppItem) -> String?,
    canReinstall: (AppItem) -> Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onAppClick(item) },
                onLongClick = { onContextItemChanged(item) },
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(imagePathOf(item), 36.dp)
            Column(
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            ) {
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        text = item.author,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = item.version,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        if (contextItem?.id == item.id) {
            val reinstallAvailable = remember(item.id, item.path) { canReinstall(item) }
            AppContextMenu(
                item,
                shortcutSupported,
                reinstallAvailable,
                contextItem,
                onContextItemChanged,
                onContextAction,
            )
        }
    }
}

@Composable
private fun AppIcon(imagePath: String?, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val targetSizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val fallbackBitmap = remember(context) {
        appIconCache.get("launcher-fallback")
            ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.also {
                appIconCache.put("launcher-fallback", it)
            }
    }
    val bitmap by produceState<Bitmap?>(initialValue = fallbackBitmap, context, imagePath, targetSizePx) {
        value = imagePath?.let { loadAppIcon(it, targetSizePx) }
            ?: fallbackBitmap
    }
    val loadedBitmap = bitmap
    val imageBitmap = remember(loadedBitmap) { loadedBitmap?.asImageBitmap() }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop,
        )
    } else {
        Image(
            painter = painterResource(R.drawable.ic_setting_default),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop,
        )
    }
}

private val appIconDecodeSemaphore = Semaphore(permits = 2)

private suspend fun loadAppIcon(imagePath: String, targetSizePx: Int): Bitmap? {
    val key = "$imagePath#$targetSizePx"
    appIconCache.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        appIconDecodeSemaphore.withPermit {
            appIconCache.get(key)?.let { return@withPermit it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withPermit null
            }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > targetSizePx * 2
                || bounds.outHeight / sampleSize > targetSizePx * 2) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                imagePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )?.also { appIconCache.put(key, it) }
        }
    }
}

@Composable
private fun AppContextMenu(
    item: AppItem,
    shortcutSupported: Boolean,
    canReinstall: Boolean,
    contextItem: AppItem?,
    onContextItemChanged: (AppItem?) -> Unit,
    onContextAction: (AppItem, Int) -> Unit,
) {
    DropdownMenu(
        expanded = contextItem?.id == item.id,
        onDismissRequest = { onContextItemChanged(null) },
    ) {
        AppContextMenuItems(
            item = item,
            shortcutSupported = shortcutSupported,
            canReinstall = canReinstall,
            onDismiss = { onContextItemChanged(null) },
            onContextAction = onContextAction,
        )
    }
}

@Composable
private fun AppContextMenuItems(
    item: AppItem,
    shortcutSupported: Boolean,
    canReinstall: Boolean,
    onDismiss: () -> Unit,
    onContextAction: (AppItem, Int) -> Unit,
) {
    if (shortcutSupported) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_context_shortcut)) },
            onClick = {
                onDismiss()
                onContextAction(item, R.id.action_context_shortcut)
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_context_rename)) },
        onClick = {
            onDismiss()
            onContextAction(item, R.id.action_context_rename)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_settings)) },
        onClick = {
            onDismiss()
            onContextAction(item, R.id.action_context_settings)
        },
    )
    if (canReinstall) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_reinstall)) },
            onClick = {
                onDismiss()
                onContextAction(item, R.id.action_context_reinstall)
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_context_delete)) },
        onClick = {
            onDismiss()
            onContextAction(item, R.id.action_context_delete)
        },
    )
}

private fun previewApps(): List<AppItem> = listOf(
    AppItem("asphalt", "Asphalt - Urban GT 3D", "Gameloft", "1.0").apply { id = 1 },
    AppItem("bounce-tales", "Bounce Tales", "Nokia", "2.1").apply { id = 2 },
    AppItem("opera-mini", "Opera Mini", "Opera Software", "4.5").apply { id = 3 },
    AppItem("space-impact", "Space Impact", "Nokia", "1.3").apply { id = 4 },
)

@Preview(name = "Apps grid", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListGridPreview() {
    AppsListPreviewContent(AppsListComposeController.LAYOUT_TYPE_GRID, darkTheme = false)
}

@Preview(name = "Apps list", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListListPreview() {
    AppsListPreviewContent(AppsListComposeController.LAYOUT_TYPE_LIST, darkTheme = false)
}

@Preview(name = "Apps grid dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AppsListGridDarkPreview() {
    AppsListPreviewContent(AppsListComposeController.LAYOUT_TYPE_GRID, darkTheme = true)
}

@Preview(name = "Apps list dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AppsListListDarkPreview() {
    AppsListPreviewContent(AppsListComposeController.LAYOUT_TYPE_LIST, darkTheme = true)
}

@Preview(name = "Apps search", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListSearchPreview() {
    AppsListPreviewContent(
        AppsListComposeController.LAYOUT_TYPE_GRID,
        darkTheme = false,
        searchExpanded = true,
        searchQuery = "Asphalt",
    )
}

@Preview(name = "Apps search dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AppsListSearchDarkPreview() {
    AppsListPreviewContent(
        AppsListComposeController.LAYOUT_TYPE_GRID,
        darkTheme = true,
        searchExpanded = true,
        searchQuery = "Asphalt",
    )
}

@Preview(name = "Apps overflow", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListOverflowPreview() {
    AppsListPreviewContent(
        AppsListComposeController.LAYOUT_TYPE_GRID,
        darkTheme = false,
        overflowExpanded = true,
    )
}

@Preview(name = "Apps overflow dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AppsListOverflowDarkPreview() {
    AppsListPreviewContent(
        AppsListComposeController.LAYOUT_TYPE_GRID,
        darkTheme = true,
        overflowExpanded = true,
    )
}

@Composable
private fun AppsListPreviewContent(
    layoutType: Int,
    darkTheme: Boolean,
    searchExpanded: Boolean = false,
    searchQuery: String = "",
    overflowExpanded: Boolean = false,
) {
    AppComposeTheme(darkTheme = darkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppsListContent(
                items = previewApps(),
                emptyMessage = "",
                layoutType = layoutType,
                shortcutSupported = true,
                onAddClick = {},
                onAppClick = {},
                onContextAction = { _, _ -> },
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                imagePathOf = { null },
                canReinstall = { false },
            )
            if (overflowExpanded) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp)
                        .width(200.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                ) {
                    Column {
                        HomeOverflowMenuItems(onAction = {})
                    }
                }
            }
        }
    }
}

@Preview(name = "Apps empty", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListEmptyPreview() {
    AppComposeTheme {
        AppsListContent(
            items = emptyList(),
            emptyMessage = stringResource(R.string.no_data_for_display),
            layoutType = AppsListComposeController.LAYOUT_TYPE_GRID,
            shortcutSupported = true,
            onAddClick = {},
            onAppClick = {},
            onContextAction = { _, _ -> },
            imagePathOf = { null },
            canReinstall = { false },
        )
    }
}

@Preview(
    name = "Apps empty dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun AppsListEmptyDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        AppsListContent(
            items = emptyList(),
            emptyMessage = stringResource(R.string.no_data_for_display),
            layoutType = AppsListComposeController.LAYOUT_TYPE_GRID,
            shortcutSupported = true,
            onAddClick = {},
            onAppClick = {},
            onContextAction = { _, _ -> },
            imagePathOf = { null },
            canReinstall = { false },
        )
    }
}

@Preview(name = "Apps context menu", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun AppsListContextMenuPreview() {
    val item = previewApps().first()
    AppComposeTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.width(280.dp), shadowElevation = 8.dp) {
                Column {
                    AppContextMenuItems(
                        item = item,
                        shortcutSupported = true,
                        canReinstall = true,
                        onDismiss = {},
                        onContextAction = { _, _ -> },
                    )
                }
            }
        }
    }
}

@Preview(
    name = "Apps context menu dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun AppsListContextMenuDarkPreview() {
    val item = previewApps().first()
    AppComposeTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.width(280.dp), shadowElevation = 8.dp) {
                Column {
                    AppContextMenuItems(
                        item = item,
                        shortcutSupported = true,
                        canReinstall = true,
                        onDismiss = {},
                        onContextAction = { _, _ -> },
                    )
                }
            }
        }
    }
}
