from pathlib import Path


def load(path: str) -> str:
    return Path(path).read_text()


def save(path: str, text: str) -> None:
    Path(path).write_text(text)


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"{label}: start marker missing")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise SystemExit(f"{label}: end marker missing")
    return text[:start_index] + replacement + text[end_index:]


bridge_path = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
bridge = load(bridge_path)
bridge = once(
    bridge,
    "    var metadataTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }\n    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }",
    "    var metadataTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }\n    var appActionsCollectionId by remember { mutableStateOf<Long?>(null) }\n    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }",
    "collection action context",
)
bridge = once(
    bridge,
    "    val collectionsHost = actions as? LibraryCollectionsHost\n\n    LaunchedEffect(destination) {",
    """    val collectionsHost = actions as? LibraryCollectionsHost

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

    LaunchedEffect(destination) {""",
    "metadata full page route",
)
bridge = once(
    bridge,
    "            appActions = null\n        }",
    "            appActions = null\n            appActionsCollectionId = null\n        }",
    "action context reset",
)
bridge = once(
    bridge,
    "                        onOpenActions = { appActions = it },",
    """                        onOpenActions = {
                            appActions = it
                            appActionsCollectionId = null
                        },""",
    "main app action context",
)
bridge = once(
    bridge,
    """                    LibraryDestination.Collections -> if (collectionsHost != null) {
                        LibraryCollectionsDestination(collectionsHost, padding)
                    } else {
                        LibraryCollectionsDestination(padding)
                    }""",
    """                    LibraryDestination.Collections -> if (collectionsHost != null) {
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
                    }""",
    "collection browser route",
)
bridge = once(
    bridge,
    "            onDismiss = { appActions = null },",
    """            onDismiss = {
                appActions = null
                appActionsCollectionId = null
            },""",
    "app action dismiss",
)
bridge = once(
    bridge,
    """            onAddToCollection = if (state.databaseControlsReady && collectionsHost != null) {
                { collectionsHost.onRequestAddToCollection(app.id) }
            } else {
                null
            },
            onShareApp = if (state.databaseControlsReady) {""",
    """            onAddToCollection = if (state.databaseControlsReady && collectionsHost != null) {
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
            onShareApp = if (state.databaseControlsReady) {""",
    "remove from collection action",
)
bridge = between(
    bridge,
    "    metadataTarget?.let { target ->\n",
    "    deleteTarget?.let { app ->\n",
    "",
    "remove metadata dialog",
)
bridge = once(
    bridge,
    "@Composable\nprivate fun LibraryAppsDestination(",
    "@Composable\ninternal fun LibraryAppsDestination(",
    "shared app browser visibility",
)
bridge = once(
    bridge,
    """    onRetry: () -> Unit,
    onFabVisibilityChanged: (Boolean) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.appliedFilter) }""",
    """    onRetry: () -> Unit,
    onFabVisibilityChanged: (Boolean) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    showQuickViews: Boolean = true,
    queryStateKey: Any? = Unit,
) {
    var query by rememberSaveable(queryStateKey) { mutableStateOf(state.appliedFilter) }""",
    "shared app browser params",
)
bridge = once(
    bridge,
    """            onQuickView = onQuickView,
            onSort = onSort,
            interactive = interactive,""",
    """            onQuickView = onQuickView,
            onSort = onSort,
            title = title,
            onBack = onBack,
            showQuickViews = showQuickViews,
            interactive = interactive,""",
    "header browser params",
)

header_start = "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun LibraryAppsHeader("
header_end = "\n@Composable\nprivate fun LibraryQuickFilter("
new_header = r'''@OptIn(ExperimentalMaterial3Api::class)
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
'''
bridge = between(bridge, header_start, header_end, new_header, "compact search/sort header")

list_start = "@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun LibraryListItem("
list_end = "\nprivate const val LIBRARY_DESCRIPTION_OVERFLOW_FALLBACK_LENGTH = 120"
new_list = r'''@OptIn(ExperimentalFoundationApi::class)
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
private fun LibraryDescription(descriptionValue: String, appId: Int) {
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
'''
bridge = between(bridge, list_start, list_end, new_list, "full-row list interactions")
bridge = bridge.replace("\nprivate const val LIBRARY_DESCRIPTION_OVERFLOW_FALLBACK_LENGTH = 120", "", 1)

