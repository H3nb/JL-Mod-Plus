from pathlib import Path


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


def replace_block(path, start, end, new_block):
    text = read(path)
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{path}: start marker missing: {start!r}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{path}: end marker missing: {end!r}")
    write(path, text[:start_index] + new_block + text[end_index:])


BRIDGE = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
COLLECTIONS = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionsUi.kt"
FRAGMENT = "app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java"
MAIN = "app/src/main/java/ru/playsoftware/j2meloader/MainActivity.java"
INSTALLER = "app/src/main/java/ru/woesss/j2me/installer/InstallerDialog.java"
VIEWMODEL = "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryViewModel.kt"

# ---------------------------------------------------------------------------
# Collections overview + nested collection chrome
# ---------------------------------------------------------------------------
for anchor, addition in [
    ("import androidx.compose.runtime.setValue\n", "import androidx.compose.runtime.setValue\nimport androidx.compose.runtime.snapshotFlow\n"),
    ("import androidx.compose.ui.Alignment\n", "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.draw.clipToBounds\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.input.nestedscroll.NestedScrollConnection\nimport androidx.compose.ui.input.nestedscroll.NestedScrollSource\nimport androidx.compose.ui.input.nestedscroll.nestedScroll\nimport androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.platform.LocalDensity\n"),
    ("import androidx.compose.ui.Modifier\n", ""),
    ("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.IntOffset\nimport androidx.compose.ui.unit.dp\nimport kotlin.math.roundToInt\nimport kotlinx.coroutines.flow.collectLatest\n"),
]:
    if addition:
        replace_once(COLLECTIONS, anchor, addition)
    else:
        # The Modifier import was intentionally folded into the Alignment anchor above.
        text = read(COLLECTIONS)
        if text.count(anchor) != 1:
            raise RuntimeError(f"{COLLECTIONS}: Modifier import count mismatch")
        write(COLLECTIONS, text.replace(anchor, "", 1))

