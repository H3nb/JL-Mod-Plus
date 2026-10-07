/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.GlassSystemBarScrim

/**
 * Collection-specific browser. It mirrors Library List/Grid chrome while collection management and
 * selection actions replace Favorites and quick views.
 */
@Composable
internal fun LibraryCollectionBrowser(
    collection: LibraryCollectionUiItem,
    members: List<LibraryAppUiItem>,
    allApps: List<LibraryAppUiItem>,
    allAppsPrepared: Boolean = true,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState = rememberLibraryViewportState(libraryState.libraryScope, collection.id),
    pickerViewport: LibraryViewportState = rememberLibraryViewportState(libraryState.libraryScope, collection.id),
    onBack: () -> Unit,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    selectionState: LibrarySelectionState = LibrarySelectionState(),
    onSelectionStateChanged: (LibrarySelectionState) -> Unit = {},
    manageApps: Boolean,
    onManageAppsChanged: (Boolean) -> Unit,
    onSetMembership: (Int, Boolean, CollectionMembershipResultCallback) -> Unit,
    onPrepareAppPicker: () -> Unit,
    onSort: (Int) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit = {},
    showBackButton: Boolean = true,
    handleSystemBack: Boolean = true,
    interactionActive: Boolean = true,
) {
    val currentOnNavigationVisibilityChanged by rememberUpdatedState(onNavigationVisibilityChanged)
    val publishNavigationVisibility: (Boolean) -> Unit = remember(viewportState) {
        { visible ->
            viewportState.chromeVisible = visible
            currentOnNavigationVisibilityChanged(visible)
        }
    }
    var pendingMemberships by remember(collection.id) {
        mutableStateOf<Map<Int, Boolean>>(emptyMap())
    }
    val committedMemberIds = remember(members) {
        members.mapTo(LinkedHashSet()) { it.id }
    }
    LaunchedEffect(committedMemberIds) {
        val unresolved = pendingMemberships.filter { (appId, desiredIncluded) ->
            (appId in committedMemberIds) != desiredIncluded
        }
        if (unresolved != pendingMemberships) pendingMemberships = unresolved
    }
    fun setManageApps(visible: Boolean) {
        if (manageApps == visible) return
        onManageAppsChanged(visible)
        if (interactionActive) {
            onNavigationVisibilityChanged(!visible)
        }
    }
    BackHandler(
        enabled = interactionActive && (manageApps || selectionState.isActive || handleSystemBack),
    ) {
        when {
            manageApps -> setManageApps(false)
            selectionState.isActive -> onSelectionStateChanged(selectionState.clear())
            else -> onBack()
        }
    }
    LaunchedEffect(manageApps, interactionActive) {
        if (!interactionActive) return@LaunchedEffect
        if (manageApps) {
            onPrepareAppPicker()
            // Reassert for direct/preview hosts; LibraryScreen also derives suppression from
            // navigationState.collectionManageApps, so pager return never depends on this effect.
            onNavigationVisibilityChanged(false)
        }
    }

    if (manageApps) {
        LibraryCollectionAppPicker(
            collection = collection,
            allApps = allApps,
            memberIds = committedMemberIds,
            pendingMemberships = pendingMemberships,
            sortVariant = libraryState.sortVariant,
            iconRatio = libraryState.iconRatio,
            iconShape = libraryState.iconShape,
            enhancedIcons = libraryState.enhancedIcons,
            scaffoldPadding = scaffoldPadding,
            viewportState = pickerViewport,
            loading = !allAppsPrepared,
            onBack = { setManageApps(false) },
            onSetMembership = { appId, included ->
                if (appId !in pendingMemberships) {
                    pendingMemberships = pendingMemberships + (appId to included)
                    onSetMembership(
                        appId,
                        included,
                        CollectionMembershipResultCallback { success ->
                            if (!success && pendingMemberships[appId] == included) {
                                pendingMemberships = pendingMemberships - appId
                            }
                        },
                    )
                }
            },
        )
        return
    }

    var query by viewportState.queryState
    var searchFocused by remember { mutableStateOf(false) }
    var sortVisible by remember { mutableStateOf(false) }
    val projected = rememberCollectionAppsProjection(viewportState, members, libraryState.sortVariant)
    val projectedIds = remember(projected) {
        projected?.map(LibraryAppUiItem::databaseId).orEmpty()
    }
    val listState = viewportState.listState
    val gridState = viewportState.gridState
    val headerHeightPx = remember { mutableIntStateOf(0) }
    val headerOffsetPx = viewportState.headerOffsetPx
    val density = LocalDensity.current
    val headerSpacerHeight = with(density) { headerHeightPx.intValue.toDp() }
    val scrollConnection = rememberLibraryScrollChrome(
        viewport = viewportState,
        layout = libraryState.layout,
        headerHeightPx = headerHeightPx,
        enabled = interactionActive && !searchFocused,
        onVisibilityChanged = publishNavigationVisibility,
    )

    val searchScope = rememberCoroutineScope()
    fun revealSearchResults() {
        viewportState.headerOffsetPx.floatValue = 0f
        viewportState.chromeVisible = true
        searchScope.launch {
            if (libraryState.layout == LibraryLayout.List) listState.scrollToItem(0)
            else gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(searchFocused) {
        if (searchFocused) revealSearchResults()
    }
    val renderHeader: @Composable (Modifier, Boolean) -> Unit = { modifier, interactive ->
        LibraryCollectionHeader(
            modifier = modifier,
            title = collection.name,
            query = query,
            sortVariant = libraryState.sortVariant,
            sortVisible = sortVisible,
            selectionState = selectionState,
            visibleAppIds = projectedIds,
            onBack = onBack,
            onExitSelection = { onSelectionStateChanged(selectionState.clear()) },
            onSelectAll = {
                onSelectionStateChanged(
                    selectionState.selectVisible(
                        libraryState.generation,
                        projectedIds,
                        collectionId = collection.id,
                    ),
                )
            },
            onUnselectAll = {
                onSelectionStateChanged(
                    selectionState.unselectVisible(
                        libraryState.generation,
                        projectedIds,
                        collectionId = collection.id,
                    ),
                )
            },
            onQueryChange = {
                query = it
                revealSearchResults()
            },
            onSearchFocusChanged = { searchFocused = it },
            onSortVisibilityChanged = { sortVisible = it },
            onSort = onSort,
            onManageApps = { setManageApps(true) },
            interactive = interactive,
            showBackButton = showBackButton,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(scaffoldPadding)
            .clipToBounds()
            .nestedScroll(scrollConnection),
    ) {
        if (projected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (libraryState.layout == LibraryLayout.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (headerHeightPx.intValue == 0) {
                        renderHeader(
                            Modifier.alpha(0f).clearAndSetSemantics { },
                            false,
                        )
                    } else {
                        Spacer(Modifier.height(headerSpacerHeight))
                    }
                }
                if (projected.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryCollectionEmptyState(query)
                    }
                } else {
                    items(projected, key = { it.id }) { app ->
                        LibraryCollectionGridItem(
                            app = app,
                            iconRatio = libraryState.iconRatio,
                            iconShape = libraryState.iconShape,
                            enhancedIcons = libraryState.enhancedIcons,
                            hideTitle = libraryState.hideGridTitles,
                            gridSpacing = libraryState.gridSpacing.value,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                            selectionMode = selectionState.isActive,
                            selected = app.databaseId in selectionState.selectedAppIds,
                            onToggleSelection = {
                                onSelectionStateChanged(
                                    selectionState.toggle(
                                        libraryState.generation,
                                        it.databaseId,
                                        collectionId = collection.id,
                                    ),
                                )
                            },
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
                    if (headerHeightPx.intValue == 0) {
                        renderHeader(
                            Modifier.alpha(0f).clearAndSetSemantics { },
                            false,
                        )
                    } else {
                        Spacer(Modifier.height(headerSpacerHeight))
                    }
                }
                if (projected.isEmpty()) {
                    item { LibraryCollectionEmptyState(query) }
                } else {
                    items(projected, key = { it.id }) { app ->
                        LibraryCollectionListItem(
                            app = app,
                            iconRatio = libraryState.iconRatio,
                            iconShape = libraryState.iconShape,
                            enhancedIcons = libraryState.enhancedIcons,
                            showDescription = libraryState.showListDescription,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                            selectionMode = selectionState.isActive,
                            selected = app.databaseId in selectionState.selectedAppIds,
                            onToggleSelection = {
                                onSelectionStateChanged(
                                    selectionState.toggle(
                                        libraryState.generation,
                                        it.databaseId,
                                        collectionId = collection.id,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = headerOffsetPx.floatValue }
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { headerHeightPx.intValue = it.height },
        ) {
            renderHeader(Modifier, true)
        }
        val scrimVisible by remember(headerOffsetPx) { derivedStateOf { headerOffsetPx.floatValue < -1f } }
        GlassSystemBarScrim(visible = scrimVisible)
    }
}

@Composable
private fun LibraryCollectionEmptyState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) {
                stringResource(R.string.library_collection_members_empty)
            } else {
                stringResource(R.string.library_no_matches, query)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryCollectionHeader(
    modifier: Modifier = Modifier,
    title: String,
    query: String,
    sortVariant: Int,
    sortVisible: Boolean,
    selectionState: LibrarySelectionState,
    visibleAppIds: List<Long>,
    onBack: () -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit = {},
    onSortVisibilityChanged: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onManageApps: () -> Unit,
    interactive: Boolean,
    showBackButton: Boolean,
) {
    val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()
    val selectedSort = sortVariant and Int.MAX_VALUE
    val ascending = sortVariant >= 0
    val selectionMode = selectionState.isActive
    val allVisibleSelected = selectionState.isAllVisibleSelected(visibleAppIds)

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
            if (selectionMode || showBackButton) {
                IconButton(
                    onClick = if (selectionMode) onExitSelection else onBack,
                    enabled = interactive,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.library_back),
                    )
                }
            }
            Text(
                text = if (selectionMode) {
                    pluralStringResource(
                        R.plurals.library_selection_count,
                        selectionState.selectedCount,
                        selectionState.selectedCount,
                    )
                } else {
                    title
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selectionMode) {
                val toggleLabel = stringResource(
                    if (allVisibleSelected) {
                        R.string.library_selection_unselect_all
                    } else {
                        R.string.library_selection_select_all
                    },
                )
                TextButton(
                    onClick = if (allVisibleSelected) onUnselectAll else onSelectAll,
                    enabled = visibleAppIds.isNotEmpty() && interactive,
                ) {
                    Icon(
                        painter = painterResource(
                            if (allVisibleSelected) R.drawable.ic_deselect
                            else R.drawable.ic_select_all,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(toggleLabel)
                }
            } else {
                TextButton(onClick = onManageApps, enabled = interactive) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_to_collection),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.library_collection_add_apps))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibrarySearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
                enabled = interactive,
                onFocusChanged = onSearchFocusChanged,
            )
            Box {
                LibrarySortButton(
                    onClick = { onSortVisibilityChanged(true) },
                    enabled = interactive,
                )
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCollectionListItem(
    app: LibraryAppUiItem,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
    showDescription: Boolean,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (LibraryAppUiItem) -> Unit,
) {
    val selectionDescription = stringResource(
        R.string.library_selection_checkbox_description,
        app.title,
    )
    val selectionStateDescription = stringResource(
        if (selected) R.string.library_selection_checked
        else R.string.library_selection_unchecked,
    )
    val interactionModifier = if (selectionMode) {
        Modifier.toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelection(app) },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onOpenApp(app.id) },
            onLongClick = { onOpenActions(app) },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .semantics {
                role = if (selectionMode) Role.Checkbox else Role.Button
                if (selectionMode) {
                    contentDescription = selectionDescription
                    stateDescription = selectionStateDescription
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.width(52.dp),
                contentSize = 40.dp,
                iconRatio = iconRatio,
                iconShape = iconShape,
                enhancedIcons = enhancedIcons,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.library_vendor_version, app.author, app.version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                Spacer(Modifier.width(6.dp))
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        if (showDescription && app.description.isNotBlank()) {
            Box(modifier = Modifier.padding(start = 80.dp, end = 16.dp, bottom = 10.dp)) {
                LibraryDescription(app.description, app.databaseId)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCollectionGridItem(
    app: LibraryAppUiItem,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
    hideTitle: Boolean,
    gridSpacing: Dp,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (LibraryAppUiItem) -> Unit,
) {
    val selectionDescription = stringResource(
        R.string.library_selection_checkbox_description,
        app.title,
    )
    val selectionStateDescription = stringResource(
        if (selected) R.string.library_selection_checked
        else R.string.library_selection_unchecked,
    )
    val interactionModifier = if (selectionMode) {
        Modifier.toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelection(app) },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onOpenApp(app.id) },
            onLongClick = { onOpenActions(app) },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(gridSpacing / 2)
            .then(interactionModifier)
            .semantics {
                role = if (selectionMode) Role.Checkbox else Role.Button
                if (selectionMode) {
                    contentDescription = selectionDescription
                    stateDescription = selectionStateDescription
                } else if (hideTitle) {
                    contentDescription = app.title
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.fillMaxWidth(),
                contentSize = null,
                iconRatio = iconRatio,
                iconShape = iconShape,
                enhancedIcons = enhancedIcons,
            )
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clearAndSetSemantics { },
                )
            }
        }
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

@Composable
internal fun LibraryCollectionAppPicker(
    collection: LibraryCollectionUiItem,
    allApps: List<LibraryAppUiItem>,
    memberIds: Set<Int>,
    pendingMemberships: Map<Int, Boolean> = emptyMap(),
    sortVariant: Int,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean = true,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState = rememberLibraryViewportState(collectionId = collection.id),
    loading: Boolean = false,
    onBack: () -> Unit,
    onSetMembership: (Int, Boolean) -> Unit,
) {
    var query by viewportState.queryState
    val visibleApps = rememberCollectionAppsProjection(viewportState, allApps, sortVariant, ready = !loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.library_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_collection_manage_apps),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = collection.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.library_collection_manage_apps_summary),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (loading || visibleApps == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LibrarySearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize(), state = viewportState.listState) {
            items(visibleApps, key = { it.id }) { app ->
                val pending = pendingMemberships[app.id]
                val checked = pending ?: (app.id in memberIds)
                val enabled = pending == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collection-membership-${app.id}")
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = { next -> onSetMembership(app.id, next) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryIconSlot(
                        app = app,
                        modifier = Modifier.width(48.dp),
                        contentSize = 40.dp,
                        iconRatio = iconRatio,
                        iconShape = iconShape,
                        enhancedIcons = enhancedIcons,
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
                            text = stringResource(R.string.library_vendor_version, app.author, app.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun rememberCollectionAppsProjection(
    viewportState: LibraryViewportState,
    source: List<LibraryAppUiItem>,
    sortVariant: Int,
    ready: Boolean = true,
): List<LibraryAppUiItem>? {
    val query = viewportState.query
    LaunchedEffect(viewportState, source, query, sortVariant, ready) {
        if (!ready) return@LaunchedEffect
        if (viewportState.collectionProjection?.matches(source, query, sortVariant) == true) {
            return@LaunchedEffect
        }
        val apps = withContext(Dispatchers.Default) {
            projectCollectionApps(source, query, sortVariant)
        }
        viewportState.collectionProjection = LibraryCollectionProjection(source, query, sortVariant, apps)
    }
    return viewportState.collectionProjection?.apps
}

private fun projectCollectionApps(
    rows: List<LibraryAppUiItem>,
    filter: String,
    sortVariant: Int,
    locale: Locale = Locale.getDefault(),
): List<LibraryAppUiItem> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    val ranked = rows.mapNotNull { row ->
        val rank = if (needle.isEmpty()) 0 else collectionSearchRank(row, needle) ?: return@mapNotNull null
        rank to row
    }
    if (ranked.size < 2) return ranked.map { it.second }

    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val sortIndex = sortVariant and Int.MAX_VALUE
    val descending = sortVariant < 0
    val fallback = Comparator<LibraryAppUiItem> { left, right ->
        val primary = when (sortIndex) {
            1 -> left.id.compareTo(right.id)
            2 -> collator.compare(left.author, right.author)
            else -> collator.compare(left.title, right.title)
        }
        val ordered = if (descending) -primary else primary
        if (ordered != 0) ordered else left.id.compareTo(right.id)
    }
    return ranked.sortedWith { left, right ->
        val rankOrder = left.first.compareTo(right.first)
        if (rankOrder != 0) rankOrder else fallback.compare(left.second, right.second)
    }.map { it.second }
}

private fun collectionSearchRank(row: LibraryAppUiItem, needle: String): Int? {
    val title = row.title.lowercase(Locale.ROOT)
    val vendor = row.author.lowercase(Locale.ROOT)
    val version = row.version.lowercase(Locale.ROOT)
    val description = row.description.lowercase(Locale.ROOT)
    return when {
        title == needle -> 0
        title.startsWith(needle) -> 1
        title.contains(needle) -> 2
        vendor.contains(needle) -> 3
        version.contains(needle) -> 4
        description.contains(needle) -> 5
        else -> null
    }
}
