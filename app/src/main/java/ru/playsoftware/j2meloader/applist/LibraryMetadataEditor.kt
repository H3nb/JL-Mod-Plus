/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.applist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.R

/** Full presentation-metadata editor. Source descriptor values remain immutable. */
@Composable
internal fun LibraryMetadataEditorDialog(
    app: LibraryAppUiItem,
    onDismiss: () -> Unit,
    onConfirm: (title: String, vendor: String, version: String, description: String) -> Unit,
    onPickIcon: () -> Unit = {},
    onResetIcon: () -> Unit = {},
) {
    var title by rememberSaveable(app.id, app.title) { mutableStateOf(app.title) }
    var vendor by rememberSaveable(app.id, app.author) { mutableStateOf(app.author) }
    var version by rememberSaveable(app.id, app.version) { mutableStateOf(app.version) }
    var description by rememberSaveable(app.id, app.description) { mutableStateOf(app.description) }
    val valid = title.trim().isNotEmpty()
    val layout = metadataDialogLayout()

    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_metadata_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LibraryMetadataIconField(
                    app = app,
                    onPickIcon = onPickIcon,
                    onResetIcon = onResetIcon,
                )
                LibraryMetadataField(
                    label = R.string.library_metadata_name,
                    value = title,
                    source = app.sourceTitle,
                    onValueChange = { title = it },
                    singleLine = true,
                    required = true,
                )
                LibraryMetadataField(
                    label = R.string.library_metadata_vendor,
                    value = vendor,
                    source = app.sourceAuthor,
                    onValueChange = { vendor = it },
                    singleLine = true,
                )
                LibraryMetadataField(
                    label = R.string.library_metadata_version,
                    value = version,
                    source = app.sourceVersion,
                    onValueChange = { version = it },
                    singleLine = true,
                )
                LibraryMetadataField(
                    label = R.string.library_metadata_description,
                    value = description,
                    source = app.sourceDescription,
                    onValueChange = { description = it },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        title.trim(),
                        vendor.trim(),
                        version.trim(),
                        description.trim(),
                    )
                },
            ) {
                Text(stringResource(android.R.string.ok))
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
private fun LibraryMetadataIconField(
    app: LibraryAppUiItem,
    onPickIcon: () -> Unit,
    onResetIcon: () -> Unit,
) {
    val iconBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        app.iconPath,
        app.iconRevision,
    ) {
        val path = app.iconPath?.takeIf(String::isNotBlank)
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeMetadataIcon(path)?.asImageBitmap() }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_default_midlet),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.library_metadata_icon),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.library_metadata_icon_note),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onPickIcon) {
                    Text(stringResource(R.string.library_metadata_change_icon))
                }
                TextButton(onClick = onResetIcon) {
                    Text(stringResource(R.string.library_metadata_reset_to_original))
                }
            }
        }
    }
}

private fun decodeMetadataIcon(path: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
} catch (_: OutOfMemoryError) {
    null
} catch (_: RuntimeException) {
    null
}

@Composable
private fun LibraryMetadataField(
    label: Int,
    value: String,
    source: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    required: Boolean = false,
) {
    val changed = value.trim() != source
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(label)) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 6,
            isError = required && value.trim().isEmpty(),
        )
        TextButton(
            enabled = changed,
            onClick = { onValueChange(source) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.library_metadata_reset_to_original))
        }
    }
}
