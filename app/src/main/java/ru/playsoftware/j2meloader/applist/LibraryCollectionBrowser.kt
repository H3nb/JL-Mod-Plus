/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.applist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.R

/**
 * Collection-specific browser. It mirrors Library List/Grid chrome while membership controls replace
 * Favorites and quick views.
 */
@Composable
internal fun LibraryCollectionBrowser(
    collection: LibraryCollectionUiItem,
    members: List<LibraryAppUiItem>,
    allApps: List<LibraryAppUiItem>,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onRemove: (Int) -> Unit,
    onSetMembership: (Int, Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit = {},
) {
    var manageApps by rememberSaveable(collection.id) { mutableStateOf(false) }
    BackHandler {
        if (manageApps) {
            manageApps = false
            onNavigationVisibilityChanged(true)
        } else {
            onBack()
        }
    }
    LaunchedEffect(manageApps) {
        onNavigationVisibilityChanged(!manageApps)
    }

    if (manageApps) {
        LibraryCollectionAppPicker(
            collection = collection,
            allApps = allApps,
            memberIds = members.mapTo(LinkedHashSet()) { it.id },
            sortVariant = libraryState.sortVariant,
            iconRatio = libraryState.iconRatio,
            scaffoldPadding = scaffoldPadding,
            onBack = { manageApps = false },
            onSetMembership = onSetMembership,
        )
        return
    }

    var query by rememberSaveable(collection.id) { mutableStateOf("") }
    var sortVisible by remember { mutableStateOf(false) }
    val projected by produceState(
        initialValue = members,
        members,
        query,
        libraryState.sortVariant,
    ) {
        value = withContext(Dispatchers.Default) {
            projectCollectionApps(members, query, libraryState.sortVariant)
        }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val headerHeightPx = remember { mutableStateOf(0) }
    val headerOffsetPx = remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val headerSpacerHeight = with(density) { headerHeightPx.value.toDp() }
    val hideDistancePx = with(density) { 10.dp.toPx() }
    val revealDistancePx = with(density) { 18.dp.toPx() }
    val chromeHysteresis = remember(hideDistancePx, revealDistancePx) {
        LibraryChromeScrollHysteresis(hideDistancePx, revealDistancePx)
    }

    LaunchedEffect(libraryState.layout, collection.id) {
        headerOffsetPx.value = 0f
        chromeHysteresis.reset()
        onNavigationVisibilityChanged(true)
        snapshotFlow {
            if (libraryState.layout == LibraryLayout.List) {
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
                onNavigationVisibilityChanged(true)
            }
        }
    }

    val scrollConnection = remember(
        chromeHysteresis,
        libraryState.layout,
        onNavigationVisibilityChanged,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val height = headerHeightPx.value
                if (delta == 0f || height <= 0) return Offset.Zero

                val canScroll = if (libraryState.layout == LibraryLayout.List) {
                    listState.canScrollForward || listState.canScrollBackward
                } else {
                    gridState.canScrollForward || gridState.canScrollBackward
                }
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
        LibraryCollectionHeader(
            modifier = modifier,
            title = collection.name,
            query = query,
            sortVariant = libraryState.sortVariant,
            sortVisible = sortVisible,
            onBack = onBack,
            onQueryChange = { query = it },
            onSortVisibilityChanged = { sortVisible = it },
            onSort = onSort,
            onManageApps = { manageApps = true },
            interactive = interactive,
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
        if (libraryState.layout == LibraryLayout.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (headerHeightPx.value == 0) {
                    renderHeader(Modifier.alpha(0f).clearAndSetSemantics { }, false)
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
                            hideTitle = libraryState.hideGridTitles,
                            gridSpacing = libraryState.gridSpacing.value,
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                            onRemove = onRemove,
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
                    if (headerHeightPx.value == 0) {
                    renderHeader(Modifier.alpha(0f).clearAndSetSemantics { }, false)
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
                            onOpenApp = onOpenApp,
                            onOpenActions = onOpenActions,
                            onRemove = onRemove,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = headerOffsetPx.value }
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { headerHeightPx.value = it.height },
        ) {
            renderHeader(Modifier, true)
        }
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
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortVisibilityChanged: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onManageApps: () -> Unit,
    interactive: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()
    val selectedSort = sortVariant and Int.MAX_VALUE
    val ascending = sortVariant >= 0

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
            IconButton(onClick = onBack, enabled = interactive) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.library_back),
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onManageApps, enabled = interactive) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.library_collection_add_apps))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
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
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                leadingIcon = {
                    if (query.isEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = stringResource(R.string.search),
                        )
                    } else {
                        IconButton(
                            enabled = interactive,
                            onClick = {
                                onQueryChange("")
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
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
                    enabled = interactive,
                    onClick = { onSortVisibilityChanged(true) },
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCollectionListItem(
    app: LibraryAppUiItem,
    iconRatio: LibraryIconRatio,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onRemove: (Int) -> Unit,
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
                    text = stringResource(R.string.library_vendor_version, app.author, app.version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { onRemove(app.id) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove_circle),
                    contentDescription = stringResource(R.string.library_collection_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCollectionGridItem(
    app: LibraryAppUiItem,
    iconRatio: LibraryIconRatio,
    hideTitle: Boolean,
    gridSpacing: Dp,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(gridSpacing / 2)
            .combinedClickable(
                onClick = { onOpenApp(app.id) },
                onLongClick = { onOpenActions(app) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.fillMaxWidth(),
                contentSize = null,
                iconRatio = iconRatio,
            )
            IconButton(
                onClick = { onRemove(app.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove_circle),
                    contentDescription = stringResource(R.string.library_collection_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    sortVariant: Int,
    iconRatio: LibraryIconRatio,
    scaffoldPadding: PaddingValues,
    onBack: () -> Unit,
    onSetMembership: (Int, Boolean) -> Unit,
) {
    var query by rememberSaveable(collection.id, "picker") { mutableStateOf("") }
    var selectedIds by remember(collection.id) { mutableStateOf(memberIds) }
    LaunchedEffect(memberIds) {
        selectedIds = memberIds
    }
    val visibleApps by produceState(
        initialValue = allApps,
        allApps,
        query,
        sortVariant,
    ) {
        value = withContext(Dispatchers.Default) {
            projectCollectionApps(allApps, query, sortVariant)
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(54.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
            leadingIcon = {
                if (query.isEmpty()) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.search),
                    )
                } else {
                    IconButton(onClick = {
                        query = ""
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.library_search_clear),
                        )
                    }
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(visibleApps, key = { it.id }) { app ->
                val checked = app.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !checked
                            selectedIds = selectedIds.toMutableSet().apply {
                                if (next) add(app.id) else remove(app.id)
                            }
                            onSetMembership(app.id, next)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryIconSlot(
                        app = app,
                        modifier = Modifier.width(48.dp),
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
                            text = stringResource(R.string.library_vendor_version, app.author, app.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { next ->
                            selectedIds = selectedIds.toMutableSet().apply {
                                if (next) add(app.id) else remove(app.id)
                            }
                            onSetMembership(app.id, next)
                        },
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
