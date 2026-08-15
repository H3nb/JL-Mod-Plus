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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import ru.playsoftware.j2meloader.BuildConfig
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.io.File
import java.util.Locale

enum class LibraryLayout {
    List,
    Grid,
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
)

data class LibraryUiState(
    val loading: Boolean = true,
    val apps: List<LibraryAppUiItem> = emptyList(),
    val appliedFilter: String = "",
    val layout: LibraryLayout = LibraryLayout.List,
    val sortVariant: Int = 0,
    val canAddShortcut: Boolean = true,
)

interface LibraryActions {
    fun onSearch(query: String)
    fun onLayoutChange(layout: LibraryLayout)
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
    canAddShortcut: Boolean,
) {
    private var state by mutableStateOf(
        LibraryUiState(
            layout = initialLayout,
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
                )
            },
            appliedFilter = appliedFilter,
        )
    }

    fun updateLayout(layout: LibraryLayout) {
        state = state.copy(layout = layout)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    enableIdleTitleMarquee: Boolean = true,
) {
    var selectedDestinationIndex by rememberSaveable { mutableStateOf(0) }
    val destination = when (selectedDestinationIndex) {
        1 -> LibraryDestination.Collections
        2 -> LibraryDestination.Options
        else -> LibraryDestination.Apps
    }
    var showInstallFab by rememberSaveable { mutableStateOf(true) }
    var appActions by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var infoDialog by remember { mutableStateOf<LibraryInfoDialog?>(null) }

    LaunchedEffect(destination) {
        if (destination != LibraryDestination.Apps) {
            showInstallFab = true
            appActions = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = LibraryScaffoldInsets,
        bottomBar = {
            LibraryNavigationBar(
                selected = destination,
                onSelected = { selectedDestinationIndex = it.ordinal },
            )
        },
        floatingActionButton = {
            if (destination == LibraryDestination.Apps && showInstallFab) {
                FloatingActionButton(onClick = actions::onInstall) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.install),
                    )
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
                enableIdleTitleMarquee = enableIdleTitleMarquee,
            )
            LibraryDestination.Collections -> LibraryCollectionsDestination(padding)
            LibraryDestination.Options -> LibraryOptionsDestination(
                state = state,
                scaffoldPadding = padding,
                onLayoutChange = actions::onLayoutChange,
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
    enableIdleTitleMarquee: Boolean,
) {
    var query by rememberSaveable { mutableStateOf(state.appliedFilter) }
    var sortVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(query) {
        delay(300)
        onSearch(query.lowercase(Locale.getDefault()))
    }

    LaunchedEffect(state.layout) {
        onFabVisibilityChanged(true)
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow {
            if (state.layout == LibraryLayout.List) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }
        }.collectLatest { (index, offset) ->
            val movingDown = index > previousIndex ||
                    (index == previousIndex && offset > previousOffset)
            if (index == 0 && offset == 0) {
                onFabVisibilityChanged(true)
            } else {
                onFabVisibilityChanged(!movingDown)
            }
            previousIndex = index
            previousOffset = offset
        }
    }

    val listModifier = Modifier
        .fillMaxSize()
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    if (state.layout == LibraryLayout.Grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 80.dp),
            contentPadding = scaffoldPadding,
            modifier = listModifier,
            state = gridState,
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryAppsHeader(
                    query = query,
                    onQueryChange = { query = it },
                    state = state,
                    sortVisible = sortVisible,
                    onSortVisibilityChanged = { sortVisible = it },
                    onSort = onSort,
                )
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryLoadingState()
                }
            } else if (state.apps.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryEmptyState(state.appliedFilter)
                }
            } else {
                items(state.apps, key = { it.id }) { app ->
                    LibraryGridItem(
                        app = app,
                        onOpenApp = onOpenApp,
                        onOpenActions = onOpenActions,
                        marqueeEnabled = enableIdleTitleMarquee && !gridState.isScrollInProgress,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = scaffoldPadding,
            modifier = listModifier,
            state = listState,
        ) {
            item {
                LibraryAppsHeader(
                    query = query,
                    onQueryChange = { query = it },
                    state = state,
                    sortVisible = sortVisible,
                    onSortVisibilityChanged = { sortVisible = it },
                    onSort = onSort,
                )
            }
            when {
                state.loading -> item { LibraryLoadingState() }
                state.apps.isEmpty() -> item { LibraryEmptyState(state.appliedFilter) }
                else -> items(state.apps, key = { it.id }) { app ->
                    LibraryListItem(
                        app = app,
                        onOpenApp = onOpenApp,
                        onOpenActions = onOpenActions,
                        marqueeEnabled = enableIdleTitleMarquee && !listState.isScrollInProgress,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryAppsHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    state: LibraryUiState,
    sortVisible: Boolean,
    onSortVisibilityChanged: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
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
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.search),
                    )
                },
            )
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

@Composable
internal fun LibraryOptionsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onLayoutChange: (LibraryLayout) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onProfiles: () -> Unit,
    onHelp: () -> Unit,
    onCrashReports: () -> Unit,
    onSaveLog: () -> Unit,
    onExit: () -> Unit,
) {
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
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.pref_apps_view),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                }
            }
        }
        item { LibraryActionRow(R.string.about, onAbout) }
        item { LibraryActionRow(R.string.action_settings, onSettings) }
        item { LibraryActionRow(R.string.profiles, onProfiles) }
        item { LibraryActionRow(R.string.help, onHelp) }
        item { LibraryActionRow(R.string.crash_reports, onCrashReports) }
        item { LibraryActionRow(R.string.save_log, onSaveLog) }
        item { LibraryActionRow(R.string.exit, onExit) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    marqueeEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibraryIconSlot(app = app, slotSize = 64.dp, contentSize = 48.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = app.title,
                modifier = Modifier.libraryTitleMarquee(marqueeEnabled),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryListItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    marqueeEnabled: Boolean,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIconSlot(app = app, slotSize = 48.dp, contentSize = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    modifier = Modifier.libraryTitleMarquee(marqueeEnabled),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.author,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = app.version,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                LibraryFavoritePlaceholder()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.libraryTitleMarquee(enabled: Boolean): Modifier {
    return if (enabled) basicMarquee() else this
}

@Composable
private fun LibraryFavoritePlaceholder(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_star),
        contentDescription = stringResource(R.string.library_favorite_coming_soon),
        modifier = modifier.size(28.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private data class LibraryNormalizedIcon(
    val bitmap: ImageBitmap,
    val filterQuality: FilterQuality,
    val representativeColor: Color?,
)

private fun loadLibraryIcon(
    context: Context,
    iconPath: String?,
    fallbackSizePx: Int,
): LibraryNormalizedIcon? {
    val source = iconPath
        ?.takeIf(String::isNotBlank)
        ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        ?: ContextCompat.getDrawable(context, R.drawable.ic_default_midlet)
            ?.mutate()
            ?.also { drawable ->
                drawable.setTint(
                    ContextCompat.getColor(context, R.color.library_default_icon),
                )
            }
            ?.toBitmap(fallbackSizePx, fallbackSizePx)
        ?: return null

    val contentBounds = runCatching { source.findAlphaContentBounds() }.getOrNull()
    val normalized = if (contentBounds == null ||
        (contentBounds.left == 0 &&
            contentBounds.top == 0 &&
            contentBounds.right == source.width &&
            contentBounds.bottom == source.height)
    ) {
        source
    } else {
        runCatching {
            Bitmap.createBitmap(
                source,
                contentBounds.left,
                contentBounds.top,
                contentBounds.width(),
                contentBounds.height(),
            )
        }.getOrElse { source }
    }

    return LibraryNormalizedIcon(
        bitmap = normalized.asImageBitmap(),
        filterQuality = if (normalized.width <= 64 && normalized.height <= 64) {
            FilterQuality.None
        } else {
            FilterQuality.Medium
        },
        representativeColor = runCatching { normalized.findRepresentativeColor() }.getOrNull(),
    )
}

private fun Bitmap.findAlphaContentBounds(): Rect? {
    if (!hasAlpha() || width <= 0 || height <= 0) {
        return Rect(0, 0, width, height)
    }

    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            if ((row[x] ushr 24) < MIN_VISIBLE_ALPHA) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    return if (right < left || bottom < top) {
        null
    } else {
        Rect(left, top, right + 1, bottom + 1)
    }
}

private fun Bitmap.findRepresentativeColor(): Color? {
    if (width <= 0 || height <= 0) return null

    val step = maxOf(1, maxOf(width, height) / 32)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var weight = 0.0
    val row = IntArray(width)
    for (y in 0 until height step step) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step step) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < 16) continue

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

private const val MIN_VISIBLE_ALPHA = 16
private const val LIBRARY_SLOT_TINT_AMOUNT = 0.12f

/**
 * Gives the slot a restrained adaptive-icon-like tint without touching the
 * bitmap foreground. Keeping the theme surface dominant prevents bright or
 * dark source icons from losing contrast against their own background.
 */
private fun adaptiveLibrarySlotColor(base: Color, accent: Color): Color {
    return blendLibrarySlotColor(base, accent, amount = LIBRARY_SLOT_TINT_AMOUNT)
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
): LibraryNormalizedIcon? {
    val context = LocalContext.current
    val contentSizePx = with(LocalDensity.current) { contentSize.roundToPx() }
    return remember(app.iconPath, contentSizePx) {
        loadLibraryIcon(context, app.iconPath, contentSizePx)
    }
}

@Composable
private fun LibraryIconSlot(
    app: LibraryAppUiItem,
    slotSize: Dp,
    contentSize: Dp,
) {
    val icon = rememberLibraryIcon(app, contentSize)
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = icon?.representativeColor?.let { representativeColor ->
        adaptiveLibrarySlotColor(baseContainerColor, representativeColor)
    } ?: baseContainerColor

    Card(
        modifier = Modifier.size(slotSize),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconArtwork(icon = icon, contentSize = contentSize)
        }
    }
}

@Composable
private fun LibraryIconArtwork(icon: LibraryNormalizedIcon?, contentSize: Dp) {
    if (icon != null) {
        Image(
            bitmap = icon.bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(contentSize)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Fit,
            filterQuality = icon.filterQuality,
        )
    }
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
