/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import io.github.h3nb.jlmodplus.ui.InlineSearchField

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.h3nb.jlmodplus.R

@Composable
internal fun LibraryCollectionAppPicker(
    collection: LibraryCollectionUiItem,
    allApps: List<LibraryAppUiItem>,
    sourceRevision: Long = 0L,
    memberIds: Set<Long>,
    pendingMemberships: Map<Long, Boolean> = emptyMap(),
    sortVariant: Int,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean = true,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState = rememberLibraryViewportState(collectionId = collection.id),
    loading: Boolean = false,
    active: Boolean = true,
    onBack: () -> Unit,
    onSetMembership: (Long, Boolean) -> Unit,
) {
    var query by viewportState.queryState
    val searchScope = rememberCoroutineScope()
    val visibleApps = rememberCollectionAppsProjection(
        viewportState, allApps, sortVariant, ready = !loading,
        sourceRevision = sourceRevision.takeIf { it != 0L },
    )
    val searchDismissFocus = remember { FocusRequester() }
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
            IconButton(
                onClick = onBack,
                modifier = Modifier.focusRequester(searchDismissFocus).focusProperties { canFocus = true },
            ) {
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
        InlineSearchField(
            query = query,
            onQueryChange = {
                query = it
                if (viewportState.listState.firstVisibleItemIndex != 0 ||
                    viewportState.listState.firstVisibleItemScrollOffset != 0
                ) {
                    searchScope.launch { viewportState.listState.scrollToItem(0) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            onDismissInput = { searchDismissFocus.requestFocus() },
            enabled = active,
        )
        // Filtering may continue on Default; keep the search field and IME focus mounted
        // while only the results pane waits for its matching projection.
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
        LazyColumn(modifier = Modifier.fillMaxSize(), state = viewportState.listState) {
            items(visibleApps, key = { it.databaseId }) { app ->
                val pending = pendingMemberships[app.databaseId]
                val checked = pending ?: (app.databaseId in memberIds)
                val enabled = pending == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collection-membership-${app.databaseId}")
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = { next -> onSetMembership(app.databaseId, next) },
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
    sourceRevision: Long? = null,
): List<LibraryAppUiItem>? {
    val query = viewportState.query
    LaunchedEffect(viewportState, sourceRevision ?: source, query, sortVariant, ready) {
        if (!ready) return@LaunchedEffect
        if (viewportState.collectionProjection?.matches(source, query, sortVariant) == true) {
            return@LaunchedEffect
        }
        val apps = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            projectCollectionApps(source, query, sortVariant) { context.ensureActive() }
        }
        viewportState.collectionProjection = LibraryCollectionProjection(source, query, sortVariant, apps)
    }
    return viewportState.collectionProjection?.takeIf { it.matches(source, query, sortVariant) }?.apps
}