collections_destination = r'''/** READY Collections destination. Collections overview and member browsing share Library scroll chrome. */
@Composable
internal fun LibraryCollectionsDestination(
    host: LibraryCollectionsHost,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onOpenActions: (LibraryAppUiItem, Long) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
) {
    val state by host.collectionsStore().state.collectAsState()
    if (!state.ready) {
        onNavigationVisibilityChanged(true)
        LibraryCollectionsDestination(scaffoldPadding)
        return
    }

    val members = state.members
    val openCollection = members?.let { current ->
        state.collections.firstOrNull { it.id == current.collectionId }
    }
    if (members != null && openCollection != null) {
        LibraryCollectionBrowser(
            collection = openCollection,
            members = members.members,
            allApps = state.allApps,
            libraryState = libraryState,
            scaffoldPadding = scaffoldPadding,
            onBack = {
                onNavigationVisibilityChanged(true)
                host.onDismissCollectionMembers()
            },
            onOpenApp = host::onOpenApp,
            onOpenActions = { app -> onOpenActions(app, openCollection.id) },
            onRemove = { appId -> host.onRemoveAppFromCollection(appId, openCollection.id) },
            onSetMembership = { appId, included ->
                if (included) {
                    host.onAddAppToCollection(appId, openCollection.id)
                } else {
                    host.onRemoveAppFromCollection(appId, openCollection.id)
                }
            },
            onSort = host::onSort,
            onNavigationVisibilityChanged = onNavigationVisibilityChanged,
        )
        return
    }

    var createDialog by rememberSaveable { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val headerHeightPx = remember { mutableStateOf(0) }
    val headerOffsetPx = remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val hideDistancePx = with(density) { 10.dp.toPx() }
    val revealDistancePx = with(density) { 18.dp.toPx() }
    val chromeHysteresis = remember(hideDistancePx, revealDistancePx) {
        LibraryChromeScrollHysteresis(hideDistancePx, revealDistancePx)
    }

    LaunchedEffect(state.collections) {
        headerOffsetPx.value = 0f
        chromeHysteresis.reset()
        onNavigationVisibilityChanged(true)
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.canScrollForward || listState.canScrollBackward,
            )
        }.collectLatest { (index, offset, canScroll) ->
            if (!canScroll || (index == 0 && offset == 0)) {
                headerOffsetPx.value = 0f
                chromeHysteresis.reset()
                onNavigationVisibilityChanged(true)
            }
        }
    }

    val scrollConnection = remember(chromeHysteresis, onNavigationVisibilityChanged) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val height = headerHeightPx.value
                if (delta == 0f || height <= 0) return Offset.Zero
                val canScroll = listState.canScrollForward || listState.canScrollBackward
                if (!canScroll) {
                    if (headerOffsetPx.value != 0f || !chromeHysteresis.chromeVisible) {
                        headerOffsetPx.value = 0f
                        chromeHysteresis.reset()
                        onNavigationVisibilityChanged(true)
                    }
                    return Offset.Zero
                }
                val fullyHidden = headerOffsetPx.value <= -height.toFloat() + 0.5f
                var visibilityChange = chromeHysteresis.onScrollDelta(delta)
                val shouldMoveHeader =
                    delta < 0f || !fullyHidden || chromeHysteresis.chromeVisible || visibilityChange == true
                if (shouldMoveHeader) {
                    headerOffsetPx.value =
                        (headerOffsetPx.value + delta).coerceIn(-height.toFloat(), 0f)
                }
                if (delta > 0f && headerOffsetPx.value >= -0.5f && !chromeHysteresis.chromeVisible) {
                    visibilityChange = chromeHysteresis.revealNow()
                }
                visibilityChange?.let(onNavigationVisibilityChanged)
                return Offset.Zero
            }
        }
    }

    val renderHeader: @Composable (Modifier, Boolean) -> Unit = { modifier, interactive ->
        Row(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_destination_collections),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                enabled = interactive,
                onClick = { createDialog = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_collection_new))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .clipToBounds()
            .nestedScroll(scrollConnection),
    ) {
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

            if (state.collections.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_collections),
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.library_collections_empty_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = stringResource(R.string.library_collection_ready_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                items(state.collections, key = { it.id }) { collection ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = collection.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.library_collection_member_count,
                                    collection.appCount,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_collections),
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { actionsTarget = collection }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { host.onOpenCollection(collection.id) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, headerOffsetPx.value.roundToInt()) }
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { headerHeightPx.value = it.height },
        ) {
            renderHeader(Modifier, true)
        }
    }

    if (createDialog) {
        CollectionNameDialog(
            title = stringResource(R.string.library_collection_new),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { createDialog = false },
            onConfirm = { name ->
                createDialog = false
                host.onCreateCollection(name)
            },
        )
    }

    actionsTarget?.let { collection ->
        CollectionActionsDialog(
            collection = collection,
            onDismiss = { actionsTarget = null },
            onRename = {
                actionsTarget = null
                renameTarget = collection
            },
            onDelete = {
                actionsTarget = null
                deleteTarget = collection
            },
        )
    }

    renameTarget?.let { collection ->
        CollectionNameDialog(
            title = stringResource(R.string.action_context_rename),
            initialName = collection.name,
            confirmLabel = stringResource(R.string.action_context_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                host.onRenameCollection(collection.id, name)
            },
        )
    }

    deleteTarget?.let { collection ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(collection.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    host.onDeleteCollection(collection.id)
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
}

'''
replace_block(
    COLLECTIONS,
    "/** READY Collections destination. Opening a collection reuses the main Library browser. */",
    "/** Collection dialogs live at LibraryScreen scope so Add-to-Collection works from any app browser. */",
    collections_destination,
)

# clearAndSetSemantics/background/offset imports used by the replacement.
replace_once(COLLECTIONS, "import androidx.compose.foundation.clickable\n", "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n")
replace_once(COLLECTIONS, "import androidx.compose.foundation.layout.only\n", "import androidx.compose.foundation.layout.offset\nimport androidx.compose.foundation.layout.only\n")
replace_once(COLLECTIONS, "import androidx.compose.ui.res.painterResource\n", "import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.semantics.clearAndSetSemantics\n")

