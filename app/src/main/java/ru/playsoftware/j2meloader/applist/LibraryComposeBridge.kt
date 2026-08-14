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

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.delay
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
    val layout: LibraryLayout = LibraryLayout.Grid,
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

/** The Java host already owns the safe-area padding for this hybrid screen. */
private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(state.appliedFilter) }
    var overflowVisible by remember { mutableStateOf(false) }
    var sortVisible by remember { mutableStateOf(false) }
    var appActions by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var infoDialog by remember { mutableStateOf<LibraryInfoDialog?>(null) }

    LaunchedEffect(query) {
        delay(300)
        actions.onSearch(query.lowercase(Locale.getDefault()))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                title = {
                    if (searchVisible) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.search)) },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (searchVisible) {
                        IconButton(onClick = {
                            searchVisible = false
                            query = ""
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    }
                },
                actions = {
                    if (!searchVisible) {
                        IconButton(onClick = { searchVisible = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                        IconButton(onClick = {
                            val layout = if (state.layout == LibraryLayout.Grid) {
                                LibraryLayout.List
                            } else {
                                LibraryLayout.Grid
                            }
                            actions.onLayoutChange(layout)
                        }) {
                            Icon(
                                painter = painterResource(
                                    if (state.layout == LibraryLayout.Grid) {
                                        R.drawable.ic_library_list
                                    } else {
                                        R.drawable.ic_library_grid
                                    },
                                ),
                                contentDescription = stringResource(R.string.pref_apps_view),
                            )
                        }
                        IconButton(onClick = { sortVisible = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sort),
                                contentDescription = stringResource(R.string.pref_app_sort_title),
                            )
                        }
                        Box {
                            IconButton(onClick = { overflowVisible = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.more),
                                )
                            }
                            LibraryOverflowMenu(
                                expanded = overflowVisible,
                                onDismiss = { overflowVisible = false },
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
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions::onInstall) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.install),
                )
            }
        },
    ) { padding ->
        LibraryContent(
            state = state,
            modifier = Modifier.padding(padding),
            onOpenApp = actions::onOpenApp,
            onOpenActions = { appActions = it },
        )
    }

    if (sortVisible) {
        LibrarySortDialog(
            sortVariant = state.sortVariant,
            onDismiss = { sortVisible = false },
            onSelect = { index ->
                sortVisible = false
                actions.onSort(index)
            },
        )
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
private fun LibraryContent(
    state: LibraryUiState,
    modifier: Modifier,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = "Loading apps" },
            )

            state.apps.isEmpty() -> Text(
                text = if (state.appliedFilter.isEmpty()) {
                    stringResource(R.string.no_data_for_display)
                } else {
                    stringResource(R.string.msg_no_matches, state.appliedFilter)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.layout == LibraryLayout.Grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 90.dp),
                contentPadding = PaddingValues(bottom = 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.apps, key = { it.id }) { app ->
                    LibraryGridItem(app, onOpenApp, onOpenActions)
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.apps, key = { it.id }) { app ->
                    LibraryListItem(app, onOpenApp, onOpenActions)
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = 48)
            Text(
                text = app.title,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            )
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app = app, size = 40)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = app.author,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.version,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        IconButton(onClick = { onOpenActions(app) }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.more),
            )
        }
    }
}

@Composable
private fun AppIcon(app: LibraryAppUiItem, size: Int) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.dp.roundToPx() }
    val bitmap = remember(app.iconPath, sizePx) {
        val source = app.iconPath?.let(BitmapFactory::decodeFile)
            ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(sizePx, sizePx)
        source?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size.dp),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun LibraryOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onProfiles: () -> Unit,
    onHelp: () -> Unit,
    onCrashReports: () -> Unit,
    onSaveLog: () -> Unit,
    onExit: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun closeThen(action: () -> Unit) {
            onDismiss()
            action()
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { closeThen(onAbout) })
        DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = { closeThen(onSettings) })
        DropdownMenuItem(text = { Text(stringResource(R.string.profiles)) }, onClick = { closeThen(onProfiles) })
        DropdownMenuItem(text = { Text(stringResource(R.string.help)) }, onClick = { closeThen(onHelp) })
        DropdownMenuItem(text = { Text(stringResource(R.string.crash_reports)) }, onClick = { closeThen(onCrashReports) })
        DropdownMenuItem(text = { Text(stringResource(R.string.save_log)) }, onClick = { closeThen(onSaveLog) })
        DropdownMenuItem(text = { Text(stringResource(R.string.exit)) }, onClick = { closeThen(onExit) })
    }
}

@Composable
private fun LibrarySortDialog(
    sortVariant: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val entries = stringArrayResource(R.array.pref_app_sort_entries)
    val selected = sortVariant and 0x7fffffff
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_app_sort_title)) },
        text = {
            Column {
                entries.forEachIndexed { index, entry ->
                    TextButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (index == selected) {
                                val arrow = if (sortVariant >= 0) "↓" else "↑"
                                "$entry  $arrow"
                            } else {
                                entry
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
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
    TextButton(
        onClick = {
            onDismiss()
            action()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
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
                append('\n')
                append(stringResource(R.string.about_copyright))
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
            message = AnnotatedString.fromHtml(
                context.assets.open("licenses.html").bufferedReader().use { it.readText() },
            )
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