favorite_start = "@Composable\nprivate fun LibraryFavoriteButton("
favorite_end = "\n@Composable\nprivate fun LibraryFavoritePlaceholder("
new_favorite = r'''@Composable
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
'''
bridge = between(bridge, favorite_start, favorite_end, new_favorite, "favorite animation")
bridge = once(
    bridge,
    "    onAddToCollection: (() -> Unit)? = null,\n    onShareApp: (() -> Unit)? = null,",
    "    onAddToCollection: (() -> Unit)? = null,\n    onRemoveFromCollection: (() -> Unit)? = null,\n    onShareApp: (() -> Unit)? = null,",
    "action signature membership removal",
)
bridge = once(
    bridge,
    """                if (onShareApp != null) {
                    item {""",
    """                if (onRemoveFromCollection != null) {
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
                    item {""",
    "action body membership removal",
)
save(bridge_path, bridge)

fragment_path = "app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java"
fragment = load(fragment_path)
fragment = once(
    fragment,
    " * Copyright 2019-2026 Yury Kharchenko\n *\n * Licensed under the Apache License",
    " * Copyright 2019-2026 Yury Kharchenko\n *\n * Modified by JL-Mod Plus contributors; original upstream attribution is retained.\n *\n * Licensed under the Apache License",
    "fragment attribution",
)
fragment = once(
    fragment,
    """\t\t\t@Override
\t\t\tpublic void onRemoveAppFromCollection(long appId, long collectionId) {
\t\t\t\tlibraryViewModel.setCollectionMembership(
\t\t\t\t\t\tcollectionId,
\t\t\t\t\t\tappId,""",
    """\t\t\t@Override
\t\t\tpublic void onRemoveAppFromCollection(int appId, long collectionId) {
\t\t\t\tLibraryAppRow app = findRow(appId);
\t\t\t\tif (app == null) return;
\t\t\t\tlibraryViewModel.setCollectionMembership(
\t\t\t\t\t\tcollectionId,
\t\t\t\t\t\tapp.getId(),""",
    "fragment membership removal",
)
old_load = """\tprivate void loadCollectionMembers(long collectionId) {
\t\tlibraryViewModel.getCollectionAppIds(collectionId, (appIds, error) -> {
\t\t\tif (error != null) {
\t\t\t\tshowError(error);
\t\t\t\treturn;
\t\t\t}
\t\t\tif (!isAdded() || appIds == null) return;
\t\t\tList<LibraryCollectionMemberUiItem> members = new ArrayList<>(appIds.size());
\t\t\tfor (Long appId : appIds) {
\t\t\t\tLibraryAppRow app = libraryViewModel.getApp(appId);
\t\t\t\tif (app == null) continue;
\t\t\t\tmembers.add(new LibraryCollectionMemberUiItem(
\t\t\t\t\t\tapp.getId(),
\t\t\t\t\t\tapp.getTitle(),
\t\t\t\t\t\tapp.getVendor(),
\t\t\t\t\t\tapp.getVersion()));
\t\t\t}
\t\t\tcollectionsUiStore.showMembers(collectionId, members);
\t\t});
\t}
"""
new_load = """\tprivate void loadCollectionMembers(long collectionId) {
\t\tlibraryViewModel.getCollectionAppIds(collectionId, (appIds, error) -> {
\t\t\tif (error != null) {
\t\t\t\tshowError(error);
\t\t\t\treturn;
\t\t\t}
\t\t\tif (!isAdded() || appIds == null) return;
\t\t\tList<LibraryAppRow> rows = libraryViewModel.getApps(appIds);
\t\t\tList<LibraryAppUiItem> members = new ArrayList<>(rows.size());
\t\t\tfor (LibraryAppRow row : rows) {
\t\t\t\tmembers.add(toLibraryUiItem(row));
\t\t\t}
\t\t\tcollectionsUiStore.showMembers(collectionId, members);
\t\t});
\t}
"""
fragment = once(fragment, old_load, new_load, "bulk collection member lookup")
old_mapping = """\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
\t\tfor (LibraryAppRow row : state.getApps()) {
\t\t\tint uiId = uiIdFor(row.getId());
\t\t\trowsByUiId.put(uiId, row);
\t\t\tString iconPath = row.getIconRevision() == 0L
\t\t\t\t\t? null
\t\t\t\t\t: new File(appPath(row) + Config.MIDLET_ICON_FILE).getAbsolutePath();
\t\t\tuiItems.add(new LibraryAppUiItem(
\t\t\t\t\tuiId,
\t\t\t\t\trow.getTitle(),
\t\t\t\t\trow.getVendor(),
\t\t\t\t\trow.getVersion(),
\t\t\t\t\ticonPath,
\t\t\t\t\ttrue,
\t\t\t\t\trow.getDescription(),
\t\t\t\t\trow.getIconRevision(),
\t\t\t\t\trow.getFavorite(),
\t\t\t\t\trow.getSourceTitle(),
\t\t\t\t\trow.getSourceVendor(),
\t\t\t\t\trow.getSourceVersion(),
\t\t\t\t\trow.getSourceDescription(),
\t\t\t\t\trow.getPlayCount(),
\t\t\t\t\trow.getTotalPlayTimeMs()));
\t\t}
"""
new_mapping = """\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
\t\tfor (LibraryAppRow row : state.getApps()) {
\t\t\tuiItems.add(toLibraryUiItem(row));
\t\t}
\t\tLong activeCollectionId = collectionsUiStore.activeCollectionId();
\t\tif (activeCollectionId != null) loadCollectionMembers(activeCollectionId);
"""
fragment = once(fragment, old_mapping, new_mapping, "shared UI item mapping")
marker = "\n\tprivate int uiIdFor(long databaseId) {"
helper = """
\tprivate LibraryAppUiItem toLibraryUiItem(LibraryAppRow row) {
\t\tint uiId = uiIdFor(row.getId());
\t\trowsByUiId.put(uiId, row);
\t\tString iconPath = row.getIconRevision() == 0L
\t\t\t\t? null
\t\t\t\t: new File(appPath(row) + Config.MIDLET_ICON_FILE).getAbsolutePath();
\t\treturn new LibraryAppUiItem(
\t\t\t\tuiId,
\t\t\t\trow.getTitle(),
\t\t\t\trow.getVendor(),
\t\t\t\trow.getVersion(),
\t\t\t\ticonPath,
\t\t\t\ttrue,
\t\t\t\trow.getDescription(),
\t\t\t\trow.getIconRevision(),
\t\t\t\trow.getFavorite(),
\t\t\t\trow.getSourceTitle(),
\t\t\t\trow.getSourceVendor(),
\t\t\t\trow.getSourceVersion(),
\t\t\t\trow.getSourceDescription(),
\t\t\t\trow.getPlayCount(),
\t\t\t\trow.getTotalPlayTimeMs());
\t}
"""
if marker not in fragment:
    raise SystemExit("UI item helper marker missing")
