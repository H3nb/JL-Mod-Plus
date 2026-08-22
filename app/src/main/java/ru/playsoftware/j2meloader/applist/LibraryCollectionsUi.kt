/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.applist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.librarydb.LibraryCollectionRow

data class LibraryCollectionUiItem(
    val id: Long,
    val name: String,
    val appCount: Int,
)

data class LibraryCollectionMembersUi(
    val collectionId: Long,
    val members: List<LibraryAppUiItem>,
)

data class LibraryCollectionAppTargetUi(
    val appId: Int,
    val title: String,
)

data class LibraryCollectionsUiState(
    val ready: Boolean = false,
    val collections: List<LibraryCollectionUiItem> = emptyList(),
    val allApps: List<LibraryAppUiItem> = emptyList(),
    val allAppsPrepared: Boolean = false,
    val members: LibraryCollectionMembersUi? = null,
    val addTarget: LibraryCollectionAppTargetUi? = null,
    val bulkAddTargetAppIds: Set<Long>? = null,
)

/** Fragment-owned presentation bridge; Compose never reaches Room or filesystem directly. */
class LibraryCollectionsUiStore {
    private val mutableState = MutableStateFlow(LibraryCollectionsUiState())
    val state: StateFlow<LibraryCollectionsUiState> = mutableState.asStateFlow()

    fun publishCollections(rows: List<LibraryCollectionRow>) {
        val collections = rows.map { row -> LibraryCollectionUiItem(row.id, row.name, row.appCount) }
        val current = mutableState.value
        mutableState.value = current.copy(
            ready = true,
            collections = collections,
            members = current.members?.takeIf { members ->
                collections.any { it.id == members.collectionId }
            },
        )
    }

    fun publishAllApps(items: List<LibraryAppUiItem>) {
        mutableState.value = mutableState.value.copy(
            allApps = items,
            allAppsPrepared = true,
        )
    }

    fun clear() {
        mutableState.value = LibraryCollectionsUiState()
    }

    fun showMembers(collectionId: Long, members: List<LibraryAppUiItem>) {
        if (mutableState.value.collections.none { it.id == collectionId }) return
        mutableState.value = mutableState.value.copy(
            members = LibraryCollectionMembersUi(collectionId, members),
        )
    }

    fun activeCollectionId(): Long? = mutableState.value.members?.collectionId

    fun hasAllAppsSnapshot(): Boolean = mutableState.value.allAppsPrepared

    fun dismissMembers() {
        mutableState.value = mutableState.value.copy(
            members = null,
            allApps = emptyList(),
            allAppsPrepared = false,
        )
    }

    fun showAddTarget(appId: Int, title: String) {
        mutableState.value = mutableState.value.copy(
            addTarget = LibraryCollectionAppTargetUi(appId, title),
            bulkAddTargetAppIds = null,
        )
    }

    fun showBulkAddTarget(appIds: Set<Long>) {
        if (appIds.isEmpty()) return
        mutableState.value = mutableState.value.copy(
            addTarget = null,
            bulkAddTargetAppIds = appIds.toSet(),
        )
    }

    fun dismissAddTarget() {
        mutableState.value = mutableState.value.copy(
            addTarget = null,
            bulkAddTargetAppIds = null,
        )
    }
}

/** Bulk operations are kept separate so previews and lightweight hosts can omit the domain side effects. */
interface LibraryBulkActions {
    fun onDeleteSelected(appIds: Set<Long>) = Unit
    fun onAddSelectedToCollection(appIds: Set<Long>) = Unit
    fun onShareSelected(appIds: Set<Long>) = Unit
    fun onReinstallSelected(appIds: Set<Long>) = Unit
    fun onExportSelectedBundle(appIds: Set<Long>) = Unit
}

