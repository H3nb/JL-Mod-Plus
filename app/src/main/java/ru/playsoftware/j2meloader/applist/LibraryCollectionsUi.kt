/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.applist

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.librarydb.LibraryCollectionRow
import ru.playsoftware.j2meloader.librarydb.LibraryQuickView

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
    val members: LibraryCollectionMembersUi? = null,
    val addTarget: LibraryCollectionAppTargetUi? = null,
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

    fun dismissMembers() {
        mutableState.value = mutableState.value.copy(members = null)
    }

    fun showAddTarget(appId: Int, title: String) {
        mutableState.value = mutableState.value.copy(
            addTarget = LibraryCollectionAppTargetUi(appId, title),
        )
    }

    fun dismissAddTarget() {
        mutableState.value = mutableState.value.copy(addTarget = null)
    }
}

/** Extra capabilities implemented only by the production Library host. */
interface LibraryCollectionsHost : LibraryActions {
    fun collectionsStore(): LibraryCollectionsUiStore
    fun onCreateCollection(name: String)
    fun onRenameCollection(collectionId: Long, name: String)
    fun onDeleteCollection(collectionId: Long)
    fun onOpenCollection(collectionId: Long)
    fun onDismissCollectionMembers()
    fun onRequestAddToCollection(appId: Int)
    fun onDismissAddToCollection()
    fun onAddAppToCollection(appId: Int, collectionId: Long)
    fun onRemoveAppFromCollection(appId: Int, collectionId: Long)
}

/** READY Collections destination. Opening a collection reuses the main Library browser. */
@Composable
internal fun LibraryCollectionsDestination(
    host: LibraryCollectionsHost,
    libraryState: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onOpenActions: (LibraryAppUiItem, Long) -> Unit,
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
        BackHandler(onBack = host::onDismissCollectionMembers)
        var query by rememberSaveable(openCollection.id) { mutableStateOf("") }
        val projectedMembers by produceState(
            initialValue = members.members,
            members.members,
            query,
            libraryState.sortVariant,
        ) {
            value = withContext(Dispatchers.Default) {
                projectCollectionMembers(members.members, query, libraryState.sortVariant)
            }
        }
        val browserState = libraryState.copy(
            loading = false,
            apps = projectedMembers,
            appliedFilter = query,
            quickView = LibraryQuickView.All,
            databaseControlsReady = true,
            errorMessage = null,
        )
        LibraryAppsDestination(
            state = browserState,
            scaffoldPadding = scaffoldPadding,
            onOpenApp = host::onOpenApp,
            onOpenActions = { app -> onOpenActions(app, openCollection.id) },
            onSearch = { query = it },
            onQuickView = {},
            onFavorite = host::onFavorite,
            onSort = host::onSort,
            onRetry = {},
            onFabVisibilityChanged = {},
            onNavigationVisibilityChanged = {},
            title = openCollection.name,
            onBack = host::onDismissCollectionMembers,
            showQuickViews = false,
            queryStateKey = openCollection.id,
        )
        return
    }

    var createDialog by rememberSaveable { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryCollectionUiItem?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        item {
            Row(
                modifier = Modifier
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
                TextButton(onClick = { createDialog = true }) {
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

private fun projectCollectionMembers(
    rows: List<LibraryAppUiItem>,
    filter: String,
    sortVariant: Int,
    locale: Locale = Locale.getDefault(),
): List<LibraryAppUiItem> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    val ranked = rows.mapNotNull { row ->
        val rank = if (needle.isEmpty()) {
            0
        } else {
            collectionSearchRank(row, needle) ?: return@mapNotNull null
        }
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
                                        stringResource(
                                            R.string.library_collection_member_count,
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