# ---------------------------------------------------------------------------
# Library App List description layout, collection nav wiring, Options import,
# and context-menu order.
# ---------------------------------------------------------------------------
replace_once(BRIDGE, "    fun onInstall()\n    fun onOpenApp(appId: Int)\n", "    fun onInstall()\n    fun onImportAppBundle() = Unit\n    fun onOpenApp(appId: Int)\n")
replace_once(
    BRIDGE,
    "                            onOpenActions = { app, collectionId ->\n                                appActions = app\n                                appActionsCollectionId = collectionId\n                            },\n                        )\n",
    "                            onOpenActions = { app, collectionId ->\n                                appActions = app\n                                appActionsCollectionId = collectionId\n                            },\n                            onNavigationVisibilityChanged = { visible ->\n                                if (!isLandscape) showNavigationBar = visible\n                            },\n                        )\n",
)
replace_once(
    BRIDGE,
    "                        onGridSpacingChange = actions::onGridSpacingChange,\n                        onAbout = { infoDialog = LibraryInfoDialog.About },\n",
    "                        onGridSpacingChange = actions::onGridSpacingChange,\n                        onImportAppBundle = actions::onImportAppBundle,\n                        onAbout = { infoDialog = LibraryInfoDialog.About },\n",
)
replace_once(
    BRIDGE,
    "    onGridSpacingChange: (LibraryGridSpacing) -> Unit,\n    onAbout: () -> Unit,\n",
    "    onGridSpacingChange: (LibraryGridSpacing) -> Unit,\n    onImportAppBundle: () -> Unit,\n    onAbout: () -> Unit,\n",
)
replace_once(
    BRIDGE,
    "                        if (state.layout == LibraryLayout.Grid) {\n",
    "                        LibraryActionRow(\n                            label = R.string.library_action_import_bundle,\n                            summary = R.string.library_action_import_bundle_summary,\n                            icon = R.drawable.ic_upload_file,\n                            action = onImportAppBundle,\n                        )\n                        if (state.layout == LibraryLayout.Grid) {\n",
)

list_and_description = r'''@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryListItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onFavorite: (Int, Boolean) -> Unit,
    favoriteEnabled: Boolean,
    iconRatio: LibraryIconRatio,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
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
            }
            Spacer(Modifier.width(6.dp))
            if (favoriteEnabled) {
                LibraryFavoriteButton(app, onFavorite)
            } else {
                LibraryFavoritePlaceholder(app.id)
            }
        }
        if (app.description.isNotBlank()) {
            Box(modifier = Modifier.padding(start = 80.dp, end = 16.dp, bottom = 10.dp)) {
                LibraryDescription(app.description, app.id)
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
                textAlign = TextAlign.Justify,
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


'''
replace_block(
    BRIDGE,
    "@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun LibraryListItem(",
    "@Composable\nprivate fun LibraryFavoriteButton(",
    list_and_description,
)

app_actions = r'''@Composable
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
                            icon = R.drawable.ic_remove_circle,
                            onDismiss = onDismiss,
                            action = onRemoveFromCollection,
                        )
                    }
                }
                if (onShareApp != null || onExportAppBundle != null) {
                    item { DialogActionDivider() }
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
                    item { DialogActionDivider() }
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
private fun DialogActionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
    )
}

'''
replace_block(
    BRIDGE,
    "@Composable\ninternal fun AppActionsDialog(",
    "@Composable\nprivate fun DialogAction(",
    app_actions,
)