/** Extra capabilities implemented only by the production Library host. */
interface LibraryCollectionsHost : LibraryActions, LibraryBulkActions {
    fun collectionsStore(): LibraryCollectionsUiStore
    fun onCreateCollection(name: String)
    fun onRenameCollection(collectionId: Long, name: String)
    fun onDeleteCollection(collectionId: Long)
    fun onOpenCollection(collectionId: Long)
    fun onPrepareCollectionAppPicker()
    fun onDismissCollectionMembers()
    fun onRequestAddToCollection(appId: Int)
    fun onDismissAddToCollection()
    fun onAddAppToCollection(appId: Int, collectionId: Long)
    fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long)
    fun onRemoveAppFromCollection(appId: Int, collectionId: Long)
}

/** READY Collections destination. Collections overview and member browsing share Library scroll chrome. */
@Composable
internal fun LibraryCollectionsDestination(
    host: LibraryCollectionsHost,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    navigationState: LibraryNavigationState = LibraryNavigationState(),
    onNavigationStateChanged: (LibraryNavigationState) -> Unit = {},
    onOpenActions: (LibraryAppUiItem, Long) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit = {},
) {
    val state by host.collectionsStore().state.collectAsState()
    if (!state.ready) {
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
            navigationState = navigationState,
            onNavigationStateChanged = onNavigationStateChanged,
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
            onPrepareAppPicker = host::onPrepareCollectionAppPicker,
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
    val currentNavigationState by androidx.compose.runtime.rememberUpdatedState(navigationState)
    val headerHeightPx = remember { mutableStateOf(0) }
    val headerOffsetPx = remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val headerSpacerHeight = with(density) { headerHeightPx.value.toDp() }
    val hideDistancePx = with(density) { LIBRARY_CHROME_HIDE_DISTANCE_DP.dp.toPx() }
    val minScrollRoomPx = with(density) { LIBRARY_CHROME_MIN_SCROLL_ROOM_DP.dp.toPx() }
    val revealDistancePx = with(density) { 18.dp.toPx() }
    val chromeHysteresis = remember(hideDistancePx, revealDistancePx) {
        LibraryChromeScrollHysteresis(hideDistancePx, revealDistancePx)
    }

    LaunchedEffect(state.collections, libraryState.generation) {
        val availableIds = state.collections.map { it.id }
        val anchor = navigationState.resolveAnchor(
            LibraryNavigationSurface.CollectionsList,
            libraryState.generation,
            availableIds,
        ) ?: return@LaunchedEffect
        val targetIndex = anchor.index + 1
        if (listState.firstVisibleItemIndex != targetIndex ||
            listState.firstVisibleItemScrollOffset != anchor.offsetPx
        ) {
            listState.scrollToItem(targetIndex, anchor.offsetPx)
        }
    }

    LaunchedEffect(state.collections, libraryState.generation) {
        snapshotFlow {
            val firstCollection = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index > 0 }
            val fallbackIndex = (firstCollection?.index ?: 1) - 1
            LibraryScrollAnchor(
                generation = libraryState.generation,
                stableItemId = state.collections.getOrNull(fallbackIndex)?.id,
                offsetPx = firstCollection?.offset ?: 0,
                fallbackIndex = fallbackIndex.coerceAtLeast(0),
            )
        }.collectLatest { anchor ->
            onNavigationStateChanged(
                currentNavigationState.saveAnchor(
                    LibraryNavigationSurface.CollectionsList,
                    anchor,
                ),
            )
        }
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
            if ((index == 0 && offset == 0 || !canScroll) && headerOffsetPx.value >= -0.5f) {
                headerOffsetPx.value = 0f
                chromeHysteresis.reset()
                onNavigationVisibilityChanged(true)
            }
        }
    }

    val scrollConnection = remember(
        chromeHysteresis,
        minScrollRoomPx,
        onNavigationVisibilityChanged,
    ) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val height = headerHeightPx.value
                if (height <= 0) return Offset.Zero
                val canScroll = listState.canScrollForward || listState.canScrollBackward
                if (!canScroll) {
                    if (headerOffsetPx.value < -0.5f && (consumed.y > 0f || available.y > 0f)) {
                        headerOffsetPx.value = 0f
                        if (chromeHysteresis.revealNow() != null) {
                            onNavigationVisibilityChanged(true)
                        }
                    } else if (headerOffsetPx.value == 0f && !chromeHysteresis.chromeVisible) {
                        chromeHysteresis.reset()
                        onNavigationVisibilityChanged(true)
                    }
                    return Offset.Zero
                }
                // Base hide/reveal progress on the distance the LazyColumn actually consumed.
                // Unconsumed fling distance can exceed a short collection's range and otherwise
                // causes a footer hide/show loop when the viewport changes.
                val delta = when {
                    consumed.y != 0f -> consumed.y
                    available.y > 0f -> available.y
                    else -> return Offset.Zero
                }
                if (delta < 0f && !listState.hasLibraryChromeScrollRoom(minScrollRoomPx)) {
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
                if (headerHeightPx.value == 0) {
                    renderHeader(
                        Modifier
                            .alpha(0f)
                            .clearAndSetSemantics { },
                        false,
                    )
                } else {
                    Spacer(Modifier.height(headerSpacerHeight))
                }
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
                                text = pluralStringResource(
                                    R.plurals.library_collection_member_count,
                                    collection.appCount,
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
                .graphicsLayer { translationY = headerOffsetPx.value }
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

/** Collection dialogs live at LibraryScreen scope so Add-to-Collection works from any app browser. */
@Composable
internal fun LibraryCollectionsDialogHost(host: LibraryCollectionsHost) {
    val state by host.collectionsStore().state.collectAsState()
    var createForAdd by rememberSaveable { mutableStateOf(false) }

    state.addTarget?.let { target ->
        AddToCollectionDialog(
            target = target,
            collections = state.collections,
            onDismiss = host::onDismissAddToCollection,
            onCreate = { createForAdd = true },
            onSelected = { collectionId ->
                host.onAddAppToCollection(target.appId, collectionId)
                host.onDismissAddToCollection()
            },
        )
    }

    state.bulkAddTargetAppIds?.let { appIds ->
        AddAppsToCollectionDialog(
            appCount = appIds.size,
            collections = state.collections,
            onDismiss = host::onDismissAddToCollection,
            onSelected = { collectionId ->
                host.onAddAppsToCollection(appIds, collectionId)
                host.onDismissAddToCollection()
            },
        )
    }

    if (createForAdd) {
        CollectionNameDialog(
            title = stringResource(R.string.library_collection_new),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { createForAdd = false },
            onConfirm = { name ->
                createForAdd = false
                host.onCreateCollection(name)
            },
        )
    }
}

@Composable
private fun AddToCollectionDialog(
    target: LibraryCollectionAppTargetUi,
    collections: List<LibraryCollectionUiItem>,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onSelected: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_collection_add_app)) },
        text = {
            Column {
                Text(
                    text = target.title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_collection_ready_empty_message),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(collections, key = { it.id }) { collection ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = collection.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.library_collection_member_count,
                                            collection.appCount,
                                            collection.appCount,
                                        ),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelected(collection.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (collections.isEmpty()) {
                TextButton(onClick = onCreate) {
                    Text(stringResource(R.string.library_collection_new))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddAppsToCollectionDialog(
    appCount: Int,
    collections: List<LibraryCollectionUiItem>,
    onDismiss: () -> Unit,
    onSelected: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_bulk_add_collection)) },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.library_collection_member_count,
                        appCount,
                        appCount,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (collections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_collection_ready_empty_message),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(collections, key = { it.id }) { collection ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = collection.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.library_collection_member_count,
                                            collection.appCount,
                                            collection.appCount,
                                        ),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelected(collection.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun CollectionActionsDialog(
    collection: LibraryCollectionUiItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = collection.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                CollectionDialogAction(
                    label = R.string.action_context_rename,
                    icon = R.drawable.ic_edit,
                    onClick = onRename,
                )
                CollectionDialogAction(
                    label = R.string.action_context_delete,
                    icon = R.drawable.ic_delete,
                    destructive = true,
                    onClick = onDelete,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CollectionDialogAction(
    label: Int,
    icon: Int,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(label), color = contentColor) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun CollectionNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val normalized = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.library_collection_name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalized) },
                enabled = normalized.isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
