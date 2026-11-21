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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
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

/**
 * The scope picker and Manage collections share one row style. Membership is catalog state,
 * not a folder hierarchy: the All apps row is the only synthetic entry.
 */
@Composable
internal fun LibraryCollectionChoiceRow(
    collection: LibraryCollectionUiItem?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = collection?.name ?: stringResource(R.string.library_destination_apps),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (collection != null) {
            {
                Text(
                    pluralStringResource(
                        R.plurals.library_collection_member_count,
                        collection.appCount,
                        collection.appCount,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        leadingContent = {
            Icon(
                painter = painterResource(
                    if (collection == null) R.drawable.ic_apps else R.drawable.ic_folder,
                ),
                contentDescription = null,
            )
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
    )
}

data class LibraryCollectionMembersUi(
    val collectionId: Long,
    val revision: Long,
    val memberIds: Set<Long>,
    val members: List<LibraryAppUiItem>,
)

data class LibraryCollectionAppTargetUi(
    val appId: Int,
    val title: String,
    val requestId: Long,
    val memberCollectionIds: Set<Long>? = null,
)

data class LibraryCollectionsUiState(
    val ready: Boolean = false,
    val collections: List<LibraryCollectionUiItem> = emptyList(),
    val allAppsRevision: Long = 0L,
    val allApps: List<LibraryAppUiItem> = emptyList(),
    val allAppsPrepared: Boolean = false,
    val members: LibraryCollectionMembersUi? = null,
    val addTarget: LibraryCollectionAppTargetUi? = null,
    val bulkAddTargetAppIds: Set<Long>? = null,
)

/** Fragment-owned presentation bridge; Compose never reaches Room or filesystem directly. */
class LibraryCollectionsUiStore {
    private var nextTargetRevision = 0L
    private var nextAllAppsRevision = 0L
    private var nextMembersRevision = 0L
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
            allAppsRevision = ++nextAllAppsRevision,
            allApps = items,
            allAppsPrepared = true,
        )
    }

    fun dismissPicker() {
        mutableState.value = mutableState.value.copy(
            allApps = emptyList(),
            allAppsPrepared = false,
        )
    }

    fun clear() {
        mutableState.value = LibraryCollectionsUiState()
    }

    fun showMembers(
        collectionId: Long,
        members: List<LibraryAppUiItem>,
        memberIds: Set<Long> = members.mapTo(LinkedHashSet()) { it.databaseId },
    ) {
        if (mutableState.value.collections.none { it.id == collectionId }) return
        mutableState.value = mutableState.value.copy(
            members = LibraryCollectionMembersUi(
                collectionId, ++nextMembersRevision, memberIds, members,
            ),
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

    fun showAddTarget(appId: Int, title: String): Long {
        val requestId = ++nextTargetRevision
        mutableState.value = mutableState.value.copy(
            addTarget = LibraryCollectionAppTargetUi(appId, title, requestId),
            bulkAddTargetAppIds = null,
        )
        return requestId
    }

    fun publishTargetMemberships(appId: Int, requestId: Long, ids: Set<Long>) {
        val current = mutableState.value
        val target = current.addTarget ?: return
        if (target.appId != appId || target.requestId != requestId) return
        mutableState.value = current.copy(
            addTarget = target.copy(memberCollectionIds = ids),
        )
    }

    fun currentTargetRequestId(appId: Int): Long? =
        mutableState.value.addTarget?.takeIf { it.appId == appId }?.requestId

    fun updateTargetMembership(
        appId: Int,
        requestId: Long?,
        collectionId: Long,
        included: Boolean,
    ) {
        val current = mutableState.value
        val target = current.addTarget ?: return
        if (target.appId != appId || target.requestId != requestId) return
        val existing = target.memberCollectionIds ?: return
        mutableState.value = current.copy(
            addTarget = target.copy(
                memberCollectionIds = if (included) existing + collectionId
                    else existing - collectionId,
            ),
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
    fun onCreateCollectionForApp(appId: Int, name: String) = onCreateCollection(name)
    fun onRenameCollection(collectionId: Long, name: String)
    fun onDeleteCollection(collectionId: Long)
    fun onOpenCollection(collectionId: Long)
    fun onPrepareCollectionAppPicker()
    fun onPrepareSearchApps() = Unit
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
    fun onSetCollectionMembershipByDatabaseId(
        databaseId: Long,
        collectionId: Long,
        included: Boolean,
        callback: CollectionMembershipResultCallback,
    )
    fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long)
    fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long)
    fun onRemoveAppFromCollection(appId: Int, collectionId: Long)
}

/** Collection management is a child of Library, not a second application browser. */
@Composable
internal fun LibraryCollectionsManagementDestination(
    host: LibraryCollectionsHost,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState,
    selectedCollectionId: Long?,
    onBack: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    active: Boolean = true,
) {
    val state by host.collectionsStore().state.collectAsState()
    if (!state.ready) {
        LibraryCollectionsDestination(scaffoldPadding)
        return
    }
    BackHandler(enabled = active, onBack = onBack)
    LibraryCollectionsOverview(
        host = host,
        state = state,
        libraryState = libraryState,
        scaffoldPadding = scaffoldPadding,
        viewportState = viewportState,
        selectedCollectionId = selectedCollectionId,
        active = active,
        onOpenCollection = onOpenCollection,
        onNavigationVisibilityChanged = {},
        onBack = onBack,
    )
}

/** Single membership editor reused for every active Library collection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryCollectionMembershipEditor(
    host: LibraryCollectionsHost,
    collection: LibraryCollectionUiItem,
    members: LibraryCollectionMembersUi?,
    libraryState: LibraryUiState,
    viewportState: LibraryViewportState,
    onBack: () -> Unit,
) {
    val state by host.collectionsStore().state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var pending by remember(collection.id) { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    val memberIds = members?.memberIds.orEmpty()
    LaunchedEffect(members?.revision) {
        pending = pending.filter { (id, included) -> (id in memberIds) != included }
    }
    LaunchedEffect(collection.id) { host.onPrepareCollectionAppPicker() }
    BackHandler {
        if (imeVisible) keyboard?.hide() else onBack()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LibraryCollectionAppPicker(
            collection = collection,
            allApps = state.allApps,
            sourceRevision = state.allAppsRevision,
            memberIds = memberIds,
            pendingMemberships = pending,
            sortVariant = libraryState.sortVariant,
            iconRatio = libraryState.iconRatio,
            iconShape = libraryState.iconShape,
            enhancedIcons = libraryState.enhancedIcons,
            scaffoldPadding = PaddingValues(),
            viewportState = viewportState,
            loading = !state.allAppsPrepared || members == null,
            onBack = onBack,
            onSetMembership = { appId, included ->
                if (appId !in pending) {
                    pending = pending + (appId to included)
                    host.onSetCollectionMembershipByDatabaseId(
                        appId,
                        collection.id,
                        included,
                        CollectionMembershipResultCallback { success ->
                            if (!success && pending[appId] == included) {
                                pending = pending - appId
                            }
                        },
                    )
                }
            },
        )
    }
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
    onBack: (() -> Unit)? = null,
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(titleGate.positionModifier)
                .then(if (titleActionsEnabled) Modifier else Modifier.clearAndSetSemantics { }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = titleActionsEnabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.library_back),
                    )
                }
            }
            Text(
                text = stringResource(R.string.library_collection_manage),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                enabled = titleActionsEnabled,
                onClick = { if (currentActive) createDialog = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_create_folder),
                    contentDescription = stringResource(R.string.library_collection_new),
                )
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
                            TextButton(onClick = { if (currentActive) createDialog = true }) {
                                Text(stringResource(R.string.library_collection_new))
                            }
                        }
                    }
                }
            } else {
                items(state.collections, key = { it.id }) { collection ->
                    LibraryCollectionChoiceRow(
                        collection = collection,
                        selected = collection.id == selectedCollectionId,
                        onClick = { if (currentActive) onOpenCollection(collection.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        trailingContent = {
                            Box {
                                IconButton(onClick = { if (currentActive) actionsTarget = collection }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert),
                                        contentDescription = stringResource(
                                            R.string.library_collection_more_actions, collection.name,
                                        ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = active && actionsTarget?.id == collection.id,
                                    onDismissRequest = { actionsTarget = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_context_rename)) },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                                        },
                                        onClick = {
                                            actionsTarget = null
                                            renameTarget = collection
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.action_context_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painterResource(R.drawable.ic_delete),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            actionsTarget = null
                                            deleteTarget = collection
                                        },
                                    )
                                }
                            }
                        },
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
            text = {
                Text(stringResource(R.string.library_collection_delete_confirmation, collection.name))
            },
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

/** Collection dialogs live at LibraryScreen scope so Add-to-Collection works from any app browser. */
@Composable
internal fun LibraryCollectionsDialogHost(host: LibraryCollectionsHost) {
    val state by host.collectionsStore().state.collectAsState()
    var createForAdd by rememberSaveable { mutableStateOf(false) }

    state.addTarget?.takeUnless { createForAdd }?.let { target ->
        AddToCollectionDialog(
            target = target,
            collections = state.collections,
            onDismiss = host::onDismissAddToCollection,
            onCreate = { createForAdd = true },
            onSetMembership = { collectionId, included, callback ->
                host.onSetCollectionMembership(target.appId, collectionId, included, callback)
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
                val target = state.addTarget
                if (target == null) host.onCreateCollection(name)
                else {
                    host.onCreateCollectionForApp(target.appId, name)
                    host.onDismissAddToCollection()
                }
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
    onSetMembership: (Long, Boolean, CollectionMembershipResultCallback) -> Unit,
) {
    val maxListHeight = adaptiveDialogLayout().maxHeight
    var pending by remember(target.requestId) { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    val committed = target.memberCollectionIds
    LaunchedEffect(committed) {
        if (committed != null) {
            pending = pending.filter { (id, included) -> (id in committed) != included }
        }
    }
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_destination_collections)) },
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
                when {
                    committed == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    collections.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.library_collection_ready_empty_message),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        val canScrollForward = rememberLazyListCanScrollForward(listState)
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                state = listState,
                            ) {
                                items(collections, key = { it.id }) { collection ->
                                    val checked = pending[collection.id]
                                        ?: (collection.id in committed)
                                    val enabled = collection.id !in pending
                                    ListItem(
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent,
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
                                                pluralStringResource(
                                                    R.plurals.library_collection_member_count,
                                                    collection.appCount,
                                                    collection.appCount,
                                                ),
                                            )
                                        },
                                        trailingContent = {
                                            androidx.compose.material3.Checkbox(
                                                checked = checked,
                                                onCheckedChange = null,
                                                enabled = enabled,
                                                modifier = Modifier.clearAndSetSemantics {},
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().toggleable(
                                            value = checked,
                                            enabled = enabled,
                                            role = Role.Checkbox,
                                            onValueChange = { included ->
                                                pending = pending + (collection.id to included)
                                                onSetMembership(
                                                    collection.id,
                                                    included,
                                                    CollectionMembershipResultCallback { success ->
                                                        if (!success &&
                                                            pending[collection.id] == included
                                                        ) pending = pending - collection.id
                                                    },
                                                )
                                            },
                                        ),
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
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) {
                Text(stringResource(R.string.library_collection_new))
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
