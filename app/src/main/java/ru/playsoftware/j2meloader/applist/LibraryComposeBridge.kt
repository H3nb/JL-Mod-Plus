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

import android.content.Context
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import ru.playsoftware.j2meloader.BuildConfig
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.io.File
import java.util.Locale
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
    // Kept at the UI boundary until the library model exposes MIDlet descriptions.
    val description: String = "",
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
    val canAddShortcut: Boolean = true,
)

interface LibraryActions {
    fun onSearch(query: String)
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
    fun onReinstall(appId: Int)
    fun onDelete(appId: Int)
    fun onOpenSettings()
    fun onOpenProfiles()
    fun onOpenCrashReports()
    fun onSaveLog()
    fun onExit()
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
                LibraryScreen(state = state, actions = actions)
            }
        }
    }

    fun updateApps(items: List<AppItem>, appliedFilter: String) {
        state = state.copy(
            loading = false,
            apps = items.map { item ->
                LibraryAppUiItem(
                    id = item.id,
                    title = item.title,
                    author = item.author,
                    version = item.version,
                    iconPath = item.imagePathExt,
                    canReinstall = File(item.pathExt + ru.playsoftware.j2meloader.config.Config.MIDLET_RES_FILE).exists(),
                    description = "",
                )
            },
            appliedFilter = appliedFilter,
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
}

internal enum class LibraryInfoDialog {
    About,
    More,
    Help,
    Licenses,
}

private val LibraryScaffoldInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    var selectedDestinationIndex by rememberSaveable { mutableStateOf(0) }
    val destination = when (selectedDestinationIndex) {
        1 -> LibraryDestination.Collections
        2 -> LibraryDestination.Options
        else -> LibraryDestination.Apps
    }
    var showInstallFab by rememberSaveable { mutableStateOf(true) }
    var showNavigationBar by rememberSaveable { mutableStateOf(true) }
    var appActions by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var infoDialog by remember { mutableStateOf<LibraryInfoDialog?>(null) }
    val isImeVisible = WindowInsets.isImeVisible
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(destination) {
        if (destination != LibraryDestination.Apps) {
            showInstallFab = true
            showNavigationBar = true
            appActions = null
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        if (isLandscape) {
            // The side rail does not consume vertical content space, so keep it persistent.
            LibraryNavigationRail(
                selected = destination,
                onSelected = { selectedDestinationIndex = it.ordinal },
            )
        }

        Scaffold(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentWindowInsets = LibraryScaffoldInsets,
            bottomBar = {
                // Remove the slot while the IME is open. Scaffold includes the slot's measured
                // height in its content padding; animating it out would leave a blank strip above
                // the keyboard and make the list appear clipped.
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
                            onSelected = { selectedDestinationIndex = it.ordinal },
                        )
                    }
                }
            },
            floatingActionButton = {
                // The FAB is part of the same transient chrome and must not compete with the IME.
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
            when (destination) {
                LibraryDestination.Apps -> LibraryAppsDestination(
                    state = state,
                    scaffoldPadding = padding,
                    onOpenApp = actions::onOpenApp,
                    onOpenActions = { appActions = it },
                    onSearch = actions::onSearch,
                    onSort = actions::onSort,
                    onFabVisibilityChanged = { showInstallFab = it },
                    onNavigationVisibilityChanged = { visible ->
                        if (!isLandscape) showNavigationBar = visible
                    },
                )
                LibraryDestination.Collections -> LibraryCollectionsDestination(padding)
                LibraryDestination.Options -> LibraryOptionsDestination(
                    state = state,
                    scaffoldPadding = padding,
                    onLayoutChange = actions::onLayoutChange,
                    onIconRatioChange = actions::onIconRatioChange,
                    onHideGridTitlesChange = actions::onHideGridTitlesChange,
                    onGridSpacingChange = actions::onGridSpacingChange,
                    onAbout = { infoDialog = LibraryInfoDialog.About },
                    onSettings = actions::onOpenSettings,
                    onProfiles = actions::onOpenProfiles,
                    onHelp = { infoDialog = LibraryInfoDialog.Help },
                    onCrashReports = actions::onOpenCrashReports,
                    onSaveLog = actions::onSaveLog,
                    onExit = actions::onExit,
                )
            }
        }
    }

    appActions?.let { app ->
        AppActionsDialog(
            app = app,
            onDismiss = { appActions = null },
            onShortcut = if (state.canAddShortcut) {
                { actions.onAddShortcut(app.id) }
            } else {
                null
            },
            onRename = { renameTarget = app },
            onSettings = { actions.onOpenAppSettings(app.id) },
            onReinstall = { actions.onReinstall(app.id) },
            onDelete = { deleteTarget = app },
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.message_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    actions.onDelete(app.id)
                }) { Text(stringResource(android.R.string.ok)) }
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
    NavigationRail {
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
    NavigationBar {
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
    )
}