# ---------------------------------------------------------------------------
# Global Import App Bundle picker and durable MainActivity request kind.
# ---------------------------------------------------------------------------
replace_once(
    FRAGMENT,
    "\tprivate int pendingIconUiId = NO_UI_ID;\n",
    "\tprivate final ActivityResultLauncher<String[]> importBundleLauncher = registerForActivityResult(\n"
    "\t\t\tnew ActivityResultContracts.OpenDocument(),\n"
    "\t\t\tthis::onImportBundlePicked);\n\n"
    "\tprivate int pendingIconUiId = NO_UI_ID;\n",
)
replace_once(
    FRAGMENT,
    "\t\t\t@Override\n\t\t\tpublic void onOpenApp(int appId) {\n",
    "\t\t\t@Override\n"
    "\t\t\tpublic void onImportAppBundle() {\n"
    "\t\t\t\timportBundleLauncher.launch(new String[]{\n"
    "\t\t\t\t\t\t\"application/zip\",\n"
    "\t\t\t\t\t\t\"application/x-zip-compressed\",\n"
    "\t\t\t\t\t\t\"application/octet-stream\"\n"
    "\t\t\t\t});\n"
    "\t\t\t}\n\n"
    "\t\t\t@Override\n"
    "\t\t\tpublic void onOpenApp(int appId) {\n",
)
replace_once(
    FRAGMENT,
    "\tprivate void onIconPicked(Uri uri) {\n",
    "\tprivate void onImportBundlePicked(Uri uri) {\n"
    "\t\tif (uri == null) return;\n"
    "\t\ttry {\n"
    "\t\t\trequireContext().getContentResolver().takePersistableUriPermission(\n"
    "\t\t\t\t\turi, Intent.FLAG_GRANT_READ_URI_PERMISSION);\n"
    "\t\t} catch (SecurityException ignored) {\n"
    "\t\t\t// Some document providers expose only the active transient read grant.\n"
    "\t\t}\n"
    "\t\tActivity activity = requireActivity();\n"
    "\t\tif (activity instanceof MainActivity) {\n"
    "\t\t\t((MainActivity) activity).requestBundleInstaller(uri);\n"
    "\t\t\treturn;\n"
    "\t\t}\n"
    "\t\tthrow new IllegalStateException(\"AppsListFragment requires MainActivity host\");\n"
    "\t}\n\n"
    "\tprivate void onIconPicked(Uri uri) {\n",
)

