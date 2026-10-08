/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward

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

    fun displayedMembersCollectionId(): Long? = mutableState.value.members?.collectionId

    fun containsCollection(collectionId: Long): Boolean =
        mutableState.value.collections.any { it.id == collectionId }

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

fun interface CollectionMembershipResultCallback {
    fun onResult(success: Boolean)
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
    fun onSetCollectionMembership(
        appId: Int,
        collectionId: Long,
        included: Boolean,
        callback: CollectionMembershipResultCallback,
    )
    fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long)
    fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long)
    fun onRemoveAppFromCollection(appId: Int, collectionId: Long)
}

/** READY Collections destination. Collections overview and member browsing share Library scroll chrome. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun LibraryCollectionsDestination(
    host: LibraryCollectionsHost,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    overviewScaffoldPadding: PaddingValues = scaffoldPadding,
    collectionScaffoldPadding: PaddingValues = scaffoldPadding,
    navigationState: LibraryNavigationState = LibraryNavigationState(),
    overviewViewport: LibraryViewportState = rememberLibraryViewportState(libraryState.libraryScope),
    collectionViewport: LibraryViewportState = rememberLibraryViewportState(libraryState.libraryScope, navigationState.selectedCollectionId),
    pickerViewport: LibraryViewportState = rememberLibraryViewportState(libraryState.libraryScope, navigationState.selectedCollectionId),
    onNavigationStateChanged: (LibraryNavigationState) -> Unit = {},
    onOpenActions: (LibraryAppUiItem, Long) -> Unit,
    selectionState: LibrarySelectionState = LibrarySelectionState(),
    onSelectionStateChanged: (LibrarySelectionState) -> Unit = {},
    onNavigationVisibilityChanged: (Boolean) -> Unit = {},
    active: Boolean = true,
) {
    val state by host.collectionsStore().state.collectAsState()
    if (!state.ready) {
        LibraryCollectionsDestination(scaffoldPadding)
        return
    }

    val persistedCollectionId = navigationState.selectedCollectionId
    val collectionScopeMatches = persistedCollectionId == null ||
        libraryState.libraryScope.isEmpty() ||
        navigationState.selectedCollectionScope == libraryState.libraryScope
    val selectedCollectionId = persistedCollectionId.takeIf { collectionScopeMatches }
    val selectedCollection = selectedCollectionId?.let { collectionId ->
        state.collections.firstOrNull { it.id == collectionId }
    }
    LaunchedEffect(
        selectionState,
        selectedCollectionId,
        state.members?.collectionId,
        state.members?.members,
        libraryState.generation,
    ) {
        val members = state.members
        if (
            !selectionState.isActive ||
            selectedCollectionId == null ||
            selectionState.collectionId != selectedCollectionId ||
            members?.collectionId != selectedCollectionId
        ) {
            return@LaunchedEffect
        }
        val retained = selectionState.retainAvailable(
            libraryState.generation,
            members.members.asSequence().map(LibraryAppUiItem::databaseId).toList(),
            collectionId = selectedCollectionId,
        )
        if (retained != selectionState) onSelectionStateChanged(retained)
    }

    LaunchedEffect(
        persistedCollectionId,
        selectedCollectionId,
        state.collections,
        state.members?.collectionId,
        libraryState.libraryScope,
    ) {
        when {
            persistedCollectionId != null && !collectionScopeMatches -> {
                onSelectionStateChanged(selectionState.clear())
                onNavigationStateChanged(
                    navigationState.copy(
                        selectedCollectionId = null,
                        selectedCollectionScope = null,
                        collectionManageApps = false,
                    ),
                )
                host.onDismissCollectionMembers()
            }
            selectedCollectionId == null && state.members != null -> {
                host.onDismissCollectionMembers()
            }
            selectedCollectionId != null && selectedCollection == null -> {
                onNavigationStateChanged(
                    navigationState.copy(
                        selectedCollectionId = null,
                        selectedCollectionScope = null,
                        collectionManageApps = false,
                    ),
                )
                host.onDismissCollectionMembers()
            }
            selectedCollectionId != null && state.members?.collectionId != selectedCollectionId -> {
                host.onOpenCollection(selectedCollectionId)
            }
        }
    }

    val currentActive by rememberUpdatedState(active)
    val currentOnNavigationVisibilityChanged by rememberUpdatedState(
        onNavigationVisibilityChanged,
    )
    val activeNavigationVisibilityChanged: (Boolean) -> Unit = remember {
        { visible ->
            if (currentActive) {
                currentOnNavigationVisibilityChanged(visible)
            }
        }
    }
    val closeCollection = {
        onSelectionStateChanged(selectionState.clear())
        activeNavigationVisibilityChanged(true)
        onNavigationStateChanged(
            navigationState.copy(
                selectedCollectionId = null,
                selectedCollectionScope = null,
                collectionManageApps = false,
            ),
        )
        host.onDismissCollectionMembers()
    }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(adaptiveInfo) {
        calculatePaneScaffoldDirective(adaptiveInfo).copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val showDetailBack = directive.maxHorizontalPartitions == 1
    // Keep neighbouring pager pages on their last visual state. "active" owns interaction,
    // not which Collection is rendered, so returning to this tab cannot flash the overview.
    val displayedCollectionId = selectedCollectionId
    val scaffoldValue = remember(
        directive.maxHorizontalPartitions,
        displayedCollectionId != null,
    ) {
        val multiPane = directive.maxHorizontalPartitions > 1
        ThreePaneScaffoldValue(
            // ListDetailPaneScaffold maps Detail -> primary and List -> secondary.
            primary = if (multiPane || displayedCollectionId != null) {
                PaneAdaptedValue.Expanded
            } else {
                PaneAdaptedValue.Hidden
            },
            secondary = if (multiPane || displayedCollectionId == null) {
                PaneAdaptedValue.Expanded
            } else {
                PaneAdaptedValue.Hidden
            },
            tertiary = PaneAdaptedValue.Hidden,
        )
    }

    BackHandler(enabled = active && selectedCollectionId != null) {
        closeCollection()
    }

    ListDetailPaneScaffold(
        directive = directive,
        value = scaffoldValue,
        modifier = Modifier
            .fillMaxSize()
            .focusProperties {
                onEnter = { if (!currentActive) cancelFocusChange() }
            }
            .focusGroup()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        listPane = {
            AnimatedPane {
                LibraryCollectionsOverview(
                    host = host,
                    state = state,
                    libraryState = libraryState,
                    scaffoldPadding = overviewScaffoldPadding,
                    viewportState = overviewViewport,
                    selectedCollectionId = selectedCollectionId,
                    active = active,
                    onOpenCollection = { collectionId ->
                        if (currentActive) {
                            onNavigationStateChanged(
                                navigationState.copy(
                                    selectedCollectionId = collectionId,
                                    selectedCollectionScope =
                                        libraryState.libraryScope.takeIf(String::isNotEmpty),
                                    collectionManageApps = false,
                                ),
                            )
                        }
                    },
                    onNavigationVisibilityChanged = activeNavigationVisibilityChanged,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val collectionId = displayedCollectionId
                if (collectionId == null) {
                    LibraryCollectionDetailPlaceholder(scaffoldPadding)
                } else {
                    val collection = state.collections.firstOrNull { it.id == collectionId }
                    val members = state.members?.takeIf { it.collectionId == collectionId }
                    if (collection == null || members == null) {
                        LibraryCollectionDetailLoading(
                            name = collection?.name,
                            scaffoldPadding = scaffoldPadding,
                        )
                    } else {
                        key(collection.id) {
                            LibraryCollectionBrowser(
                                collection = collection,
                                members = members.members,
                                allApps = state.allApps,
                                allAppsPrepared = state.allAppsPrepared,
                                libraryState = libraryState,
                                scaffoldPadding = collectionScaffoldPadding,
                                viewportState = collectionViewport,
                                pickerViewport = pickerViewport,
                                onBack = {
                                    if (active) closeCollection()
                                },
                                onOpenApp = host::onOpenApp,
                                onOpenActions = { app -> onOpenActions(app, collection.id) },
                                selectionState = selectionState,
                                onSelectionStateChanged = onSelectionStateChanged,
                                manageApps = navigationState.collectionManageApps,
                                onManageAppsChanged = { visible ->
                                    onNavigationStateChanged(
                                        navigationState.copy(collectionManageApps = visible),
                                    )
                                },
                                onSetMembership = { appId, included, callback ->
                                    host.onSetCollectionMembership(
                                        appId,
                                        collection.id,
                                        included,
                                        callback,
                                    )
                                },
                                onPrepareAppPicker = host::onPrepareCollectionAppPicker,
                                onSort = host::onSort,
                                onNavigationVisibilityChanged = activeNavigationVisibilityChanged,
                                showBackButton = showDetailBack,
                                handleSystemBack = false,
                                interactionActive = active,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun LibraryCollectionsOverview(
    host: LibraryCollectionsHost,
    state: LibraryCollectionsUiState,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState,
    selectedCollectionId: Long?,
    active: Boolean,
    onOpenCollection: (Long) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
) {
    val currentActive by rememberUpdatedState(active)
    val currentOnNavigationVisibilityChanged by rememberUpdatedState(onNavigationVisibilityChanged)
    val publishNavigationVisibility: (Boolean) -> Unit = remember(viewportState) {
        { visible ->
            viewportState.chromeVisible = visible
            currentOnNavigationVisibilityChanged(visible)
        }
    }

    var createDialog by rememberSaveable { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    // These windows are presentation owned by the settled route, not retained viewport data.
    LaunchedEffect(active) {
        if (!active) {
            createDialog = false
            actionsTarget = null
            renameTarget = null
            deleteTarget = null
        }
    }
    val listState = viewportState.listState
    val headerHeightPx = viewportState.headerHeightPx
    val headerOffsetPx = viewportState.headerOffsetPx
    val headerHidden by remember(headerHeightPx, headerOffsetPx) {
        derivedStateOf {
            headerHeightPx.intValue > 0 &&
                headerOffsetPx.floatValue <= -headerHeightPx.intValue + 0.5f
        }
    }
    val scrollConnection = rememberLibraryScrollChrome(
        viewport = viewportState,
        layout = LibraryLayout.List,
        headerHeightPx = headerHeightPx,
        enabled = active,
        onVisibilityChanged = publishNavigationVisibility,
    )

    val renderHeader: @Composable (Modifier, Boolean) -> Unit = { modifier, interactive ->
        val titleGate = rememberLibraryHeaderActionGate(headerOffsetPx)
        val titleActionsEnabled = interactive && titleGate.enabled.value
        Row(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .then(titleGate.positionModifier)
                .then(if (titleActionsEnabled) Modifier else Modifier.clearAndSetSemantics { }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_destination_collections),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(
                enabled = titleActionsEnabled,
                onClick = { if (currentActive) createDialog = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_create_folder),
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
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .clipToBounds()
            .nestedScroll(scrollConnection),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = scaffoldPadding,
        ) {
            item {
                if (headerHeightPx.intValue == 0) {
                    renderHeader(
                        Modifier
                            .alpha(0f)
                            .clearAndSetSemantics { },
                        false,
                    )
                } else {
                    LibraryChromeSpacer(headerHeightPx, headerOffsetPx)
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
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.library_collections_empty_title),
                                style = MaterialTheme.typography.titleLarge,
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
                    val selected = collection.id == selectedCollectionId
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                        headlineContent = {
                            Text(
                                text = collection.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { if (currentActive) actionsTarget = collection }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (currentActive) onOpenCollection(collection.id) }
                            .semantics { this.selected = selected },
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
                .graphicsLayer { translationY = headerOffsetPx.floatValue }
                .background(MaterialTheme.colorScheme.background)
                .then(if (headerHidden) Modifier.clearAndSetSemantics { } else Modifier)
                .onSizeChanged { headerHeightPx.intValue = it.height },
        ) {
            renderHeader(Modifier, true)
        }
    }

    if (active && createDialog) {
        CollectionNameDialog(
            title = stringResource(R.string.library_collection_new),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { createDialog = false },
            onConfirm = { name ->
                createDialog = false
                if (currentActive) host.onCreateCollection(name)
            },
        )
    }

    actionsTarget?.takeIf { active }?.let { collection ->
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

    renameTarget?.takeIf { active }?.let { collection ->
        CollectionNameDialog(
            title = stringResource(R.string.action_context_rename),
            initialName = collection.name,
            confirmLabel = stringResource(R.string.action_context_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                if (currentActive) host.onRenameCollection(collection.id, name)
            },
        )
    }

    deleteTarget?.takeIf { active }?.let { collection ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(collection.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (currentActive) host.onDeleteCollection(collection.id)
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

@Composable
private fun LibraryCollectionDetailPlaceholder(scaffoldPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.library_collection_select_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.library_collection_select_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LibraryCollectionDetailLoading(
    name: String?,
    scaffoldPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            name?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    val maxListHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        textScrollable = false,
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
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val canScrollForward = rememberLazyListCanScrollForward(listState)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxListHeight),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                        ) {
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
                        ScrollableContentHint(
                            visible = canScrollForward,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
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
    val maxListHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        textScrollable = false,
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
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val canScrollForward = rememberLazyListCanScrollForward(listState)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxListHeight),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                        ) {
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
                        ScrollableContentHint(
                            visible = canScrollForward,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        },
        confirmButton = null,
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
        confirmButton = null,
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