fragment = fragment.replace(marker, helper + marker, 1)
save(fragment_path, fragment)

repository_path = "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryRepository.kt"
repository = load(repository_path)
repository = once(
    repository,
    """    fun currentApp(expected: LibraryGenerationToken, appId: Long): LibraryAppRow? {
        val ready = requireReadyGeneration(expected)
        return ready.apps.firstOrNull { it.id == appId }
    }

    fun currentCollection""",
    """    fun currentApp(expected: LibraryGenerationToken, appId: Long): LibraryAppRow? {
        val ready = requireReadyGeneration(expected)
        return ready.apps.firstOrNull { it.id == appId }
    }

    fun currentApps(expected: LibraryGenerationToken, appIds: Set<Long>): List<LibraryAppRow> {
        if (appIds.isEmpty()) return emptyList()
        return requireReadyGeneration(expected).apps.filter { it.id in appIds }
    }

    fun currentCollection""",
    "repository bulk lookup",
)
save(repository_path, repository)

view_model_path = "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryViewModel.kt"
view_model = load(view_model_path)
view_model = once(
    view_model,
    """    fun getApp(expectedGeneration: Long, expectedWorkdir: File, appId: Long): LibraryAppRow? =
        repository.currentApp(token(expectedGeneration, expectedWorkdir), appId)

    fun storageKeys""",
    """    fun getApp(expectedGeneration: Long, expectedWorkdir: File, appId: Long): LibraryAppRow? =
        repository.currentApp(token(expectedGeneration, expectedWorkdir), appId)

    fun getApps(appIds: Set<Long>): List<LibraryAppRow> {
        val generation = readyGeneration() ?: return emptyList()
        return try {
            repository.currentApps(generation, appIds)
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    fun storageKeys""",
    "viewmodel bulk lookup",
)
old_reconcile = """    private suspend fun reconcilePlayStats(expected: LibraryGenerationToken) {
        playStatRefreshMutex.withLock {
            if (!repository.isReadyGeneration(expected)) return@withLock
            val application = getApplication<Application>()
            val records = withContext(Dispatchers.IO) {
                MidletSessionStatsHandoff.loadTerminalRecords(application).map { record ->
                    LibraryPlayStatRecord(
                        sessionId = record.sessionId,
                        workdirLocator = record.workdirLocator,
                        storageKey = record.storageKey,
                        reachedRunning = record.reachedRunning,
                        firstRunningWallTimeMillis = record.firstRunningWallTimeMillis,
                        accumulatedActiveMillis = record.accumulatedActiveMillis,
                    )
                }
            }
            if (records.isEmpty()) return@withLock
            val result = try {
                repository.reconcilePlayStats(expected, records)
            } catch (error: IllegalStateException) {
                if (!repository.isReadyGeneration(expected)) return@withLock
                throw error
            }
            withContext(Dispatchers.IO) {
                result.reconciledSessionIds.forEach { sessionId ->
                    MidletSessionStatsHandoff.markReconciled(application, sessionId)
                }
            }
        }
    }
"""
new_reconcile = """    private suspend fun reconcilePlayStats(expected: LibraryGenerationToken) {
        try {
            playStatRefreshMutex.withLock {
                if (!repository.isReadyGeneration(expected)) return@withLock
                val application = getApplication<Application>()
                val records = withContext(Dispatchers.IO) {
                    MidletSessionStatsHandoff.loadTerminalRecords(application).map { record ->
                        LibraryPlayStatRecord(
                            sessionId = record.sessionId,
                            workdirLocator = record.workdirLocator,
                            storageKey = record.storageKey,
                            reachedRunning = record.reachedRunning,
                            firstRunningWallTimeMillis = record.firstRunningWallTimeMillis,
                            accumulatedActiveMillis = record.accumulatedActiveMillis,
                        )
                    }
                }
                if (records.isEmpty()) return@withLock
                val result = try {
                    repository.reconcilePlayStats(expected, records)
                } catch (error: IllegalStateException) {
                    if (!repository.isReadyGeneration(expected)) return@withLock
                    throw error
                }
                withContext(Dispatchers.IO) {
                    result.reconciledSessionIds.forEach { sessionId ->
                        MidletSessionStatsHandoff.markReconciled(application, sessionId)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w(
                "LibraryViewModel",
                "Unable to reconcile play statistics; leaving session journals pending",
                error,
            )
        }
    }
"""
view_model = once(view_model, old_reconcile, new_reconcile, "play stat failure boundary")
save(view_model_path, view_model)