@Composable
private fun LibraryAppsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onSearch: (String) -> Unit,
    onSort: (Int) -> Unit,
    onFabVisibilityChanged: (Boolean) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.appliedFilter) }
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
        delay(300)
        onSearch(query.lowercase(Locale.getDefault()))
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
            onSort = onSort,
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
                if (state.loading) {
                    item {
                        LibraryLoadingState()
                    }
                } else if (state.apps.isEmpty()) {
                    item {
                        LibraryEmptyState(state.appliedFilter)
                    }
                } else {
                    items(state.apps, key = { it.id }) { app ->
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
                    state.loading -> item { LibraryLoadingState() }
                    state.apps.isEmpty() -> item { LibraryEmptyState(state.appliedFilter) }
                    else -> items(state.apps, key = { it.id }) { app ->
                        LibraryListItem(
                            app = app,
                            iconRatio = state.iconRatio,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
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
    onSort: (Int) -> Unit,
    interactive: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.search),
                    )
                },
            )
            if (interactive) {
                Box {
                    IconButton(onClick = { onSortVisibilityChanged(true) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sort),
                            contentDescription = stringResource(R.string.pref_app_sort_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LibrarySortMenu(
                        expanded = sortVisible,
                        entries = stringArrayResource(R.array.pref_app_sort_entries).toList(),
                        selectedSort = state.sortVariant and Int.MAX_VALUE,
                        ascending = state.sortVariant >= 0,
                        onDismissRequest = { onSortVisibilityChanged(false) },
                        onSelected = { index ->
                            onSortVisibilityChanged(false)
                            onSort(index)
                        },
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryQuickFilter(R.string.library_filter_all, selected = true, enabled = true)
            LibraryQuickFilter(R.string.library_filter_recently_opened)
            LibraryQuickFilter(R.string.library_filter_recently_added)
            LibraryQuickFilter(R.string.library_filter_favorites)
        }
    }
}

@Composable
private fun LibraryQuickFilter(
    label: Int,
    selected: Boolean = false,
    enabled: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = {},
        enabled = enabled,
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun LibraryLoadingState() {
    val loadingDescription = stringResource(R.string.loading_apps)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = loadingDescription
            },
        )
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
private fun LibraryCollectionsDestination(scaffoldPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_collections),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.library_collections_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.library_collections_empty_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
    onProfiles: () -> Unit,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.library_destination_options),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LibraryOptionsSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    title = R.string.library_options_display_title,
                ) {
                    LibraryOptionGroup(label = R.string.pref_apps_view) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.layout == LibraryLayout.List,
                                onClick = { onLayoutChange(LibraryLayout.List) },
                                label = { Text(stringResource(R.string.library_view_list)) },
                            )
                            FilterChip(
                                selected = state.layout == LibraryLayout.Grid,
                                onClick = { onLayoutChange(LibraryLayout.Grid) },
                                label = { Text(stringResource(R.string.library_view_grid)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    LibraryOptionGroup(
                        label = R.string.library_icon_ratio_title,
                        summary = R.string.library_icon_ratio_summary,
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                }
                if (state.layout == LibraryLayout.Grid) {
                    LibraryOptionsSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        title = R.string.library_options_grid_title,
                    ) {
                        LibraryOptionGroup(
                            label = R.string.library_grid_spacing_title,
                            summary = R.string.library_grid_spacing_summary,
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.gridSpacing == LibraryGridSpacing.Compact,
                                    onClick = {
                                        onGridSpacingChange(LibraryGridSpacing.Compact)
                                    },
                                    label = {
                                        Text(stringResource(R.string.library_grid_spacing_compact))
                                    },
                                )
                                FilterChip(
                                    selected = state.gridSpacing == LibraryGridSpacing.Standard,
                                    onClick = {
                                        onGridSpacingChange(LibraryGridSpacing.Standard)
                                    },
                                    label = {
                                        Text(stringResource(R.string.library_grid_spacing_standard))
                                    },
                                )
                                FilterChip(
                                    selected = state.gridSpacing == LibraryGridSpacing.Spacious,
                                    onClick = {
                                        onGridSpacingChange(LibraryGridSpacing.Spacious)
                                    },
                                    label = {
                                        Text(stringResource(R.string.library_grid_spacing_spacious))
                                    },
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.library_hide_grid_titles),
                                    style = MaterialTheme.typography.titleMedium,
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
                Text(
                    text = stringResource(R.string.library_options_actions_title),
                    modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        LibraryActionRow(R.string.about, onAbout)
                        HorizontalDivider()
                        LibraryActionRow(R.string.action_settings, onSettings)
                        HorizontalDivider()
                        LibraryActionRow(R.string.profiles, onProfiles)
                        HorizontalDivider()
                        LibraryActionRow(R.string.help, onHelp)
                        HorizontalDivider()
                        LibraryActionRow(R.string.crash_reports, onCrashReports)
                        HorizontalDivider()
                        LibraryActionRow(R.string.save_log, onSaveLog)
                        HorizontalDivider()
                        LibraryActionRow(R.string.exit, onExit)
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
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun LibraryOptionGroup(
    label: Int,
    summary: Int? = null,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
        )
        summary?.let {
            Text(
                text = stringResource(it),
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
    iconRatio: LibraryIconRatio,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.width(48.dp),
                contentSize = 36.dp,
                iconRatio = iconRatio,
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 1.dp),
            ) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LibraryDescription(app.description, app.id)
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(top = 1.dp),
            ) {
                LibraryFavoritePlaceholder(app.id)
            }
        }
    }
}

@Composable
private fun LibraryDescription(descriptionValue: String, appId: Int) {
    val description = descriptionValue.trim()
    if (description.isEmpty()) return

    val collapsedMaxLines = 2
    var expanded by rememberSaveable(appId, description) { mutableStateOf(false) }
    var overflows by remember(appId, description) {
        mutableStateOf(description.length > LIBRARY_DESCRIPTION_OVERFLOW_FALLBACK_LENGTH)
    }
    val expandDescriptionLabel = stringResource(R.string.library_expand_description)
    val collapseDescriptionLabel = stringResource(R.string.library_collapse_description)
    val markerVisible = !expanded && overflows

    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = description,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = expanded || overflows,
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                )
                .then(
                    if (expanded) {
                        Modifier.semantics {
                            contentDescription = collapseDescriptionLabel
                        }
                    } else {
                        Modifier
                    },
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = if (expanded || markerVisible) {
                TextOverflow.Clip
            } else {
                TextOverflow.Ellipsis
            },
            onTextLayout = { result ->
                if (!expanded) {
                    val lastLineEnd = if (result.lineCount > 0) {
                        result.getLineEnd(result.lineCount - 1)
                    } else {
                        0
                    }
                    val lineIsEllipsized = result.lineCount > 0 &&
                        result.isLineEllipsized(result.lineCount - 1)
                    overflows = overflows || result.didOverflowHeight || result.didOverflowWidth ||
                        result.hasVisualOverflow || lineIsEllipsized ||
                        result.lineCount >= collapsedMaxLines && lastLineEnd < description.length
                }
            },
        )
        if (markerVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(32.dp)
                    .height(28.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(role = Role.Button) { expanded = true }
                    .semantics {
                        contentDescription = expandDescriptionLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private const val LIBRARY_DESCRIPTION_OVERFLOW_FALLBACK_LENGTH = 120

@Composable
private fun LibraryFavoritePlaceholder(appId: Int) {
    var favorite by rememberSaveable(appId) { mutableStateOf(false) }
    IconButton(
        onClick = { favorite = !favorite },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(
                if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
            ),
            contentDescription = stringResource(
                if (favorite) {
                    R.string.library_remove_favorite_coming_soon
                } else {
                    R.string.library_favorite_coming_soon
                },
            ),
            modifier = Modifier.size(28.dp),
            tint = if (favorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private data class LibraryIconAnalysis(
    val contentBounds: Rect,
    val framedCropBounds: Rect?,
    val stableBackgroundColor: Int?,
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
private const val LIBRARY_ICON_PRESENTATION_VERSION = 2
private val LibraryIconCache = object : LruCache<String, LibraryNormalizedIcon>(LIBRARY_ICON_CACHE_BYTES) {
    override fun sizeOf(key: String, value: LibraryNormalizedIcon): Int {
        return (value.bitmap.width.toLong() * value.bitmap.height.toLong() * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }
}

private fun loadLibraryIcon(
    context: Context,
    iconPath: String?,
    fallbackSizePx: Int,
    normalizeSquareIcon: Boolean,
): LibraryNormalizedIcon? {
    val requestedSizePx = fallbackSizePx.coerceAtLeast(1)
    val fileSource = iconPath
        ?.takeIf(String::isNotBlank)
        ?.let { path -> decodeLibraryBitmap(path, requestedSizePx) }

    if (fileSource != null) {
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
        if (analysis != null) {
            val cropBounds = if (analysis.presentation.mode == LibraryIconPresentationMode.Subject) {
                analysis.framedCropBounds ?: analysis.contentBounds
            } else {
                null
            }
            val normalized = if (
                cropBounds != null &&
                !cropBounds.isFullBitmap(fileSource)
            ) {
                fileSource.cropLibraryBounds(cropBounds)
            } else {
                fileSource
            }
            val representativeColor =
                normalized.findRepresentativeColor() ?: normalized.findAverageVisibleColor()
            val stableBackgroundColor = analysis.stableBackgroundColor?.toComposeLibraryColor()
            val tileColor = if (analysis.presentation.mode == LibraryIconPresentationMode.Cover) {
                null
            } else {
                stableBackgroundColor ?: representativeColor?.let { accent ->
                    contentDerivedLibrarySlotColor(
                        accent = accent,
                        foregroundLuminance = analysis.foregroundLuminance,
                    )
                }
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
    }

    val fallbackSource = ContextCompat.getDrawable(context, R.drawable.ic_default_midlet)
        ?.mutate()
        ?.also { drawable ->
            drawable.setTint(
                ContextCompat.getColor(context, R.color.library_default_icon),
            )
        }
        ?.toBitmap(requestedSizePx, requestedSizePx)
        ?: return null
    val fallbackBounds = if (normalizeSquareIcon) {
        fallbackSource.analyzeLibraryIcon()?.contentBounds
    } else {
        null
    }
    val fallback = if (
        fallbackBounds != null &&
        !fallbackBounds.isFullBitmap(fallbackSource)
    ) {
        fallbackSource.cropLibraryBounds(fallbackBounds)
    } else {
        fallbackSource
    }

    return LibraryNormalizedIcon(
        bitmap = fallback.asImageBitmap(),
        filterQuality = fallback.libraryFilterQuality(),
        representativeColor = if (normalizeSquareIcon) fallback.findAverageVisibleColor() else null,
        presentationMode = LibraryIconPresentationMode.Fallback,
        visualScale = LIBRARY_FALLBACK_VISUAL_SCALE,
        foregroundLuminance = fallback.averageVisibleLuminance(),
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
    val stableBackgroundColor = findUniformCornerBackgroundColor()
    val framedCropBounds = stableBackgroundColor?.let(::findFramedArtworkCropBounds)
    val presentation = decideLibraryIconPresentation(
        LibraryIconPresentationInput(
            transparentRatio = transparentRatio,
            boundsCoverage = boundsCoverage,
            occupancy = occupancy,
            aspectFill = aspectFill,
            hasFramedCrop = framedCropBounds != null,
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
        stableBackgroundColor = stableBackgroundColor,
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
private const val LIBRARY_SOURCE_FILL_LIGHT_NEUTRAL = 0.84f
private const val LIBRARY_SOURCE_FILL_DARK_NEUTRAL = 0.23f
private const val LIBRARY_SOURCE_FILL_COLOR_AMOUNT = 0.38f
private const val LIBRARY_SOURCE_FILL_NEUTRAL_AMOUNT = 0.18f
private const val LIBRARY_SOURCE_FILL_MIN_CHROMA = 0.08f
private const val LIBRARY_SOURCE_FILL_LIGHT_FOREGROUND_THRESHOLD = 0.52f

/**
 * Derives a stable real-icon tile color from source artwork rather than the current app theme.
 * The fixed neutral anchor keeps legacy icon palettes restrained while the source accent remains
 * visible enough to preserve identity. Foreground luminance chooses the opposite contrast side.
 */
private fun contentDerivedLibrarySlotColor(
    accent: Color,
    foregroundLuminance: Float,
): Color {
    val neutral = if (foregroundLuminance < LIBRARY_SOURCE_FILL_LIGHT_FOREGROUND_THRESHOLD) {
        Color(
            LIBRARY_SOURCE_FILL_LIGHT_NEUTRAL,
            LIBRARY_SOURCE_FILL_LIGHT_NEUTRAL,
            LIBRARY_SOURCE_FILL_LIGHT_NEUTRAL,
        )
    } else {
        Color(
            LIBRARY_SOURCE_FILL_DARK_NEUTRAL,
            LIBRARY_SOURCE_FILL_DARK_NEUTRAL,
            LIBRARY_SOURCE_FILL_DARK_NEUTRAL,
        )
    }
    val chroma = maxOf(accent.red, accent.green, accent.blue) -
        minOf(accent.red, accent.green, accent.blue)
    val amount = if (chroma >= LIBRARY_SOURCE_FILL_MIN_CHROMA) {
        LIBRARY_SOURCE_FILL_COLOR_AMOUNT
    } else {
        LIBRARY_SOURCE_FILL_NEUTRAL_AMOUNT
    }
    return blendLibrarySlotColor(neutral, accent.copy(alpha = 1f), amount)
}

/**
 * Preserves the existing theme-aware fallback treatment. Real icon tiles do not use this path.
 */
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
    val context = LocalContext.current
    val contentSizePx = with(LocalDensity.current) { contentSize.roundToPx() }.coerceAtLeast(1)
    val fallbackTint = ContextCompat.getColor(context, R.color.library_default_icon)
    val cacheKey = remember(app.iconPath, contentSizePx, iconRatio, fallbackTint) {
        libraryIconCacheKey(app.iconPath, contentSizePx, iconRatio, fallbackTint)
    }
    return remember(cacheKey) {
        LibraryIconCache.get(cacheKey) ?: loadLibraryIcon(
            context = context,
            iconPath = app.iconPath,
            fallbackSizePx = contentSizePx,
            normalizeSquareIcon = iconRatio == LibraryIconRatio.Square,
        )?.also { LibraryIconCache.put(cacheKey, it) }
    }
}

private fun libraryIconCacheKey(
    iconPath: String?,
    targetSizePx: Int,
    iconRatio: LibraryIconRatio,
    fallbackTint: Int,
): String {
    if (iconPath.isNullOrBlank()) {
        return "fallback:$fallbackTint:$targetSizePx:${iconRatio.name}"
    }
    val file = File(iconPath)
    val modified = runCatching { file.lastModified() }.getOrDefault(0L)
    val length = runCatching { file.length() }.getOrDefault(0L)
    return "real:$LIBRARY_ICON_PRESENTATION_VERSION:$iconPath:$modified:$length:$targetSizePx:${iconRatio.name}"
}

@Composable
private fun LibraryIconSlot(
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
        val icon = rememberLibraryIcon(app, artworkSize, iconRatio)
        val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
        val containerColor = when {
            iconRatio != LibraryIconRatio.Square -> baseContainerColor
            icon?.presentationMode == LibraryIconPresentationMode.Fallback -> {
                icon.representativeColor?.let { representativeColor ->
                    adaptiveLibraryFallbackSlotColor(
                        base = baseContainerColor,
                        accent = representativeColor,
                        foregroundLuminance = icon.foregroundLuminance,
                    )
                } ?: baseContainerColor
            }
            icon?.presentationMode == LibraryIconPresentationMode.Cover -> baseContainerColor
            else -> icon?.tileColor ?: baseContainerColor
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
                LibraryIconArtwork(
                    icon = icon,
                    contentSize = artworkSize,
                    iconRatio = iconRatio,
                )
            }
        }
    }
}

@Composable
private fun LibraryIconArtwork(
    icon: LibraryNormalizedIcon?,
    contentSize: Dp,
    iconRatio: LibraryIconRatio,
) {
    if (icon == null) return

    val artworkModifier = when {
        iconRatio != LibraryIconRatio.Square -> Modifier.fillMaxSize()
        icon.presentationMode == LibraryIconPresentationMode.Subject ->
            Modifier.size(contentSize * icon.visualScale)
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
private fun LibraryActionRow(label: Int, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(label)) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = action),
    )
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

@Composable
internal fun AppActionsDialog(
    app: LibraryAppUiItem,
    onDismiss: () -> Unit,
    onShortcut: (() -> Unit)?,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (onShortcut != null) {
                    DialogAction(R.string.action_context_shortcut, onDismiss, onShortcut)
                }
                DialogAction(R.string.action_context_rename, onDismiss, onRename)
                DialogAction(R.string.action_settings, onDismiss, onSettings)
                if (app.canReinstall) {
                    DialogAction(R.string.action_reinstall, onDismiss, onReinstall)
                }
                DialogAction(R.string.action_context_delete, onDismiss, onDelete)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DialogAction(label: Int, onDismiss: () -> Unit, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(label)) },
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
    AlertDialog(
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
    val title: String
    val message: AnnotatedString
    val maxMessageHeight = if (dialog == LibraryInfoDialog.Licenses) 380.dp else 300.dp
    when (dialog) {
        LibraryInfoDialog.About -> {
            title = stringResource(R.string.about_product_name)
            message = buildAnnotatedString {
                append(stringResource(R.string.version))
                append(' ')
                append(BuildConfig.VERSION_NAME)
                append('\n')
                append(AnnotatedString.fromHtml(stringResource(R.string.about_github)))
                append('\n')
                append(stringResource(R.string.about_maintainer))
            }
        }
        LibraryInfoDialog.More -> {
            title = stringResource(R.string.app_name)
            message = AnnotatedString(stringResource(R.string.about_message))
        }
        LibraryInfoDialog.Help -> {
            title = stringResource(R.string.help)
            message = AnnotatedString.fromHtml(stringResource(R.string.help_message))
        }
        LibraryInfoDialog.Licenses -> {
            title = stringResource(R.string.licenses)
            message = try {
                AnnotatedString.fromHtml(
                    context.assets.open("licenses.html").bufferedReader().use { it.readText() },
                )
            } catch (_: Exception) {
                AnnotatedString(stringResource(R.string.licenses_unavailable))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxMessageHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row {
                if (dialog == LibraryInfoDialog.About) {
                    TextButton(onClick = { onOpen(LibraryInfoDialog.Licenses) }) {
                        Text(stringResource(R.string.licenses))
                    }
                    TextButton(onClick = { onOpen(LibraryInfoDialog.More) }) {
                        Text(stringResource(R.string.more))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