replace_once(MAIN, "\tprivate static final String STATE_PENDING_INSTALLER_URIS = \"MainActivity.pendingInstallerUris\";\n", "\tprivate static final String STATE_PENDING_INSTALLER_URIS = \"MainActivity.pendingInstallerUris\";\n\tprivate static final String STATE_PENDING_INSTALLER_BUNDLES = \"MainActivity.pendingInstallerBundles\";\n")
replace_once(
    MAIN,
    "\t\tfinal Uri uri;\n\n\t\tPendingInstallerRequest(String id, Uri uri) {\n\t\t\tthis.id = id;\n\t\t\tthis.uri = uri;\n\t\t}\n",
    "\t\tfinal Uri uri;\n"
    "\t\tfinal boolean bundle;\n\n"
    "\t\tPendingInstallerRequest(String id, Uri uri, boolean bundle) {\n"
    "\t\t\tthis.id = id;\n"
    "\t\t\tthis.uri = uri;\n"
    "\t\t\tthis.bundle = bundle;\n"
    "\t\t}\n",
)
replace_once(
    MAIN,
    "\t\tArrayList<String> uris = new ArrayList<>(pendingInstallerRequests.size());\n\t\tfor (PendingInstallerRequest request : pendingInstallerRequests) {\n\t\t\tids.add(request.id);\n\t\t\turis.add(request.uri.toString());\n\t\t}\n\t\toutState.putStringArrayList(STATE_PENDING_INSTALLER_IDS, ids);\n\t\toutState.putStringArrayList(STATE_PENDING_INSTALLER_URIS, uris);\n",
    "\t\tArrayList<String> uris = new ArrayList<>(pendingInstallerRequests.size());\n"
    "\t\tboolean[] bundles = new boolean[pendingInstallerRequests.size()];\n"
    "\t\tint requestIndex = 0;\n"
    "\t\tfor (PendingInstallerRequest request : pendingInstallerRequests) {\n"
    "\t\t\tids.add(request.id);\n"
    "\t\t\turis.add(request.uri.toString());\n"
    "\t\t\tbundles[requestIndex++] = request.bundle;\n"
    "\t\t}\n"
    "\t\toutState.putStringArrayList(STATE_PENDING_INSTALLER_IDS, ids);\n"
    "\t\toutState.putStringArrayList(STATE_PENDING_INSTALLER_URIS, uris);\n"
    "\t\toutState.putBooleanArray(STATE_PENDING_INSTALLER_BUNDLES, bundles);\n",
)
replace_once(
    MAIN,
    "\tpublic void requestInstaller(@Nullable Uri uri) {\n\t\tif (uri == null) return;\n\t\tpendingInstallerRequests.addLast(\n\t\t\t\tnew PendingInstallerRequest(UUID.randomUUID().toString(), uri));\n\t\tmaybeShowPendingInstaller();\n\t}\n",
    "\tpublic void requestInstaller(@Nullable Uri uri) {\n"
    "\t\tenqueueInstaller(uri, false);\n"
    "\t}\n\n"
    "\tpublic void requestBundleInstaller(@Nullable Uri uri) {\n"
    "\t\tenqueueInstaller(uri, true);\n"
    "\t}\n\n"
    "\tprivate void enqueueInstaller(@Nullable Uri uri, boolean bundle) {\n"
    "\t\tif (uri == null) return;\n"
    "\t\tpendingInstallerRequests.addLast(\n"
    "\t\t\t\tnew PendingInstallerRequest(UUID.randomUUID().toString(), uri, bundle));\n"
    "\t\tmaybeShowPendingInstaller();\n"
    "\t}\n",
)
replace_once(MAIN, "\t\t\tArrayList<String> uris = savedInstanceState.getStringArrayList(STATE_PENDING_INSTALLER_URIS);\n", "\t\t\tArrayList<String> uris = savedInstanceState.getStringArrayList(STATE_PENDING_INSTALLER_URIS);\n\t\t\tboolean[] bundles = savedInstanceState.getBooleanArray(STATE_PENDING_INSTALLER_BUNDLES);\n")
replace_once(
    MAIN,
    "\t\t\t\t\tpendingInstallerRequests.addLast(\n\t\t\t\t\t\t\t\tnew PendingInstallerRequest(id, Uri.parse(value)));\n",
    "\t\t\t\t\tboolean bundle = bundles != null && i < bundles.length && bundles[i];\n"
    "\t\t\t\t\tpendingInstallerRequests.addLast(\n"
    "\t\t\t\t\t\t\t\tnew PendingInstallerRequest(id, Uri.parse(value), bundle));\n",
)
replace_once(
    MAIN,
    "\t\tInstallerDialog.newExternalRequest(request.id, request.uri)\n\t\t\t\t.show(getSupportFragmentManager(), INSTALLER_TAG);\n",
    "\t\tInstallerDialog installer = request.bundle\n"
    "\t\t\t\t? InstallerDialog.newExternalBundleRequest(request.id, request.uri)\n"
    "\t\t\t\t: InstallerDialog.newExternalRequest(request.id, request.uri);\n"
    "\t\tinstaller.show(getSupportFragmentManager(), INSTALLER_TAG);\n",
)
replace_once(
    MAIN,
    "\t\tif (completed != null && installerStateSnapshotExists) {\n\t\t\trecordAcknowledgedInstallerRequest(completed.id);\n\t\t}\n",
    "\t\tif (completed != null && installerStateSnapshotExists) {\n"
    "\t\t\trecordAcknowledgedInstallerRequest(completed.id);\n"
    "\t\t}\n"
    "\t\tif (completed != null && completed.bundle) {\n"
    "\t\t\ttry {\n"
    "\t\t\t\tgetContentResolver().releasePersistableUriPermission(\n"
    "\t\t\t\t\t\tcompleted.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);\n"
    "\t\t\t} catch (SecurityException ignored) {\n"
    "\t\t\t\t// The provider may have supplied only a transient grant.\n"
    "\t\t\t}\n"
    "\t\t}\n",
)