for path in (
    "app/src/main/java/javax/microedition/shell/MicroLoader.java",
    "app/src/main/java/ru/woesss/j2me/installer/AppInstaller.java",
):
    source = load(path)
    source = once(
        source,
        " *\n * Licensed under the Apache License",
        " *\n * Modified by JL-Mod Plus contributors; original upstream attribution is retained.\n *\n * Licensed under the Apache License",
        f"attribution {path}",
    )
    save(path, source)

test_path = "app/src/androidTest/java/ru/playsoftware/j2meloader/applist/LibraryComposeTest.kt"
test = load(test_path)
old_test = """    @Test
    fun searchIsLowercasedAndDebouncedForThreeHundredMilliseconds() {
        val actions = RecordingLibraryActions()
        composeRule.mainClock.autoAdvance = false
        setLibraryContent(actions = actions)
        composeRule.mainClock.advanceTimeBy(301)
        composeRule.waitForIdle()
        actions.searches.clear()

        composeRule.onNode(hasSetTextAction()).performTextInput("Demo")
        composeRule.mainClock.advanceTimeBy(299)
        composeRule.waitForIdle()
        assertTrue(actions.searches.isEmpty())

        composeRule.mainClock.advanceTimeBy(2)
        composeRule.waitForIdle()
        assertEquals(listOf("demo"), actions.searches)
    }
"""
new_test = """    @Test
    fun searchDispatchesCurrentTextWithoutArtificialDebounce() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)
        composeRule.waitForIdle()
        actions.searches.clear()

        composeRule.onNode(hasSetTextAction()).performTextInput("Demo")
        composeRule.waitForIdle()

        assertEquals(listOf("Demo"), actions.searches)
    }
"""
test = once(test, old_test, new_test, "stale search AndroidTest")
save(test_path, test)
