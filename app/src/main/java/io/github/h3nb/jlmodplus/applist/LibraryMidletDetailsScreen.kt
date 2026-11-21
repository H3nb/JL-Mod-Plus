/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryMidletDetailsData
import io.github.h3nb.jlmodplus.librarydb.LibraryPlayStatsFormatter
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private sealed interface MidletDetailsLoad {
    data object Loading : MidletDetailsLoad
    data object Unavailable : MidletDetailsLoad
    data class Ready(val value: LibraryMidletDetailsData) : MidletDetailsLoad
}

@Composable
internal fun LibraryMidletDetailsScreen(
    app: LibraryAppUiItem,
    libraryScope: String,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
    onBack: () -> Unit,
    onEditMetadata: (() -> Unit)?,
    onLoad: (LibraryViewModel.MutationCallback<LibraryMidletDetailsData>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val loaded by produceState<MidletDetailsLoad>(
        MidletDetailsLoad.Loading, app.databaseId, libraryScope,
    ) {
        value = suspendCancellableCoroutine { continuation ->
            onLoad(LibraryViewModel.MutationCallback { data, _ ->
                if (continuation.isActive) {
                    continuation.resume(
                        if (data == null) MidletDetailsLoad.Unavailable
                        else MidletDetailsLoad.Ready(data),
                    )
                }
            })
        }
    }
    val technical = (loaded as? MidletDetailsLoad.Ready)?.value
    val attrs = technical?.descriptorAttributes
    val entryPoints = attrs?.entries?.filter {
        it.key.startsWith("MIDlet-") &&
            it.key.removePrefix("MIDlet-").toIntOrNull() != null
    }.orEmpty()
    var advancedExpanded by rememberSaveable(app.databaseId) { mutableStateOf(false) }
    val attributes = attrs?.toList().orEmpty()
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val unavailable = stringResource(R.string.library_details_unavailable)
    val dateLabel: (Long?) -> String = { timestamp ->
        timestamp?.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) } ?: unavailable
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .widthIn(max = 880.dp).fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = stringResource(R.string.library_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onEditMetadata != null) {
                    IconButton(onClick = onEditMetadata) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.library_metadata_edit_title),
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "identity") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        LibraryIconSlot(
                            app = app,
                            modifier = Modifier.size(72.dp),
                            contentSize = 64.dp,
                            iconRatio = iconRatio,
                            iconShape = iconShape,
                            enhancedIcons = enhancedIcons,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.title,
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.library_details_version_value, app.version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (app.description.isNotBlank()) {
                    item(key = "description") {
                        DetailsSection(R.string.library_details_description) {
                            Text(app.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item(key = "play") {
                    DetailsSection(R.string.library_details_play_stats) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailsStat(
                                stringResource(R.string.library_details_launches),
                                app.playCount.toString(),
                                Modifier.weight(1f),
                            )
                            DetailsStat(
                                stringResource(R.string.library_details_total_time),
                                LibraryPlayStatsFormatter.duration(app.totalPlayTimeMs),
                                Modifier.weight(1f),
                            )
                        }
                        DetailsValueRow(
                            stringResource(R.string.library_details_last_played),
                            dateLabel(app.lastPlayedAt),
                        )
                    }
                }
                item(key = "library") {
                    DetailsSection(R.string.library_details_library_info) {
                        DetailsValueRow(
                            stringResource(R.string.library_details_added),
                            dateLabel(app.addedAt),
                        )
                        DetailsValueRow(
                            stringResource(R.string.library_details_collections),
                            technical?.collectionNames?.joinToString(", ")?.ifBlank {
                                stringResource(R.string.library_details_none)
                            } ?: unavailable,
                        )
                    }
                }
                item(key = "technical") {
                    DetailsSection(R.string.library_details_technical) {
                        when (loaded) {
                            MidletDetailsLoad.Loading -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                            )
                            MidletDetailsLoad.Unavailable -> Text(
                                unavailable,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is MidletDetailsLoad.Ready -> {
                                DetailsValueRow(
                                    stringResource(R.string.library_details_profile),
                                    attrs?.get("MicroEdition-Profile") ?: unavailable,
                                )
                                DetailsValueRow(
                                    stringResource(R.string.library_details_configuration),
                                    attrs?.get("MicroEdition-Configuration") ?: unavailable,
                                )
                                DetailsValueRow(
                                    stringResource(R.string.library_details_jar_size),
                                    technical?.retainedJarSizeBytes?.let {
                                        Formatter.formatShortFileSize(context, it)
                                    } ?: unavailable,
                                )
                                if (entryPoints.isNotEmpty()) {
                                    DetailsValueRow(
                                        stringResource(R.string.library_details_entry_points),
                                        entryPoints.joinToString("\n") { it.value },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "source") {
                    DetailsSection(R.string.library_details_original_metadata) {
                        DetailsValueRow(stringResource(R.string.library_details_name), app.sourceTitle)
                        DetailsValueRow(stringResource(R.string.library_details_vendor), app.sourceAuthor)
                        DetailsValueRow(stringResource(R.string.library_details_version), app.sourceVersion)
                        if (app.sourceDescription.isNotBlank() &&
                            app.sourceDescription != app.description) {
                            DetailsValueRow(
                                stringResource(R.string.library_details_original_description),
                                app.sourceDescription,
                            )
                        }
                    }
                }
                item(key = "permissions") {
                    DetailsSection(R.string.library_details_permissions) {
                        if (attrs == null) {
                            Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val required = attrs["MIDlet-Permissions"].orEmpty()
                            val optional = attrs["MIDlet-Permissions-Opt"].orEmpty()
                            if (required.isBlank() && optional.isBlank()) {
                                Text(
                                    stringResource(R.string.library_details_no_permissions),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                if (required.isNotBlank()) {
                                    DetailsValueRow(
                                        stringResource(R.string.library_details_required),
                                        required.split(",").joinToString("\n") { it.trim() },
                                    )
                                }
                                if (optional.isNotBlank()) {
                                    DetailsValueRow(
                                        stringResource(R.string.library_details_optional),
                                        optional.split(",").joinToString("\n") { it.trim() },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "advanced_heading") {
                    TextButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        enabled = attributes.isNotEmpty(),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (advancedExpanded) R.drawable.ic_keyboard_arrow_up
                                else R.drawable.ic_keyboard_arrow_down,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(R.string.library_details_descriptor_attributes))
                    }
                }
                if (advancedExpanded) {
                    items(attributes, key = { it.first }) { (name, value) ->
                        DetailsValueRow(name, value)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsSection(
    title: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = { content() },
            )
        }
    }
}

@Composable
private fun DetailsStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailsValueRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
