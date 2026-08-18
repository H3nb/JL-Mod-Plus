/*
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

package ru.playsoftware.j2meloader.applist

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.BuildConfig
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.librarydb.LibraryPlayStatsFormatter
import ru.playsoftware.j2meloader.librarydb.LibraryQuickView
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.TransientNoticeHost
import ru.playsoftware.j2meloader.ui.TransientNoticeState
import kotlin.math.roundToInt

enum class LibraryLayout {
    List,
    Grid,
}

enum class LibraryIconRatio(
    val widthToHeight: Float,
) {
    Square(1f),
    Portrait(3f / 4f),
}

enum class LibraryGridSpacing(
    val value: Dp,
) {
    Compact(4.dp),
    Standard(8.dp),
    Spacious(12.dp),
}

private enum class LibraryDestination {
    Apps,
    Collections,
    Options,
}

data class LibraryAppUiItem(
    val id: Int,
    val title: String,
    val author: String,
    val version: String,
    val iconPath: String?,
    val canReinstall: Boolean,
    val description: String = "",
    val iconRevision: Long = 0L,
    val favorite: Boolean = false,
    val sourceTitle: String = title,
    val sourceAuthor: String = author,
    val sourceVersion: String = version,
    val sourceDescription: String = description,
    val playCount: Long = 0L,
    val totalPlayTimeMs: Long = 0L,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val apps: List<LibraryAppUiItem> = emptyList(),
    val appliedFilter: String = "",
    val layout: LibraryLayout = LibraryLayout.List,
    val iconRatio: LibraryIconRatio = LibraryIconRatio.Square,
    val hideGridTitles: Boolean = false,
    val gridSpacing: LibraryGridSpacing = LibraryGridSpacing.Standard,
    val sortVariant: Int = 0,
    val quickView: LibraryQuickView = LibraryQuickView.All,
    val databaseControlsReady: Boolean = false,
    val canAddShortcut: Boolean = true,
    val loadingCompleted: Int = 0,
    val loadingTotal: Int = 0,
    val loadingStorageKey: String = "",
    val errorMessage: String? = null,
)

interface LibraryActions {
    fun onSearch(query: String)
    fun onQuickView(quickView: LibraryQuickView) = Unit
    fun onFavorite(appId: Int, favorite: Boolean) = Unit
    fun onUpdateMetadata(
        appId: Int,
        title: String,
        vendor: String,
        version: String,
        description: String,
    ) = Unit
    fun onPickIcon(appId: Int) = Unit
    fun onResetIcon(appId: Int) = Unit
    fun onLayoutChange(layout: LibraryLayout)
    fun onIconRatioChange(iconRatio: LibraryIconRatio) = Unit
    fun onHideGridTitlesChange(hide: Boolean) = Unit
    fun onGridSpacingChange(spacing: LibraryGridSpacing) = Unit
    fun onSort(sortIndex: Int)
    fun onInstall()
    fun onOpenApp(appId: Int)
    fun onAddShortcut(appId: Int)
    fun onRename(appId: Int, title: String)
    fun onOpenAppSettings(appId: Int)
    fun onShareApp(appId: Int) = Unit
    fun onExportAppBundle(appId: Int) = Unit
    fun onReinstall(appId: Int)
    fun onDelete(appId: Int)
    fun onOpenSettings()
    fun onOpenProfiles()
    fun onOpenCrashReports()
    fun onSaveLog()
    fun onExit()
    fun onRetryLibrary()
}

class LibraryComposeController(
    composeView: ComposeView,
    private val actions: LibraryActions,
    initialLayout: LibraryLayout,
    initialSortVariant: Int,
    initialIconRatio: LibraryIconRatio,
    initialHideGridTitles: Boolean,
    initialGridSpacing: LibraryGridSpacing,
    canAddShortcut: Boolean,
) {
    private val noticeState = TransientNoticeState()
    private var state by mutableStateOf(
        LibraryUiState(
            layout = initialLayout,
            iconRatio = initialIconRatio,
            hideGridTitles = initialHideGridTitles,
            gridSpacing = initialGridSpacing,
            sortVariant = initialSortVariant,
            canAddShortcut = canAddShortcut,
        ),
    )

    init {
        composeView.id = R.id.library_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LibraryScreen(state = state, actions = actions)
                    val noticeBottomPadding = if (
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ) 8.dp else 88.dp
                    TransientNoticeHost(
                        state = noticeState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = noticeBottomPadding),
                    )
                }
            }
        }
    }

    fun updateApps(
        items: List<LibraryAppUiItem>,
        appliedFilter: String,
        quickView: LibraryQuickView,
    ) {
        state = state.copy(
            loading = false,
            apps = items,
            appliedFilter = appliedFilter,
            quickView = quickView,
            databaseControlsReady = true,
            loadingCompleted = 0,
            loadingTotal = 0,
            loadingStorageKey = "",
            errorMessage = null,
        )
    }

    fun showLoading() {
        state = state.copy(
            loading = true,
            apps = emptyList(),
            databaseControlsReady = false,
            loadingCompleted = 0,
            loadingTotal = 0,
            loadingStorageKey = "",
            errorMessage = null,
        )
    }

    fun showIndexing(completed: Int, total: Int, storageKey: String) {
        state = state.copy(
            loading = true,
            apps = emptyList(),
            databaseControlsReady = false,
            loadingCompleted = completed,
            loadingTotal = total,
            loadingStorageKey = storageKey,
            errorMessage = null,
        )
    }

    fun showError(message: String) {
        state = state.copy(
            loading = false,
            apps = emptyList(),
            databaseControlsReady = false,
            errorMessage = message,
        )
    }

    fun updateLayout(layout: LibraryLayout) {
        state = state.copy(layout = layout)
    }

    fun updateIconRatio(iconRatio: LibraryIconRatio) {
        state = state.copy(iconRatio = iconRatio)
    }

    fun updateHideGridTitles(hide: Boolean) {
        state = state.copy(hideGridTitles = hide)
    }

    fun updateGridSpacing(spacing: LibraryGridSpacing) {
        state = state.copy(gridSpacing = spacing)
    }

    fun updateSort(sortVariant: Int) {
        state = state.copy(sortVariant = sortVariant)
    }

    fun showNotice(message: String) {
        noticeState.show(message)
    }
}

internal enum class LibraryInfoDialog {
    About,
    Help,
    Licenses,
}

private val LibraryScaffoldInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { LibraryDestination.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val destination = LibraryDestination.entries[pagerState.currentPage]
    var showInstallFab by rememberSaveable { mutableStateOf(true) }
    var showNavigationBar by rememberSaveable { mutableStateOf(true) }
    var appActions by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var metadataTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var appActionsCollectionId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var infoDialog by remember { mutableStateOf<LibraryInfoDialog?>(null) }
    val isImeVisible = WindowInsets.isImeVisible
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val collectionsHost = actions as? LibraryCollectionsHost

    val metadataApp = metadataTarget?.let { target ->
        state.apps.firstOrNull { it.id == target.id } ?: target
    }
    if (metadataApp != null) {
        LibraryMetadataEditorScreen(
            app = metadataApp,
            onBack = { metadataTarget = null },
            onSave = { title, vendor, description ->
                metadataTarget = null
                actions.onUpdateMetadata(
                    metadataApp.id,
                    title,
                    vendor,
                    metadataApp.version,
                    description,
                )
            },
            onPickIcon = { actions.onPickIcon(metadataApp.id) },
            onResetIcon = { actions.onResetIcon(metadataApp.id) },
        )
        return
    }

    LaunchedEffect(destination) {
        if (destination != LibraryDestination.Apps) {
            showInstallFab = true
            showNavigationBar = true
            appActions = null
            appActionsCollectionId = null
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        if (isLandscape) {
            LibraryNavigationRail(
                selected = destination,
                onSelected = { section ->
                    coroutineScope.launch { pagerState.animateScrollToPage(section.ordinal) }
                },
            )
        }

        Scaffold(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentWindowInsets = LibraryScaffoldInsets,
            bottomBar = {
                if (!isLandscape && !isImeVisible) {
                    AnimatedVisibility(
                        visible = showNavigationBar,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + expandVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            expandFrom = Alignment.Bottom,
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            initialOffsetY = { it / 2 },
                        ),
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + shrinkVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            shrinkTowards = Alignment.Bottom,
                        ) + slideOutVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            targetOffsetY = { it / 2 },
                        ),
                    ) {
                        LibraryNavigationBar(
                            selected = destination,
                            onSelected = { section ->
                                coroutineScope.launch { pagerState.animateScrollToPage(section.ordinal) }
                            },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (!isImeVisible && destination == LibraryDestination.Apps) {
                    AnimatedVisibility(
                        visible = showInstallFab,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + scaleIn(
                            initialScale = 0.86f,
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            initialOffsetY = { it / 2 },
                        ),
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + scaleOut(
                            targetScale = 0.86f,
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideOutVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            targetOffsetY = { it / 2 },
                        ),
                    ) {
                        FloatingActionButton(onClick = actions::onInstall) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.install),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                when (LibraryDestination.entries[page]) {
                    LibraryDestination.Apps -> LibraryAppsDestination(
                        state = state,
                        scaffoldPadding = padding,
                        onOpenApp = actions::onOpenApp,
                        onOpenActions = {
                            appActions = it
                            appActionsCollectionId = null
                        },
                        onSearch = actions::onSearch,
                        onQuickView = actions::onQuickView,
                        onFavorite = actions::onFavorite,
                        onSort = actions::onSort,
                        onRetry = actions::onRetryLibrary,
                        onFabVisibilityChanged = { showInstallFab = it },
                        onNavigationVisibilityChanged = { visible ->
                            if (!isLandscape) showNavigationBar = visible
                        },
                    )
                    LibraryDestination.Collections -> if (collectionsHost != null) {
                        LibraryCollectionsDestination(
                            host = collectionsHost,
                            libraryState = state,
                            scaffoldPadding = padding,
                            onOpenActions = { app, collectionId ->
                                appActions = app
                                appActionsCollectionId = collectionId
                            },
                        )
                    } else {
                        LibraryCollectionsDestination(padding)
                    }
                    LibraryDestination.Options -> LibraryOptionsDestination(
                        state = state,
                        scaffoldPadding = padding,
                        onLayoutChange = actions::onLayoutChange,
                        onIconRatioChange = actions::onIconRatioChange,
                        onHideGridTitlesChange = actions::onHideGridTitlesChange,
                        onGridSpacingChange = actions::onGridSpacingChange,
                        onAbout = { infoDialog = LibraryInfoDialog.About },
                        onSettings = actions::onOpenSettings,
                        onHelp = { infoDialog = LibraryInfoDialog.Help },
                        onCrashReports = actions::onOpenCrashReports,
                        onSaveLog = actions::onSaveLog,
                        onExit = actions::onExit,
                    )
                }
            }
        }
    }

    appActions?.let { app ->
        AppActionsDialog(
            app = app,
            onDismiss = {
                appActions = null
                appActionsCollectionId = null
            },
            onShortcut = if (state.canAddShortcut) {
                { actions.onAddShortcut(app.id) }
            } else {
                null
            },
            onRename = { renameTarget = app },
            onSettings = { actions.onOpenAppSettings(app.id) },
            onAddToCollection = if (state.databaseControlsReady && collectionsHost != null) {
                { collectionsHost.onRequestAddToCollection(app.id) }
            } else {
                null
            },
            onRemoveFromCollection = if (
                state.databaseControlsReady && collectionsHost != null && appActionsCollectionId != null
            ) {
                {
                    collectionsHost.onRemoveAppFromCollection(
                        app.id,
                        requireNotNull(appActionsCollectionId),
                    )
                }
            } else {
                null
            },
            onShareApp = if (state.databaseControlsReady) {
                { actions.onShareApp(app.id) }
            } else {
                null
            },
            onExportAppBundle = if (state.databaseControlsReady) {
                { actions.onExportAppBundle(app.id) }
            } else {
                null
            },
            onReinstall = { actions.onReinstall(app.id) },
            onDelete = { deleteTarget = app },
            onEditMetadata = if (state.databaseControlsReady) {
                { metadataTarget = app }
            } else {
                null
            },
        )
    }
    renameTarget?.let { app ->
        RenameAppDialog(
            app = app,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                actions.onRename(app.id, title)
            },
        )
    }
    deleteTarget?.let { app ->
        val layout = libraryDialogLayout()
        AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.message_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    actions.onDelete(app.id)
                }) {
                    Text(
                        text = stringResource(R.string.action_context_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    infoDialog?.let { dialog ->
        LibraryInformationDialog(
            dialog = dialog,
            onDismiss = { infoDialog = null },
            onOpen = { infoDialog = it },
        )
    }
    collectionsHost?.let { LibraryCollectionsDialogHost(it) }
}

private const val LIBRARY_CHROME_ANIMATION_MILLIS = 220
private const val LIBRARY_CHROME_HIDE_DISTANCE_DP = 10f
private const val LIBRARY_CHROME_REVEAL_DISTANCE_DP = 18f
private val LibraryGridMinCellSize = 88.dp
private const val LIBRARY_GRID_ARTWORK_FRACTION = 0.78f
private val LibraryGridMaxArtworkSize = 72.dp

internal class LibraryChromeScrollHysteresis(
    hideDistancePx: Float,
    revealDistancePx: Float,
) {
    private val hideThreshold = hideDistancePx.coerceAtLeast(1f)
    private val revealThreshold = revealDistancePx.coerceAtLeast(1f)
    private var forwardDistance = 0f
    private var reverseDistance = 0f

    var chromeVisible: Boolean = true
        private set

    fun reset(): Boolean? {
        forwardDistance = 0f
        reverseDistance = 0f
        return if (chromeVisible) {
            null
        } else {
            chromeVisible = true
            true
        }
    }

    fun revealNow(): Boolean? {
        forwardDistance = 0f
        reverseDistance = 0f
        return if (chromeVisible) {
            null
        } else {
            chromeVisible = true
            true
        }
    }

    fun onScrollDelta(delta: Float): Boolean? {
        if (delta < 0f) {
            reverseDistance = 0f
            forwardDistance += -delta
            if (chromeVisible && forwardDistance >= hideThreshold) {
                chromeVisible = false
                forwardDistance = 0f
                return false
            }
        } else if (delta > 0f) {
            forwardDistance = 0f
            reverseDistance += delta
            if (!chromeVisible && reverseDistance >= revealThreshold) {
                chromeVisible = true
                reverseDistance = 0f
                return true
            }
        }
        return null
    }
}

@Composable
private fun LibraryNavigationRail(
    selected: LibraryDestination,
    onSelected: (LibraryDestination) -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LibraryNavigationRailItem(
            destination = LibraryDestination.Apps,
            selected = selected,
            label = R.string.library_destination_apps,
            icon = R.drawable.ic_apps,
            onSelected = onSelected,
        )
        LibraryNavigationRailItem(
            destination = LibraryDestination.Collections,
            selected = selected,
            label = R.string.library_destination_collections,
            icon = R.drawable.ic_collections,
            onSelected = onSelected,
        )
        LibraryNavigationRailItem(
            destination = LibraryDestination.Options,
            selected = selected,
            label = R.string.library_destination_options,
            icon = R.drawable.ic_options,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun ColumnScope.LibraryNavigationRailItem(
    destination: LibraryDestination,
    selected: LibraryDestination,
    label: Int,
    icon: Int,
    onSelected: (LibraryDestination) -> Unit,
) {
    val labelText = stringResource(label)
    NavigationRailItem(
        selected = destination == selected,
        onClick = { onSelected(destination) },
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = labelText,
            )
        },
        label = { Text(labelText) },
        alwaysShowLabel = false,
    )
}

@Composable
private fun LibraryNavigationBar(
    selected: LibraryDestination,
    onSelected: (LibraryDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        LibraryNavigationItem(
            destination = LibraryDestination.Apps,
            selected = selected,
            label = R.string.library_destination_apps,
            icon = R.drawable.ic_apps,
            onSelected = onSelected,
        )
        LibraryNavigationItem(
            destination = LibraryDestination.Collections,
            selected = selected,
            label = R.string.library_destination_collections,
            icon = R.drawable.ic_collections,
            onSelected = onSelected,
        )
        LibraryNavigationItem(
            destination = LibraryDestination.Options,
            selected = selected,
            label = R.string.library_destination_options,
            icon = R.drawable.ic_options,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun RowScope.LibraryNavigationItem(
    destination: LibraryDestination,
    selected: LibraryDestination,
    label: Int,
    icon: Int,
    onSelected: (LibraryDestination) -> Unit,
) {
    val labelText = stringResource(label)
    NavigationBarItem(
        selected = destination == selected,
        onClick = { onSelected(destination) },
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = labelText,
            )
        },
        label = { Text(labelText) },
        alwaysShowLabel = false,
    )
}

@Composable
internal fun LibraryAppsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onSearch: (String) -> Unit,
    onQuickView: (LibraryQuickView) -> Unit,
    onFavorite: (Int, Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onRetry: () -> Unit,
    onFabVisibilityChanged: (Boolean) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    showQuickViews: Boolean = true,
    queryStateKey: Any? = Unit,
) {
    var query by rememberSaveable(queryStateKey) { mutableStateOf(state.appliedFilter) }
    var sortVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val headerHeightPx = remember { mutableStateOf(0) }
    val headerOffsetPx = remember { mutableStateOf(0f) }
    val hideDistancePx = with(LocalDensity.current) { LIBRARY_CHROME_HIDE_DISTANCE_DP.dp.toPx() }
    val revealDistancePx = with(LocalDensity.current) { LIBRARY_CHROME_REVEAL_DISTANCE_DP.dp.toPx() }
    val chromeHysteresis = remember(hideDistancePx, revealDistancePx) {
        LibraryChromeScrollHysteresis(hideDistancePx, revealDistancePx)
    }

    LaunchedEffect(query) {
        onSearch(query)
    }

    LaunchedEffect(state.layout) {
        headerOffsetPx.value = 0f
        chromeHysteresis.reset()
        onFabVisibilityChanged(true)
        onNavigationVisibilityChanged(true)
        snapshotFlow {
            if (state.layout == LibraryLayout.List) {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.canScrollForward || listState.canScrollBackward,
                )
            } else {
                Triple(
                    gridState.firstVisibleItemIndex,
                    gridState.firstVisibleItemScrollOffset,
                    gridState.canScrollForward || gridState.canScrollBackward,
                )
            }
        }.collectLatest { (index, offset, canScroll) ->
            if (!canScroll || (index == 0 && offset == 0)) {
                headerOffsetPx.value = 0f
                chromeHysteresis.reset()
                onFabVisibilityChanged(true)
                onNavigationVisibilityChanged(true)
            }
        }
    }

    val listModifier = Modifier
        .fillMaxSize()
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    val renderHeader: @Composable (Modifier, Boolean) -> Unit = { headerModifier, interactive ->
        LibraryAppsHeader(
            modifier = headerModifier,
            query = query,
            onQueryChange = { query = it },
            state = state,
            sortVisible = sortVisible,
            onSortVisibilityChanged = { sortVisible = it },
            onQuickView = onQuickView,
            onSort = onSort,
            title = title,
            onBack = onBack,
            showQuickViews = showQuickViews,
            interactive = interactive,
        )
    }
    val headerScrollConnection = remember(
        chromeHysteresis,
        state.layout,
        onFabVisibilityChanged,
        onNavigationVisibilityChanged,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                val height = headerHeightPx.value
                if (delta == 0f || height <= 0) return Offset.Zero

                val canScroll = if (state.layout == LibraryLayout.List) {
                    listState.canScrollForward || listState.canScrollBackward
                } else {
                    gridState.canScrollForward || gridState.canScrollBackward
                }
                if (!canScroll) {
                    if (headerOffsetPx.value != 0f || !chromeHysteresis.chromeVisible) {
                        headerOffsetPx.value = 0f
                        chromeHysteresis.reset()
                        onFabVisibilityChanged(true)
                        onNavigationVisibilityChanged(true)
                    }
                    return Offset.Zero
                }

                val fullyHidden = headerOffsetPx.value <= -height.toFloat() + 0.5f
                var visibilityChange = chromeHysteresis.onScrollDelta(delta)
                val shouldMoveHeader =
                    delta < 0f ||
                        !fullyHidden ||
                        chromeHysteresis.chromeVisible ||
                        visibilityChange == true
                if (shouldMoveHeader) {
                    headerOffsetPx.value =
                        (headerOffsetPx.value + delta).coerceIn(-height.toFloat(), 0f)
                }

                if (
                    delta > 0f &&
                    headerOffsetPx.value >= -0.5f &&
                    !chromeHysteresis.chromeVisible
                ) {
                    visibilityChange = chromeHysteresis.revealNow()
                }
                visibilityChange?.let { visible ->
                    onFabVisibilityChanged(visible)
                    onNavigationVisibilityChanged(visible)
                }

                return Offset.Zero
            }
        }
    }
    Box(
        modifier = listModifier
            .padding(scaffoldPadding)
            .clipToBounds()
            .nestedScroll(headerScrollConnection),
    ) {
        if (state.layout == LibraryLayout.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = LibraryGridMinCellSize),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    renderHeader(
                        Modifier
                            .alpha(0f)
                            .clearAndSetSemantics { },
                        false,
                    )
                }
                when {
                    state.errorMessage != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryErrorState(state.errorMessage, onRetry)
                    }
                    state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryLoadingState(state)
                    }
                    state.apps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryEmptyState(state.appliedFilter)
                    }
                    else -> items(state.apps, key = { it.id }) { app ->
                        LibraryGridItem(
                            app = app,
                            iconRatio = state.iconRatio,
                            hideTitle = state.hideGridTitles,
                            gridSpacing = state.gridSpacing.value,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                item {
                    renderHeader(
                        Modifier
                            .alpha(0f)
                            .clearAndSetSemantics { },
                        false,
                    )
                }
                when {
                    state.errorMessage != null -> item { LibraryErrorState(state.errorMessage, onRetry) }
                    state.loading -> item { LibraryLoadingState(state) }
                    state.apps.isEmpty() -> item { LibraryEmptyState(state.appliedFilter) }
                    else -> items(state.apps, key = { it.id }) { app ->
                        LibraryListItem(
                            app = app,
                            iconRatio = state.iconRatio,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                            onFavorite = onFavorite,
                            favoriteEnabled = state.databaseControlsReady,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset {
                    IntOffset(0, headerOffsetPx.value.roundToInt())
                }
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { headerHeightPx.value = it.height },
        ) {
            renderHeader(Modifier, true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryAppsHeader(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    state: LibraryUiState,
    sortVisible: Boolean,
    onSortVisibilityChanged: (Boolean) -> Unit,
    onQuickView: (LibraryQuickView) -> Unit,
    onSort: (Int) -> Unit,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    showQuickViews: Boolean = true,
    interactive: Boolean = true,
) {
    val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()
    val selectedSort = state.sortVariant and Int.MAX_VALUE
    val ascending = state.sortVariant >= 0
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val quickControlsPagerBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset(x = available.x, y = 0f)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = Velocity(x = available.x, y = 0f)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = interactive) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.library_back),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title ?: stringResource(R.string.app_name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                enabled = interactive,
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                leadingIcon = {
                    if (query.isEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = stringResource(R.string.search),
                        )
                    } else {
                        IconButton(
                            onClick = {
                                onQueryChange("")
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                            enabled = interactive,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.library_search_clear),
                            )
                        }
                    }
                },
            )
            Box {
                IconButton(
                    onClick = { onSortVisibilityChanged(true) },
                    enabled = interactive,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = stringResource(R.string.library_sort),
                    )
                }
                LibrarySortMenu(
                    expanded = sortVisible && interactive,
                    entries = sortEntries,
                    selectedSort = selectedSort,
                    ascending = ascending,
                    onDismissRequest = { onSortVisibilityChanged(false) },
                    onSelected = { index ->
                        onSortVisibilityChanged(false)
                        onSort(index)
                    },
                )
            }
        }
        if (showQuickViews) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .nestedScroll(quickControlsPagerBoundary)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryQuickFilter(
                    label = R.string.library_filter_all,
                    icon = R.drawable.ic_apps,
                    selected = state.quickView == LibraryQuickView.All,
                    enabled = interactive,
                    onClick = { onQuickView(LibraryQuickView.All) },
                )
                LibraryQuickFilter(
                    label = R.string.library_filter_favorites,
                    icon = R.drawable.ic_star,
                    selected = state.quickView == LibraryQuickView.Favorites,
                    enabled = interactive && state.databaseControlsReady,
                    onClick = { onQuickView(LibraryQuickView.Favorites) },
                )
                LibraryQuickFilter(
                    label = R.string.library_filter_recently_added,
                    icon = R.drawable.ic_add,
                    selected = state.quickView == LibraryQuickView.RecentlyAdded,
                    enabled = interactive && state.databaseControlsReady,
                    onClick = { onQuickView(LibraryQuickView.RecentlyAdded) },
                )
                LibraryQuickFilter(
                    label = R.string.library_filter_recently_opened,
                    icon = R.drawable.ic_history,
                    selected = state.quickView == LibraryQuickView.RecentlyPlayed,
                    enabled = interactive && state.databaseControlsReady,
                    onClick = { onQuickView(LibraryQuickView.RecentlyPlayed) },
                )
            }
        }
    }
}

@Composable
private fun LibraryQuickFilter(
    label: Int,
    icon: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun LibraryLoadingState(state: LibraryUiState) {
    val loadingDescription = stringResource(R.string.loading_apps)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = loadingDescription
            },
        )
        Text(
            text = if (state.loadingTotal > 0) {
                stringResource(
                    R.string.library_indexing_progress,
                    state.loadingCompleted,
                    state.loadingTotal,
                )
            } else {
                loadingDescription
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.loadingStorageKey.isNotBlank()) {
            Text(
                text = stringResource(R.string.library_indexing_current, state.loadingStorageKey),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_load_error_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.library_retry))
        }
    }
}

@Composable
private fun LibraryEmptyState(appliedFilter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = if (appliedFilter.isEmpty()) {
                stringResource(R.string.no_data_for_display)
            } else {
                stringResource(R.string.msg_no_matches, appliedFilter)
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LibraryCollectionsDestination(scaffoldPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.library_destination_collections),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_collections),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.library_collections_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.library_collections_empty_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryOptionsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onLayoutChange: (LibraryLayout) -> Unit,
    onIconRatioChange: (LibraryIconRatio) -> Unit,
    onHideGridTitlesChange: (Boolean) -> Unit,
    onGridSpacingChange: (LibraryGridSpacing) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onCrashReports: () -> Unit,
    onSaveLog: () -> Unit,
    onExit: () -> Unit,
) {
    val hideGridTitlesLabel = stringResource(R.string.library_hide_grid_titles)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentPadding = scaffoldPadding,
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = 840.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.library_destination_options),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_library_title,
                    ) {
                        LibraryOptionGroup(label = R.string.pref_apps_view) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = state.layout == LibraryLayout.List,
                                    onClick = { onLayoutChange(LibraryLayout.List) },
                                    label = { Text(stringResource(R.string.library_view_list)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_library_list),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                FilterChip(
                                    selected = state.layout == LibraryLayout.Grid,
                                    onClick = { onLayoutChange(LibraryLayout.Grid) },
                                    label = { Text(stringResource(R.string.library_view_grid)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_library_grid),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                        }
                        LibraryOptionGroup(
                            label = R.string.library_icon_ratio_title,
                            summary = R.string.library_icon_ratio_summary,
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = state.iconRatio == LibraryIconRatio.Square,
                                    onClick = { onIconRatioChange(LibraryIconRatio.Square) },
                                    label = { Text(stringResource(R.string.library_icon_ratio_square)) },
                                )
                                FilterChip(
                                    selected = state.iconRatio == LibraryIconRatio.Portrait,
                                    onClick = { onIconRatioChange(LibraryIconRatio.Portrait) },
                                    label = { Text(stringResource(R.string.library_icon_ratio_portrait)) },
                                )
                            }
                        }
                        if (state.layout == LibraryLayout.Grid) {
                            LibraryOptionGroup(
                                label = R.string.library_grid_spacing_title,
                                summary = R.string.library_grid_spacing_summary,
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    LibraryGridSpacing.entries.forEach { spacing ->
                                        val label = when (spacing) {
                                            LibraryGridSpacing.Compact -> R.string.library_grid_spacing_compact
                                            LibraryGridSpacing.Standard -> R.string.library_grid_spacing_standard
                                            LibraryGridSpacing.Spacious -> R.string.library_grid_spacing_spacious
                                        }
                                        FilterChip(
                                            selected = state.gridSpacing == spacing,
                                            onClick = { onGridSpacingChange(spacing) },
                                            label = { Text(stringResource(label)) },
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hideGridTitlesLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = stringResource(R.string.library_hide_grid_titles_summary),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = state.hideGridTitles,
                                    onCheckedChange = onHideGridTitlesChange,
                                    modifier = Modifier.semantics {
                                        contentDescription = hideGridTitlesLabel
                                    },
                                )
                            }
                        }
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_application_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.action_settings,
                            summary = R.string.library_action_settings_summary,
                            icon = R.drawable.ic_settings,
                            action = onSettings,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_diagnostics_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.crash_reports,
                            summary = R.string.library_action_crash_reports_summary,
                            icon = R.drawable.ic_bug_report,
                            action = onCrashReports,
                        )
                        LibraryActionRow(
                            label = R.string.save_log,
                            summary = R.string.library_action_save_log_summary,
                            icon = R.drawable.ic_save,
                            action = onSaveLog,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_information_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.about,
                            summary = R.string.library_action_about_summary,
                            icon = R.drawable.ic_info,
                            action = onAbout,
                        )
                        LibraryActionRow(
                            label = R.string.help,
                            summary = R.string.library_action_help_summary,
                            icon = R.drawable.ic_help,
                            action = onHelp,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_session_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.exit,
                            summary = R.string.library_action_exit_summary,
                            icon = R.drawable.ic_logout,
                            destructive = true,
                            action = onExit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryOptionsSection(
    modifier: Modifier = Modifier,
    title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(title),
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun LibraryOptionGroup(
    label: Int,
    summary: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        summary?.let {
            Text(
                text = stringResource(it),
                modifier = Modifier.padding(top = 1.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    iconRatio: LibraryIconRatio,
    hideTitle: Boolean,
    gridSpacing: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gridSpacing / 2, vertical = gridSpacing / 2)
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibraryIconSlot(
            app = app,
            modifier = Modifier.fillMaxWidth(),
            contentSize = null,
            iconRatio = iconRatio,
        )
        if (!hideTitle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = app.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryListItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onFavorite: (Int, Boolean) -> Unit,
    favoriteEnabled: Boolean,
    iconRatio: LibraryIconRatio,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpenApp(app.id) },
                    onLongClick = { onOpenActions(app) },
                )
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.width(52.dp),
                contentSize = 40.dp,
                iconRatio = iconRatio,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.library_vendor_version,
                        app.author,
                        app.version,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LibraryDescription(app.description, app.id)
            }
            Spacer(Modifier.width(6.dp))
            if (favoriteEnabled) {
                LibraryFavoriteButton(app, onFavorite)
            } else {
                LibraryFavoritePlaceholder(app.id)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 80.dp, end = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
internal fun LibraryDescription(descriptionValue: String, appId: Int) {
    val description = descriptionValue.trim()
    if (description.isEmpty()) return

    var expanded by rememberSaveable(appId, description) { mutableStateOf(false) }
    var overflows by remember(appId, description) { mutableStateOf(false) }
    val expandDescriptionLabel = stringResource(R.string.library_expand_description)
    val collapseDescriptionLabel = stringResource(R.string.library_collapse_description)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = description,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) {
                        overflows = result.didOverflowHeight || result.didOverflowWidth ||
                            result.hasVisualOverflow ||
                            (result.lineCount > 0 && result.isLineEllipsized(result.lineCount - 1))
                    }
                },
            )
            if (!expanded && overflows) {
                Text(
                    text = stringResource(R.string.library_description_more),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(role = Role.Button) { expanded = true }
                        .semantics { contentDescription = expandDescriptionLabel }
                        .padding(start = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.library_description_less),
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(role = Role.Button) { expanded = false }
                    .semantics { contentDescription = collapseDescriptionLabel }
                    .padding(top = 2.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}


@Composable
private fun LibraryFavoriteButton(
    app: LibraryAppUiItem,
    onFavorite: (Int, Boolean) -> Unit,
) {
    IconButton(
        onClick = { onFavorite(app.id, !app.favorite) },
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = app.favorite,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.55f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.7f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_star_filled),
                    contentDescription = stringResource(R.string.library_filter_favorites),
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(
                visible = !app.favorite,
                enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.7f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.55f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = stringResource(R.string.library_filter_favorites),
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryFavoritePlaceholder(@Suppress("UNUSED_PARAMETER") appId: Int) {
    IconButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_star),
            contentDescription = stringResource(R.string.library_favorite_coming_soon),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class LibraryDominantColorSample(
    val color: Int,
    val populationRatio: Float,
)

private data class LibraryIconAnalysis(
    val contentBounds: Rect,
    val framedCropBounds: Rect?,
    val edgeFillColor: Int?,
    val backingColor: Int?,
    val dominantColor: Int?,
    val presentation: LibraryIconPresentationDecision,
    val foregroundLuminance: Float,
    val pixelArt: Boolean,
)

private data class LibraryNormalizedIcon(
    val bitmap: ImageBitmap,
    val filterQuality: FilterQuality,
    val representativeColor: Color?,
    val presentationMode: LibraryIconPresentationMode,
    val visualScale: Float,
    val foregroundLuminance: Float,
    val tileColor: Color? = null,
)

private const val LIBRARY_ICON_CACHE_BYTES = 4 * 1024 * 1024
private const val LIBRARY_ICON_PRESENTATION_VERSION = 6
private val LibraryIconCache = object : LruCache<String, LibraryNormalizedIcon>(LIBRARY_ICON_CACHE_BYTES) {
    override fun sizeOf(key: String, value: LibraryNormalizedIcon): Int {
        return (value.bitmap.width.toLong() * value.bitmap.height.toLong() * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }
}

private fun normalizeLibraryIcon(
    fileSource: Bitmap,
    normalizeSquareIcon: Boolean,
): LibraryNormalizedIcon? {
    if (!normalizeSquareIcon) {
        return LibraryNormalizedIcon(
            bitmap = fileSource.asImageBitmap(),
            filterQuality = fileSource.libraryFilterQuality(),
            representativeColor = null,
            presentationMode = LibraryIconPresentationMode.SafeFit,
            visualScale = 1f,
            foregroundLuminance = 0.5f,
        )
    }

    val analysis = fileSource.analyzeLibraryIcon()
        ?: return LibraryNormalizedIcon(
            bitmap = fileSource.asImageBitmap(),
            filterQuality = fileSource.libraryFilterQuality(),
            representativeColor = null,
            presentationMode = LibraryIconPresentationMode.SafeFit,
            visualScale = 1f,
            foregroundLuminance = 0.5f,
        )
    val cropBounds = when (analysis.presentation.mode) {
        LibraryIconPresentationMode.Subject -> analysis.framedCropBounds ?: analysis.contentBounds
        LibraryIconPresentationMode.Backed -> null
        else -> null
    }
    val normalized = if (cropBounds != null && !cropBounds.isFullBitmap(fileSource)) {
        fileSource.cropLibraryBounds(cropBounds)
    } else {
        fileSource
    }
    val representativeColor = normalized.findRepresentativeColor() ?: normalized.findAverageVisibleColor()
    val edgeFillColor = analysis.edgeFillColor?.toComposeLibraryColor()
    val backingTileColor = analysis.backingColor?.toComposeLibraryColor()
    val fillSourceColor = analysis.dominantColor?.toComposeLibraryColor() ?: representativeColor
    val tileColor = when (analysis.presentation.mode) {
        LibraryIconPresentationMode.Cover -> null
        LibraryIconPresentationMode.Backed ->
            edgeFillColor ?: backingTileColor ?: fillSourceColor?.let {
                syntheticLibraryTileColor(it, analysis.foregroundLuminance)
            }
        LibraryIconPresentationMode.Subject,
        LibraryIconPresentationMode.SafeFit ->
            edgeFillColor ?: fillSourceColor?.let {
                syntheticLibraryTileColor(it, analysis.foregroundLuminance)
            }
        LibraryIconPresentationMode.Fallback -> null
    }
    return LibraryNormalizedIcon(
        bitmap = normalized.asImageBitmap(),
        filterQuality = if (analysis.pixelArt) FilterQuality.None else FilterQuality.Medium,
        representativeColor = representativeColor,
        presentationMode = analysis.presentation.mode,
        visualScale = analysis.presentation.visualScale,
        foregroundLuminance = analysis.foregroundLuminance,
        tileColor = tileColor,
    )
}

private fun Bitmap.cropLibraryBounds(bounds: Rect): Bitmap {
    val left = bounds.left.coerceIn(0, width)
    val top = bounds.top.coerceIn(0, height)
    val right = bounds.right.coerceIn(left, width)
    val bottom = bounds.bottom.coerceIn(top, height)
    val cropWidth = right - left
    val cropHeight = bottom - top
    if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == width && cropHeight == height)) {
        return this
    }
    return try {
        Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    } catch (_: OutOfMemoryError) {
        this
    } catch (_: RuntimeException) {
        this
    }
}

private fun decodeLibraryBitmap(path: String, targetSizePx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeTarget = (targetSizePx.coerceAtLeast(48) * 2).coerceAtMost(1024)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > decodeTarget ||
            bounds.outHeight / sampleSize > decodeTarget
        ) {
            if (sampleSize >= 1 shl 20) break
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, options)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun Bitmap.analyzeLibraryIcon(): LibraryIconAnalysis? {
    if (width <= 0 || height <= 0) return null

    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var visiblePixels = 0L
    var semiTransparentPixels = 0L
    var luminanceSum = 0.0
    var visibleWeight = 0.0
    val quantizedColors = HashSet<Int>(LIBRARY_COLOR_SAMPLE_MAX_BINS + 1)

    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue

            visiblePixels++
            if (alpha < LIBRARY_PIXEL_ART_OPAQUE_ALPHA) semiTransparentPixels++
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y

            val alphaWeight = alpha / 255.0
            luminanceSum += pixelLibraryLuminance(pixel) * alphaWeight
            visibleWeight += alphaWeight
            if (quantizedColors.size <= LIBRARY_COLOR_SAMPLE_MAX_BINS) {
                quantizedColors += quantizeLibraryColor(pixel)
            }
        }
    }
    if (right < left || bottom < top || visiblePixels == 0L || visibleWeight <= 0.0) return null

    val contentBounds = Rect(left, top, right + 1, bottom + 1)
    val canvasArea = width.toLong() * height.toLong()
    val boundsArea = contentBounds.width().toLong() * contentBounds.height().toLong()
    val boundsCoverage = if (canvasArea == 0L) 1f else boundsArea.toFloat() / canvasArea.toFloat()
    val occupancy = if (boundsArea == 0L) 1f else visiblePixels.toFloat() / boundsArea.toFloat()
    val transparentRatio = if (canvasArea == 0L) {
        0f
    } else {
        1f - visiblePixels.toFloat() / canvasArea.toFloat()
    }
    val maxContentDimension = maxOf(contentBounds.width(), contentBounds.height()).coerceAtLeast(1)
    val aspectFill = minOf(contentBounds.width(), contentBounds.height()).toFloat() /
        maxContentDimension.toFloat()
    val cornerBackgroundColor = findUniformCornerBackgroundColor()
    val framedCropBounds = cornerBackgroundColor?.let(::findFramedArtworkCropBounds)
    val dominantColor = findDominantVisibleColor()
    val dominantBackingColor = dominantColor?.takeIf { sample ->
        sample.populationRatio >= LIBRARY_BACKING_MIN_DOMINANT_RATIO &&
            boundsCoverage >= LIBRARY_BACKING_MIN_BOUNDS_COVERAGE &&
            occupancy >= LIBRARY_BACKING_MIN_OCCUPANCY &&
            transparentRatio <= LIBRARY_BACKING_MAX_TRANSPARENT_RATIO
    }?.color
    val backingColor = when {
        cornerBackgroundColor != null && !cornerBackgroundColor.isLibraryNeutralMatte() ->
            cornerBackgroundColor
        dominantBackingColor != null -> dominantBackingColor
        else -> null
    }
    val presentation = decideLibraryIconPresentation(
        LibraryIconPresentationInput(
            transparentRatio = transparentRatio,
            boundsCoverage = boundsCoverage,
            occupancy = occupancy,
            aspectFill = aspectFill,
            hasFramedCrop = framedCropBounds != null,
            hasBackingColor = backingColor != null,
            highColorDiversity = quantizedColors.size > LIBRARY_PIXEL_ART_MAX_COLORS,
            sourceAspectRatio = width.toFloat() / height.toFloat(),
        ),
    )
    val semiTransparentRatio = semiTransparentPixels.toFloat() / visiblePixels.toFloat()
    val pixelArt = maxOf(width, height) <= LIBRARY_PIXEL_ART_MAX_DIMENSION &&
        quantizedColors.size <= LIBRARY_PIXEL_ART_MAX_COLORS &&
        semiTransparentRatio <= LIBRARY_PIXEL_ART_MAX_SEMI_TRANSPARENT_RATIO
    val foregroundLuminance = (luminanceSum / visibleWeight).toFloat().coerceIn(0f, 1f)

    return LibraryIconAnalysis(
        contentBounds = contentBounds,
        framedCropBounds = framedCropBounds,
        edgeFillColor = cornerBackgroundColor,
        backingColor = backingColor,
        dominantColor = dominantColor?.color,
        presentation = presentation,
        foregroundLuminance = foregroundLuminance,
        pixelArt = pixelArt,
    )
}

private fun Bitmap.findFramedArtworkCropBounds(backgroundColor: Int): Rect? {
    if (width < 6 || height < 6) return null
    val distanceLimit = LIBRARY_BACKGROUND_FOREGROUND_DISTANCE *
        LIBRARY_BACKGROUND_FOREGROUND_DISTANCE * 3
    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var foregroundPixels = 0L

    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue
            if (libraryColorDistanceSquared(pixel, backgroundColor) <= distanceLimit) continue

            foregroundPixels++
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top || foregroundPixels == 0L) return null

    val canvasArea = width.toLong() * height.toLong()
    val foregroundRatio = foregroundPixels.toFloat() / canvasArea.toFloat()
    if (
        foregroundRatio < LIBRARY_FRAMED_MIN_FOREGROUND_RATIO ||
        foregroundRatio > LIBRARY_FRAMED_MAX_FOREGROUND_RATIO
    ) {
        return null
    }

    val opticalBounds = Rect(left, top, right + 1, bottom + 1)
    val opticalCoverage = opticalBounds.width().toLong() * opticalBounds.height().toLong() /
        canvasArea.toDouble()
    if (opticalCoverage >= LIBRARY_FRAMED_MAX_OPTICAL_COVERAGE) return null

    val cropBounds = opticalBounds.toLibraryFramedCrop(width, height)
    val cropCoverage = cropBounds.width().toLong() * cropBounds.height().toLong() /
        canvasArea.toDouble()
    return cropBounds.takeIf { cropCoverage <= LIBRARY_FRAMED_MAX_CROP_COVERAGE }
}

private fun Bitmap.findUniformCornerBackgroundColor(): Int? {
    val minDimension = minOf(width, height)
    if (minDimension < 6) return null

    val patchSize = maxOf(2, minDimension / 8).coerceAtMost(minDimension)
    val step = maxOf(1, patchSize / 4)
    val cornerRects = arrayOf(
        Rect(0, 0, patchSize, patchSize),
        Rect(width - patchSize, 0, width, patchSize),
        Rect(0, height - patchSize, patchSize, height),
        Rect(width - patchSize, height - patchSize, width, height),
    )
    val cornerSamples = Array(cornerRects.size) { ArrayList<Int>() }
    val bins = IntArray(4096)
    var totalSamples = 0

    cornerRects.forEachIndexed { index, rect ->
        for (y in rect.top until rect.bottom step step) {
            for (x in rect.left until rect.right step step) {
                val pixel = getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xff
                if (alpha < LIBRARY_BACKGROUND_SAMPLE_ALPHA) continue
                cornerSamples[index].add(pixel)
                bins[quantizeLibraryColor(pixel)]++
                totalSamples++
            }
        }
    }
    if (totalSamples < LIBRARY_BACKGROUND_MIN_CORNER_SAMPLES) return null

    var dominantBin = 0
    var dominantCount = 0
    for (index in bins.indices) {
        if (bins[index] > dominantCount) {
            dominantBin = index
            dominantCount = bins[index]
        }
    }
    if (dominantCount.toFloat() / totalSamples < LIBRARY_BACKGROUND_MIN_DOMINANT_BIN_RATIO) {
        return null
    }

    val candidate = quantizedLibraryColorCenter(dominantBin)
    val distanceLimit = LIBRARY_BACKGROUND_EDGE_DISTANCE *
        LIBRARY_BACKGROUND_EDGE_DISTANCE * 3
    var closeSamples = 0
    var red = 0L
    var green = 0L
    var blue = 0L

    for (samples in cornerSamples) {
        if (samples.size < 2) return null
        var cornerClose = 0
        for (pixel in samples) {
            if (libraryColorDistanceSquared(pixel, candidate) > distanceLimit) continue
            cornerClose++
            closeSamples++
            red += AndroidColor.red(pixel)
            green += AndroidColor.green(pixel)
            blue += AndroidColor.blue(pixel)
        }
        if (cornerClose.toFloat() / samples.size < LIBRARY_BACKGROUND_MIN_PER_CORNER_RATIO) {
            return null
        }
    }
    if (closeSamples.toFloat() / totalSamples < LIBRARY_BACKGROUND_MIN_TOTAL_CORNER_RATIO) {
        return null
    }

    return AndroidColor.rgb(
        (red / closeSamples).toInt().coerceIn(0, 255),
        (green / closeSamples).toInt().coerceIn(0, 255),
        (blue / closeSamples).toInt().coerceIn(0, 255),
    )
}

private fun Bitmap.findDominantVisibleColor(): LibraryDominantColorSample? {
    if (width <= 0 || height <= 0) return null
    val step = maxOf(1, maxOf(width, height) / 64)
    val bins = IntArray(4096)
    var visibleSamples = 0
    for (y in 0 until height step step) {
        for (x in 0 until width step step) {
            val pixel = getPixel(x, y)
            if (((pixel ushr 24) and 0xff) < MIN_VISIBLE_ALPHA) continue
            bins[quantizeLibraryColor(pixel)]++
            visibleSamples++
        }
    }
    if (visibleSamples == 0) return null

    var dominantBin = -1
    var dominantCount = 0
    for (index in bins.indices) {
        if (bins[index] > dominantCount) {
            dominantCount = bins[index]
            dominantBin = index
        }
    }
    if (dominantBin < 0 || dominantCount == 0) return null

    var red = 0L
    var green = 0L
    var blue = 0L
    var matched = 0
    for (y in 0 until height step step) {
        for (x in 0 until width step step) {
            val pixel = getPixel(x, y)
            if (((pixel ushr 24) and 0xff) < MIN_VISIBLE_ALPHA) continue
            if (quantizeLibraryColor(pixel) != dominantBin) continue
            red += AndroidColor.red(pixel)
            green += AndroidColor.green(pixel)
            blue += AndroidColor.blue(pixel)
            matched++
        }
    }
    if (matched == 0) return null
    return LibraryDominantColorSample(
        color = AndroidColor.rgb(
            (red / matched).toInt().coerceIn(0, 255),
            (green / matched).toInt().coerceIn(0, 255),
            (blue / matched).toInt().coerceIn(0, 255),
        ),
        populationRatio = dominantCount.toFloat() / visibleSamples.toFloat(),
    )
}

private fun Int.isLibraryNeutralMatte(): Boolean {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(this, hsv)
    return hsv[1] <= LIBRARY_MATTE_MAX_SATURATION &&
        (hsv[2] >= LIBRARY_MATTE_LIGHT_VALUE || hsv[2] <= LIBRARY_MATTE_DARK_VALUE)
}

private fun Rect.toLibraryFramedCrop(canvasWidth: Int, canvasHeight: Int): Rect {
    val subjectMargin = maxOf(
        2,
        (maxOf(width(), height()) * LIBRARY_FRAMED_SUBJECT_MARGIN).roundToInt(),
    )
    var desiredWidth = (width() + subjectMargin * 2).coerceAtMost(canvasWidth)
    var desiredHeight = (height() + subjectMargin * 2).coerceAtMost(canvasHeight)
    desiredWidth = maxOf(
        desiredWidth,
        (canvasWidth / LIBRARY_FRAMED_MAX_ZOOM).roundToInt().coerceAtLeast(1),
    )
    desiredHeight = maxOf(
        desiredHeight,
        (canvasHeight / LIBRARY_FRAMED_MAX_ZOOM).roundToInt().coerceAtLeast(1),
    )

    val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
    val desiredAspect = desiredWidth.toFloat() / desiredHeight.toFloat()
    if (desiredAspect < canvasAspect) {
        desiredWidth = (desiredHeight * canvasAspect).roundToInt().coerceAtMost(canvasWidth)
    } else if (desiredAspect > canvasAspect) {
        desiredHeight = (desiredWidth / canvasAspect).roundToInt().coerceAtMost(canvasHeight)
    }

    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val cropLeft = (centerX - desiredWidth / 2f)
        .roundToInt()
        .coerceIn(0, canvasWidth - desiredWidth)
    val cropTop = (centerY - desiredHeight / 2f)
        .roundToInt()
        .coerceIn(0, canvasHeight - desiredHeight)
    return Rect(
        cropLeft,
        cropTop,
        cropLeft + desiredWidth,
        cropTop + desiredHeight,
    )
}

private fun quantizedLibraryColorCenter(bin: Int): Int {
    val red = ((bin shr 8) and 0x0f) * 17
    val green = ((bin shr 4) and 0x0f) * 17
    val blue = (bin and 0x0f) * 17
    return AndroidColor.rgb(red, green, blue)
}

private fun libraryColorDistanceSquared(first: Int, second: Int): Int {
    val red = AndroidColor.red(first) - AndroidColor.red(second)
    val green = AndroidColor.green(first) - AndroidColor.green(second)
    val blue = AndroidColor.blue(first) - AndroidColor.blue(second)
    return red * red + green * green + blue * blue
}

private fun Rect.isFullBitmap(bitmap: Bitmap): Boolean {
    return left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height
}

private fun Bitmap.libraryFilterQuality(): FilterQuality {
    return if (width <= 64 && height <= 64) FilterQuality.None else FilterQuality.Medium
}

private fun Bitmap.findRepresentativeColor(): Color? {
    if (width <= 0 || height <= 0) return null

    val step = maxOf(1, maxOf(width, height) / 32)
    val hueScores = DoubleArray(LIBRARY_HUE_BUCKETS)
    val huePopulation = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueRed = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueGreen = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueBlue = DoubleArray(LIBRARY_HUE_BUCKETS)
    var sampledWeight = 0.0
    val row = IntArray(width)
    val hsv = FloatArray(3)
    for (y in 0 until height step step) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step step) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue

            val alphaWeight = alpha / 255.0
            sampledWeight += alphaWeight
            AndroidColor.colorToHSV(pixel, hsv)
            if (hsv[1] >= LIBRARY_MIN_COLOR_SATURATION && hsv[2] >= LIBRARY_MIN_COLOR_VALUE) {
                val hueBucket = (hsv[0] / 360f * LIBRARY_HUE_BUCKETS)
                    .toInt()
                    .coerceIn(0, LIBRARY_HUE_BUCKETS - 1)
                val score = alphaWeight * (0.5 + hsv[1]) * (0.35 + hsv[2])
                hueScores[hueBucket] += score
                huePopulation[hueBucket] += alphaWeight
                hueRed[hueBucket] += AndroidColor.red(pixel) * score
                hueGreen[hueBucket] += AndroidColor.green(pixel) * score
                hueBlue[hueBucket] += AndroidColor.blue(pixel) * score
            }
        }
    }

    if (sampledWeight == 0.0) return null
    val dominantHueBucket = hueScores.indices.maxByOrNull { hueScores[it] } ?: return null
    val dominantHueScore = hueScores[dominantHueBucket]
    val dominantPopulation = huePopulation[dominantHueBucket]
    if (
        dominantHueScore <= 0.0 ||
        dominantPopulation / sampledWeight < LIBRARY_MIN_DOMINANT_COLOR_RATIO
    ) {
        return null
    }

    return Color(
        red = (hueRed[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
        green = (hueGreen[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (hueBlue[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
    )
}

private fun Bitmap.findAverageVisibleColor(): Color? {
    if (width <= 0 || height <= 0) return null
    val row = IntArray(width)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var weight = 0.0
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue
            val alphaWeight = alpha / 255.0
            red += AndroidColor.red(pixel) * alphaWeight
            green += AndroidColor.green(pixel) * alphaWeight
            blue += AndroidColor.blue(pixel) * alphaWeight
            weight += alphaWeight
        }
    }
    if (weight == 0.0) return null
    return Color(
        red = (red / weight / 255.0).toFloat().coerceIn(0f, 1f),
        green = (green / weight / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (blue / weight / 255.0).toFloat().coerceIn(0f, 1f),
    )
}

private fun Bitmap.averageVisibleLuminance(): Float {
    if (width <= 0 || height <= 0) return 0.5f
    val row = IntArray(width)
    var luminance = 0.0
    var weight = 0.0
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue
            val alphaWeight = alpha / 255.0
            luminance += pixelLibraryLuminance(pixel) * alphaWeight
            weight += alphaWeight
        }
    }
    return if (weight == 0.0) 0.5f else (luminance / weight).toFloat().coerceIn(0f, 1f)
}

private fun pixelLibraryLuminance(pixel: Int): Double {
    val red = AndroidColor.red(pixel) / 255.0
    val green = AndroidColor.green(pixel) / 255.0
    val blue = AndroidColor.blue(pixel) / 255.0
    return red * 0.2126 + green * 0.7152 + blue * 0.0722
}

private fun quantizeLibraryColor(pixel: Int): Int {
    return ((AndroidColor.red(pixel) shr 4) shl 8) or
        ((AndroidColor.green(pixel) shr 4) shl 4) or
        (AndroidColor.blue(pixel) shr 4)
}

private fun Int.toComposeLibraryColor(): Color {
    return Color(
        red = AndroidColor.red(this) / 255f,
        green = AndroidColor.green(this) / 255f,
        blue = AndroidColor.blue(this) / 255f,
        alpha = 1f,
    )
}

private const val MIN_VISIBLE_ALPHA = 16
private const val LIBRARY_HUE_BUCKETS = 12
private const val LIBRARY_MIN_COLOR_SATURATION = 0.28f
private const val LIBRARY_MIN_COLOR_VALUE = 0.16f
private const val LIBRARY_MIN_DOMINANT_COLOR_RATIO = 0.10
private const val LIBRARY_LIGHT_SLOT_TINT_AMOUNT = 0.12f
private const val LIBRARY_DARK_SLOT_TINT_AMOUNT = 0.14f
private const val LIBRARY_NEUTRAL_SLOT_TINT_AMOUNT = 0.06f
private const val LIBRARY_FALLBACK_VISUAL_SCALE = 0.86f
private const val LIBRARY_FALLBACK_TINT_SCALE = 0.60f
private const val LIBRARY_BACKGROUND_SAMPLE_ALPHA = 240
private const val LIBRARY_BACKGROUND_MIN_CORNER_SAMPLES = 8
private const val LIBRARY_BACKGROUND_MIN_DOMINANT_BIN_RATIO = 0.16f
private const val LIBRARY_BACKGROUND_MIN_PER_CORNER_RATIO = 0.55f
private const val LIBRARY_BACKGROUND_MIN_TOTAL_CORNER_RATIO = 0.70f
private const val LIBRARY_BACKGROUND_EDGE_DISTANCE = 42
private const val LIBRARY_BACKGROUND_FOREGROUND_DISTANCE = 42
private const val LIBRARY_FRAMED_MIN_FOREGROUND_RATIO = 0.035f
private const val LIBRARY_FRAMED_MAX_FOREGROUND_RATIO = 0.72f
private const val LIBRARY_FRAMED_MAX_OPTICAL_COVERAGE = 0.82
private const val LIBRARY_FRAMED_SUBJECT_MARGIN = 0.10f
private const val LIBRARY_FRAMED_MAX_ZOOM = 1.30f
private const val LIBRARY_FRAMED_MAX_CROP_COVERAGE = 0.94
private const val LIBRARY_PIXEL_ART_MAX_DIMENSION = 160
private const val LIBRARY_PIXEL_ART_MAX_COLORS = 48
private const val LIBRARY_COLOR_SAMPLE_MAX_BINS = 64
private const val LIBRARY_PIXEL_ART_OPAQUE_ALPHA = 240
private const val LIBRARY_PIXEL_ART_MAX_SEMI_TRANSPARENT_RATIO = 0.06f
private const val LIBRARY_DARK_FOREGROUND_LUMINANCE = 0.20f
private const val LIBRARY_LIGHT_FOREGROUND_LUMINANCE = 0.82f
private const val LIBRARY_DARK_CONTRAST_LIFT = 0.18f
private const val LIBRARY_LIGHT_CONTRAST_DROP = 0.08f
private const val LIBRARY_BACKING_MIN_DOMINANT_RATIO = 0.24f
private const val LIBRARY_BACKING_MIN_BOUNDS_COVERAGE = 0.70f
private const val LIBRARY_BACKING_MIN_OCCUPANCY = 0.72f
private const val LIBRARY_BACKING_MAX_TRANSPARENT_RATIO = 0.30f
private const val LIBRARY_MATTE_MAX_SATURATION = 0.08f
private const val LIBRARY_MATTE_LIGHT_VALUE = 0.92f
private const val LIBRARY_MATTE_DARK_VALUE = 0.10f
private const val LIBRARY_SYNTHETIC_FILL_MIN_SATURATION = 0.12f
private const val LIBRARY_SYNTHETIC_FILL_MAX_SATURATION = 0.30f
private const val LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE = 0.88f
private const val LIBRARY_SYNTHETIC_FILL_DARK_VALUE = 0.30f
private const val LIBRARY_SYNTHETIC_FILL_BRIGHT_FOREGROUND = 0.68f

private fun syntheticLibraryTileColor(
    source: Color,
    foregroundLuminance: Float,
): Color {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (source.red * 255f).toInt().coerceIn(0, 255),
        (source.green * 255f).toInt().coerceIn(0, 255),
        (source.blue * 255f).toInt().coerceIn(0, 255),
        hsv,
    )
    val darkTile = foregroundLuminance >= LIBRARY_SYNTHETIC_FILL_BRIGHT_FOREGROUND
    if (hsv[1] < LIBRARY_MATTE_MAX_SATURATION) {
        val value = if (darkTile) {
            LIBRARY_SYNTHETIC_FILL_DARK_VALUE
        } else {
            LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE
        }
        return Color(value, value, value)
    }
    return Color.hsv(
        hue = hsv[0],
        saturation = hsv[1].coerceIn(
            LIBRARY_SYNTHETIC_FILL_MIN_SATURATION,
            LIBRARY_SYNTHETIC_FILL_MAX_SATURATION,
        ),
        value = if (darkTile) {
            LIBRARY_SYNTHETIC_FILL_DARK_VALUE
        } else {
            LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE
        },
    )
}

private fun adaptiveLibraryFallbackSlotColor(
    base: Color,
    accent: Color,
    foregroundLuminance: Float,
): Color {
    val brightness = base.red * 0.2126f + base.green * 0.7152f + base.blue * 0.0722f
    val isLightSurface = brightness >= 0.5f
    val contrastBase = when {
        isLightSurface && foregroundLuminance >= LIBRARY_LIGHT_FOREGROUND_LUMINANCE ->
            blendLibrarySlotColor(base, Color.Black, LIBRARY_LIGHT_CONTRAST_DROP)
        !isLightSurface && foregroundLuminance <= LIBRARY_DARK_FOREGROUND_LUMINANCE ->
            blendLibrarySlotColor(base, Color.White, LIBRARY_DARK_CONTRAST_LIFT)
        else -> base
    }
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (accent.red * 255f).toInt(),
        (accent.green * 255f).toInt(),
        (accent.blue * 255f).toInt(),
        hsv,
    )
    if (hsv[1] < LIBRARY_MIN_COLOR_SATURATION) {
        return blendLibrarySlotColor(
            contrastBase,
            accent,
            amount = LIBRARY_NEUTRAL_SLOT_TINT_AMOUNT * LIBRARY_FALLBACK_TINT_SCALE,
        )
    }

    val softenedAccent = Color.hsv(
        hue = hsv[0],
        saturation = hsv[1].coerceIn(0.28f, 0.58f),
        value = if (isLightSurface) 0.95f else 0.32f,
    )
    return blendLibrarySlotColor(
        contrastBase,
        softenedAccent,
        amount = if (isLightSurface) {
            LIBRARY_LIGHT_SLOT_TINT_AMOUNT * LIBRARY_FALLBACK_TINT_SCALE
        } else {
            LIBRARY_DARK_SLOT_TINT_AMOUNT * LIBRARY_FALLBACK_TINT_SCALE
        },
    )
}

private fun blendLibrarySlotColor(base: Color, accent: Color, amount: Float): Color {
    val ratio = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red + (accent.red - base.red) * ratio,
        green = base.green + (accent.green - base.green) * ratio,
        blue = base.blue + (accent.blue - base.blue) * ratio,
        alpha = base.alpha,
    )
}

@Composable
private fun rememberLibraryIcon(
    app: LibraryAppUiItem,
    contentSize: Dp,
    iconRatio: LibraryIconRatio,
): LibraryNormalizedIcon? {
    val contentSizePx = with(LocalDensity.current) { contentSize.roundToPx() }.coerceAtLeast(1)
    val cacheKey = remember(app.iconPath, app.iconRevision, contentSizePx, iconRatio) {
        libraryIconCacheKey(app.iconPath, app.iconRevision, contentSizePx, iconRatio)
    }
    val cached = remember(cacheKey) { LibraryIconCache.get(cacheKey) }
    return produceState<LibraryNormalizedIcon?>(initialValue = cached, cacheKey) {
        if (value != null) return@produceState
        val path = app.iconPath?.takeIf(String::isNotBlank) ?: return@produceState
        val bitmap = withContext(Dispatchers.IO) {
            decodeLibraryBitmap(path, contentSizePx)
        } ?: return@produceState
        val normalized = withContext(Dispatchers.Default) {
            normalizeLibraryIcon(
                fileSource = bitmap,
                normalizeSquareIcon = iconRatio == LibraryIconRatio.Square,
            )
        } ?: return@produceState
        LibraryIconCache.put(cacheKey, normalized)
        value = normalized
    }.value
}

private fun libraryIconCacheKey(
    iconPath: String?,
    iconRevision: Long,
    targetSizePx: Int,
    iconRatio: LibraryIconRatio,
): String {
    return "real:$LIBRARY_ICON_PRESENTATION_VERSION:${iconPath.orEmpty()}:$iconRevision:$targetSizePx:${iconRatio.name}"
}

@Composable
internal fun LibraryIconSlot(
    app: LibraryAppUiItem,
    modifier: Modifier,
    contentSize: Dp?,
    iconRatio: LibraryIconRatio,
) {
    BoxWithConstraints(
        modifier = modifier.aspectRatio(iconRatio.widthToHeight),
    ) {
        val artworkSize = contentSize ?: minOf(
            maxWidth * LIBRARY_GRID_ARTWORK_FRACTION,
            LibraryGridMaxArtworkSize,
        )
        val icon = if (app.iconPath.isNullOrBlank()) {
            null
        } else {
            rememberLibraryIcon(app, artworkSize, iconRatio)
        }
        val isFallback = icon == null ||
            icon.presentationMode == LibraryIconPresentationMode.Fallback
        val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
        val containerColor = when {
            isFallback -> colorResource(R.color.library_default_icon_background)
            iconRatio != LibraryIconRatio.Square -> baseContainerColor
            icon.presentationMode == LibraryIconPresentationMode.Cover -> baseContainerColor
            else -> icon.tileColor ?: baseContainerColor
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isFallback) {
                    LibraryFallbackIconArtwork(iconRatio)
                } else {
                    LibraryIconArtwork(
                        icon = icon,
                        contentSize = artworkSize,
                        iconRatio = iconRatio,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFallbackIconArtwork(iconRatio: LibraryIconRatio) {
    Icon(
        painter = painterResource(R.drawable.ic_default_midlet),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(
            if (iconRatio == LibraryIconRatio.Square) {
                LIBRARY_FALLBACK_VECTOR_SCALE
            } else {
                LIBRARY_FALLBACK_VECTOR_PORTRAIT_SCALE
            },
        ),
        tint = colorResource(R.color.library_default_icon),
    )
}

private const val LIBRARY_FALLBACK_VECTOR_SCALE = 0.70f
private const val LIBRARY_FALLBACK_VECTOR_PORTRAIT_SCALE = 0.62f

@Composable
private fun LibraryIconArtwork(
    icon: LibraryNormalizedIcon?,
    contentSize: Dp,
    iconRatio: LibraryIconRatio,
) {
    if (icon == null) return

    val artworkModifier = when {
        iconRatio != LibraryIconRatio.Square -> Modifier.fillMaxSize()
        icon.presentationMode == LibraryIconPresentationMode.Subject ||
            icon.presentationMode == LibraryIconPresentationMode.Backed ->
            Modifier.fillMaxSize(icon.visualScale)
        icon.presentationMode == LibraryIconPresentationMode.Fallback ->
            Modifier
                .size(contentSize * icon.visualScale)
                .clip(MaterialTheme.shapes.small)
        else -> Modifier.fillMaxSize()
    }
    val contentScale = if (
        iconRatio == LibraryIconRatio.Square &&
        icon.presentationMode == LibraryIconPresentationMode.Cover
    ) {
        ContentScale.Crop
    } else {
        ContentScale.Fit
    }
    Image(
        bitmap = icon.bitmap,
        contentDescription = null,
        modifier = artworkModifier,
        contentScale = contentScale,
        filterQuality = icon.filterQuality,
    )
}

@Composable
private fun LibraryActionRow(
    label: Int,
    action: () -> Unit,
    icon: Int? = null,
    summary: Int? = null,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
            if (summary != null) {
                Text(
                    text = stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
internal fun LibrarySortMenu(
    expanded: Boolean,
    entries: List<String>,
    selectedSort: Int,
    ascending: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        entries.forEachIndexed { index, entry ->
            val selected = index == selectedSort
            DropdownMenuItem(
                text = {
                    Column {
                        Text(entry)
                        if (selected) {
                            Text(
                                text = stringResource(
                                    if (ascending) {
                                        R.string.pref_app_sort_ascending
                                    } else {
                                        R.string.pref_app_sort_descending
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                        )
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                },
                trailingIcon = if (selected) {
                    {
                        Icon(
                            painter = painterResource(
                                if (ascending) {
                                    R.drawable.ic_arrow_downward
                                } else {
                                    R.drawable.ic_arrow_upward
                                },
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                onClick = { onSelected(index) },
            )
        }
    }
}

private data class LibraryDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

@Composable
private fun libraryDialogLayout(): LibraryDialogLayout {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return LibraryDialogLayout(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    )
}

@Composable
private fun libraryDialogListHeight(maxHeight: Int = 420) =
    LocalConfiguration.current.screenHeightDp
        .minus(220)
        .coerceAtLeast(120)
        .coerceAtMost(maxHeight)
        .dp

@Composable
internal fun AppActionsDialog(
    app: LibraryAppUiItem,
    onDismiss: () -> Unit,
    onShortcut: (() -> Unit)?,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
    onEditMetadata: (() -> Unit)? = null,
    onAddToCollection: (() -> Unit)? = null,
    onRemoveFromCollection: (() -> Unit)? = null,
    onShareApp: (() -> Unit)? = null,
    onExportAppBundle: (() -> Unit)? = null,
) {
    val layout = libraryDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = app.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.library_vendor_version, app.author, app.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.playCount > 0L || app.totalPlayTimeMs > 0L) {
                    Text(
                        text = stringResource(
                            R.string.library_play_stats_summary,
                            app.playCount,
                            LibraryPlayStatsFormatter.duration(app.totalPlayTimeMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = libraryDialogListHeight())) {
                if (onShortcut != null) {
                    item {
                        DialogAction(
                            label = R.string.action_context_shortcut,
                            icon = R.drawable.ic_add,
                            onDismiss = onDismiss,
                            action = onShortcut,
                        )
                    }
                }
                item {
                    if (onEditMetadata != null) {
                        DialogAction(
                            label = R.string.library_metadata_edit_title,
                            icon = R.drawable.ic_edit,
                            onDismiss = onDismiss,
                            action = onEditMetadata,
                        )
                    } else {
                        DialogAction(
                            label = R.string.action_context_rename,
                            icon = R.drawable.ic_edit,
                            onDismiss = onDismiss,
                            action = onRename,
                        )
                    }
                }
                item {
                    DialogAction(
                        label = R.string.action_settings,
                        icon = R.drawable.ic_settings,
                        onDismiss = onDismiss,
                        action = onSettings,
                    )
                }
                if (onAddToCollection != null) {
                    item {
                        DialogAction(
                            label = R.string.library_collection_add_app,
                            icon = R.drawable.ic_collections,
                            onDismiss = onDismiss,
                            action = onAddToCollection,
                        )
                    }
                }
                if (onRemoveFromCollection != null) {
                    item {
                        DialogAction(
                            label = R.string.library_collection_remove_from_current,
                            icon = R.drawable.ic_delete,
                            onDismiss = onDismiss,
                            action = onRemoveFromCollection,
                        )
                    }
                }
                if (onShareApp != null) {
                    item {
                        DialogAction(
                            label = R.string.library_action_share_app,
                            icon = R.drawable.ic_share,
                            onDismiss = onDismiss,
                            action = onShareApp,
                        )
                    }
                }
                if (onExportAppBundle != null) {
                    item {
                        DialogAction(
                            label = R.string.library_action_export_bundle,
                            icon = R.drawable.ic_save,
                            onDismiss = onDismiss,
                            action = onExportAppBundle,
                        )
                    }
                }
                if (app.canReinstall) {
                    item {
                        DialogAction(
                            label = R.string.action_reinstall,
                            icon = R.drawable.ic_restart_alt,
                            onDismiss = onDismiss,
                            action = onReinstall,
                        )
                    }
                }
                item {
                    DialogAction(
                        label = R.string.action_context_delete,
                        icon = R.drawable.ic_delete,
                        destructive = true,
                        onDismiss = onDismiss,
                        action = onDelete,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DialogAction(
    label: Int,
    icon: Int?,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(label),
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingContent = {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = contentColor,
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = {
                    onDismiss()
                    action()
                },
            ),
    )
}

@Composable
private fun RenameAppDialog(
    app: LibraryAppUiItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(app.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = app.title,
                selection = TextRange(app.title.length),
            ),
        )
    }
    val valid = value.text.trim().isNotEmpty()
    val layout = libraryDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_context_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = !valid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.text.trim()) },
                enabled = valid,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
internal fun LibraryInformationDialog(
    dialog: LibraryInfoDialog,
    onDismiss: () -> Unit,
    onOpen: (LibraryInfoDialog) -> Unit,
) {
    val context = LocalContext.current
    val title = when (dialog) {
        LibraryInfoDialog.About -> stringResource(R.string.about)
        LibraryInfoDialog.Help -> stringResource(R.string.help)
        LibraryInfoDialog.Licenses -> stringResource(R.string.licenses)
    }
    val icon = when (dialog) {
        LibraryInfoDialog.About -> R.drawable.ic_info
        LibraryInfoDialog.Help -> R.drawable.ic_help
        LibraryInfoDialog.Licenses -> R.drawable.ic_list
    }
    val layout = libraryDialogLayout()
    val maxMessageHeight = libraryDialogListHeight(
        maxHeight = if (dialog == LibraryInfoDialog.Licenses) 520 else 420,
    )
    val message = when (dialog) {
        LibraryInfoDialog.About -> AnnotatedString(stringResource(R.string.about_message))
        LibraryInfoDialog.Help -> AnnotatedString.fromHtml(stringResource(R.string.help_message))
        LibraryInfoDialog.Licenses -> try {
            AnnotatedString.fromHtml(
                context.assets.open("licenses.html").bufferedReader().use { it.readText() },
            )
        } catch (_: Exception) {
            AnnotatedString(stringResource(R.string.licenses_unavailable))
        }
    }

    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(title)
            }
        },
        text = {
            when (dialog) {
                LibraryInfoDialog.About -> LibraryAboutBody(
                    onLicenses = { onOpen(LibraryInfoDialog.Licenses) },
                )
                LibraryInfoDialog.Help -> LibraryHelpBody(
                    message = message,
                    maxHeight = maxMessageHeight,
                )
                LibraryInfoDialog.Licenses -> Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxMessageHeight)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun LibraryAboutBody(
    onLicenses: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.about_product_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append(stringResource(R.string.version))
                append(' ')
                append(BuildConfig.VERSION_NAME)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = AnnotatedString.fromHtml(stringResource(R.string.about_github)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.about_maintainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_message).trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onLicenses) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.licenses))
            }
        }
    }
}

@Composable
private fun LibraryHelpBody(
    message: AnnotatedString,
    maxHeight: Dp,
) {
    val items = remember(message.text) {
        message.text
            .split('•')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