# ---------------------------------------------------------------------------
# Generation-safe bundle restore in ViewModel.
# ---------------------------------------------------------------------------
replace_once(
    VIEWMODEL,
    "    fun setFavorite(appId: Long, favorite: Boolean, callback: MutationCallback<Unit>) {\n",
    "    fun restoreImportedBundle(\n"
    "        appId: Long,\n"
    "        prepared: LibraryAppBundleImporter.PreparedImport,\n"
    "        callback: MutationCallback<Unit>,\n"
    "    ) {\n"
    "        val generation = readyGeneration()\n"
    "        val app = try {\n"
    "            generation?.let { repository.currentApp(it, appId) }\n"
    "        } catch (_: IllegalStateException) {\n"
    "            null\n"
    "        }\n"
    "        if (generation == null || app == null) {\n"
    "            callback.complete(null, IllegalStateException(\"Library app is not available\"))\n"
    "            return\n"
    "        }\n"
    "        launchMutation(callback) {\n"
    "            val result = withContext(Dispatchers.IO) {\n"
    "                acquireGenerationLease(generation.generation, generation.emulatorDir).use {\n"
    "                    val current = repository.currentApp(generation, app.id)\n"
    "                    check(current?.storageKey == app.storageKey) {\n"
    "                        \"Library import target changed before restore\"\n"
    "                    }\n"
    "                    LibraryAppBundleImporter.restore(prepared, generation.emulatorDir, app.storageKey)\n"
    "                }\n"
    "            }\n"
    "            result.iconRevision?.let { revision ->\n"
    "                repository.setIconRevision(\n"
    "                    generation,\n"
    "                    app.id,\n"
    "                    distinctIconRevision(revision, app.iconRevision),\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    fun setFavorite(appId: Long, favorite: Boolean, callback: MutationCallback<Unit>) {\n",
)

# ---------------------------------------------------------------------------
# InstallerDialog: bundle preparation -> normal AppInstaller -> state restore.
# ---------------------------------------------------------------------------
replace_once(INSTALLER, "import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;\n", "import ru.playsoftware.j2meloader.librarydb.LibraryAppBundleImporter;\nimport ru.playsoftware.j2meloader.librarydb.LibraryViewModel;\n")
replace_once(INSTALLER, "\tprivate static final String ARG_REQUEST_ID = \"InstallerDialog.requestId\";\n", "\tprivate static final String ARG_REQUEST_ID = \"InstallerDialog.requestId\";\n\tprivate static final String ARG_BUNDLE = \"InstallerDialog.bundle\";\n")
replace_once(INSTALLER, "\tprivate Runnable primaryAction;\n\tprivate boolean restoredInstance;\n", "\tprivate Runnable primaryAction;\n\tprivate LibraryAppBundleImporter.PreparedImport bundleImport;\n\tprivate boolean restoredInstance;\n")
replace_once(
    INSTALLER,
    "\tpublic static InstallerDialog newExternalRequest(@Nullable String requestId, Uri uri) {\n\t\tInstallerDialog fragment = new InstallerDialog();\n\t\tBundle args = new Bundle();\n\t\targs.putParcelable(ARG_URI, uri);\n\t\tif (requestId != null) args.putString(ARG_REQUEST_ID, requestId);\n\t\tfragment.setArguments(args);\n\t\tfragment.setCancelable(false);\n\t\treturn fragment;\n\t}\n",
    "\tpublic static InstallerDialog newExternalRequest(@Nullable String requestId, Uri uri) {\n"
    "\t\treturn newExternalRequest(requestId, uri, false);\n"
    "\t}\n\n"
    "\tpublic static InstallerDialog newExternalBundleRequest(@Nullable String requestId, Uri uri) {\n"
    "\t\treturn newExternalRequest(requestId, uri, true);\n"
    "\t}\n\n"
    "\tprivate static InstallerDialog newExternalRequest(@Nullable String requestId, Uri uri, boolean bundle) {\n"
    "\t\tInstallerDialog fragment = new InstallerDialog();\n"
    "\t\tBundle args = new Bundle();\n"
    "\t\targs.putParcelable(ARG_URI, uri);\n"
    "\t\targs.putBoolean(ARG_BUNDLE, bundle);\n"
    "\t\tif (requestId != null) args.putString(ARG_REQUEST_ID, requestId);\n"
    "\t\tfragment.setArguments(args);\n"
    "\t\tfragment.setCancelable(false);\n"
    "\t\treturn fragment;\n"
    "\t}\n",
)
replace_once(
    INSTALLER,
    "\t\tif (uri != null) {\n\t\t\tinstallApp(null, uri);\n\t\t\treturn;\n\t\t}\n",
    "\t\tif (uri != null) {\n"
    "\t\t\tif (isBundleRequest()) {\n"
    "\t\t\t\tprepareBundle(uri);\n"
    "\t\t\t} else {\n"
    "\t\t\t\tinstallApp(null, uri);\n"
    "\t\t\t}\n"
    "\t\t\treturn;\n"
    "\t\t}\n",
)
replace_once(
    INSTALLER,
    "\tprivate void installApp(File jar, Uri uri) {\n",
    "\tprivate void prepareBundle(Uri uri) {\n"
    "\t\tif (composeController != null) {\n"
    "\t\t\tcomposeController.showLoading(installerTitle, getString(R.string.library_import_preparing));\n"
    "\t\t}\n"
    "\t\tDisposable disposable = Single.<LibraryAppBundleImporter.PreparedImport>create(emitter ->\n"
    "\t\t\t\temitter.onSuccess(LibraryAppBundleImporter.prepare(\n"
    "\t\t\t\t\trequireContext().getApplicationContext(), uri)))\n"
    "\t\t\t\t.subscribeOn(Schedulers.io())\n"
    "\t\t\t\t.observeOn(AndroidSchedulers.mainThread())\n"
    "\t\t\t\t.subscribe(prepared -> {\n"
    "\t\t\t\tbundleImport = prepared;\n"
    "\t\t\t\tinstallApp(prepared.getJarFile(), null);\n"
    "\t\t\t}, this::onError);\n"
    "\t\tcompositeDisposable.add(disposable);\n"
    "\t}\n\n"
    "\tprivate void installApp(File jar, Uri uri) {\n",
)
replace_once(
    INSTALLER,
    "\t\tif (status == AppInstaller.STATUS_SUCCESS) {\n\t\t\t// The filesystem + Room commit is the durable consumption point. A process death while the\n\t\t\t// success screen is visible must not replay the same external install request.\n\t\t\tacknowledgeExternalRequest();\n",
    "\t\tif (status == AppInstaller.STATUS_SUCCESS) {\n"
    "\t\t\tif (isBundleRequest()) {\n"
    "\t\t\t\trestoreBundleToInstalled();\n"
    "\t\t\t\treturn;\n"
    "\t\t\t}\n"
    "\t\t\t// The filesystem + Room commit is the durable consumption point. A process death while the\n"
    "\t\t\t// success screen is visible must not replay the same external install request.\n"
    "\t\t\tacknowledgeExternalRequest();\n",
)
replace_once(
    INSTALLER,
    "\t\t\tcase AppInstaller.STATUS_SAME -> {\n\t\t\t\tlaunchExistingApp(true);\n\t\t\t\treturn;\n\t\t\t}\n",
    "\t\t\tcase AppInstaller.STATUS_SAME -> {\n"
    "\t\t\t\tif (isBundleRequest()) {\n"
    "\t\t\t\t\tcurrentTitle = nd.getName();\n"
    "\t\t\t\t\tshowBundleRestoreConfirmation();\n"
    "\t\t\t\t} else {\n"
    "\t\t\t\t\tlaunchExistingApp(true);\n"
    "\t\t\t\t}\n"
    "\t\t\t\treturn;\n"
    "\t\t\t}\n",
)
replace_once(
    INSTALLER,
    "\tprivate void closeInstaller() {\n",
    "\tprivate boolean isBundleRequest() {\n"
    "\t\tBundle args = getArguments();\n"
    "\t\treturn args != null && args.getBoolean(ARG_BUNDLE, false);\n"
    "\t}\n\n"
    "\tprivate void showBundleRestoreConfirmation() {\n"
    "\t\tif (composeController == null || installer == null) return;\n"
    "\t\tprimaryAction = this::restoreBundleToInstalled;\n"
    "\t\tcomposeController.showConfirmation(\n"
    "\t\t\t\tcurrentTitle,\n"
    "\t\t\t\tgetString(R.string.library_import_restore_existing),\n"
    "\t\t\t\tgetString(R.string.library_action_import_bundle),\n"
    "\t\t\t\tgetString(android.R.string.cancel),\n"
    "\t\t\t\tnull,\n"
    "\t\t\t\tinstaller.getIconPath());\n"
    "\t}\n\n"
    "\tprivate void restoreBundleToInstalled() {\n"
    "\t\tif (installer == null || bundleImport == null || composeController == null || !isAdded()) return;\n"
    "\t\tlong installedId = installer.getInstalledId();\n"
    "\t\tif (installedId < 0L) {\n"
    "\t\t\tonBundleRestoreError(new IllegalStateException(\"Imported app identity is unavailable\"));\n"
    "\t\t\treturn;\n"
    "\t\t}\n"
    "\t\tcomposeController.showLoading(currentTitle, getString(R.string.library_import_restoring));\n"
    "\t\tlibraryViewModel.restoreImportedBundle(installedId, bundleImport, (ignored, error) -> {\n"
    "\t\t\tif (!isAdded() || composeController == null) return;\n"
    "\t\t\tif (error != null) {\n"
    "\t\t\t\tonBundleRestoreError(error);\n"
    "\t\t\t\treturn;\n"
    "\t\t\t}\n"
    "\t\t\tacknowledgeExternalRequest();\n"
    "\t\t\tcleanupBundleImport();\n"
    "\t\t\tcomposeController.showSuccess(\n"
    "\t\t\t\t\tcurrentTitle,\n"
    "\t\t\t\t\tgetString(R.string.library_import_done),\n"
    "\t\t\t\t\tgetString(R.string.START_CMD),\n"
    "\t\t\t\t\tgetString(R.string.close),\n"
    "\t\t\t\t\tinstaller.getIconPath());\n"
    "\t\t});\n"
    "\t}\n\n"
    "\tprivate void onBundleRestoreError(Throwable error) {\n"
    "\t\tLog.e(\"Installer\", \"Bundle restore failed\", error);\n"
    "\t\tif (composeController == null) return;\n"
    "\t\tprimaryAction = this::restoreBundleToInstalled;\n"
    "\t\tcomposeController.showConfirmation(\n"
    "\t\t\t\tcurrentTitle,\n"
    "\t\t\t\tgetString(R.string.library_import_restore_failed),\n"
    "\t\t\t\tgetString(R.string.library_retry),\n"
    "\t\t\t\tgetString(R.string.close),\n"
    "\t\t\t\tnull,\n"
    "\t\t\t\tinstaller == null ? null : installer.getIconPath());\n"
    "\t}\n\n"
    "\tprivate void cleanupBundleImport() {\n"
    "\t\tLibraryAppBundleImporter.cleanup(bundleImport);\n"
    "\t\tbundleImport = null;\n"
    "\t}\n\n"
    "\tprivate void closeInstaller() {\n",
)
replace_once(
    INSTALLER,
    "\t\tif (installer != null) {\n\t\t\tinstaller.deleteTemp();\n\t\t\tinstaller.clearCache();\n\t\t}\n\t\tacknowledgeExternalRequest();\n\t\tif (isAdded()) dismiss();\n",
    "\t\tif (installer != null) {\n"
    "\t\t\tinstaller.deleteTemp();\n"
    "\t\t\tinstaller.clearCache();\n"
    "\t\t}\n"
    "\t\tcleanupBundleImport();\n"
    "\t\tacknowledgeExternalRequest();\n"
    "\t\tif (isAdded()) dismiss();\n",
)
replace_once(
    INSTALLER,
    "\t\tif (installer != null) {\n\t\t\tinstaller.clearCache();\n\t\t\tinstaller.deleteTemp();\n\t\t}\n\t\tacknowledgeExternalRequest();\n\t\tif (!isAdded()) return;\n",
    "\t\tif (installer != null) {\n"
    "\t\t\tinstaller.clearCache();\n"
    "\t\t\tinstaller.deleteTemp();\n"
    "\t\t}\n"
    "\t\tcleanupBundleImport();\n"
    "\t\tacknowledgeExternalRequest();\n"
    "\t\tif (!isAdded()) return;\n",
)

print("PR2 feedback source patch applied successfully")
