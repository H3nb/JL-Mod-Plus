/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.applist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

/** Full-page presentation-metadata editor. Source descriptor values remain immutable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryMetadataEditorScreen(
    app: LibraryAppUiItem,
    onBack: () -> Unit,
    onSave: (title: String, vendor: String, description: String) -> Unit,
    onPickIcon: () -> Unit = {},
    onResetIcon: () -> Unit = {},
) {
    var title by rememberSaveable(app.id) { mutableStateOf(app.title) }
    var vendor by rememberSaveable(app.id) { mutableStateOf(app.author) }
    var description by rememberSaveable(app.id) { mutableStateOf(app.description) }
    val valid = title.trim().isNotEmpty()

    BackHandler(onBack = onBack)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_metadata_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            onSave(
                                title.trim(),
                                vendor.trim(),
                                description.trim(),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.library_metadata_save))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    LibraryMetadataIconPanel(
                        app = app,
                        onPickIcon = onPickIcon,
                        onResetIcon = onResetIcon,
                        modifier = Modifier.width(220.dp),
                    )
                    LibraryMetadataFields(
                        title = title,
                        vendor = vendor,
                        description = description,
                        app = app,
                        onTitleChange = { title = it },
                        onVendorChange = { vendor = it },
                        onDescriptionChange = { description = it },
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 760.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    LibraryMetadataIconPanel(
                        app = app,
                        onPickIcon = onPickIcon,
                        onResetIcon = onResetIcon,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LibraryMetadataFields(
                        title = title,
                        vendor = vendor,
                        description = description,
                        app = app,
                        onTitleChange = { title = it },
                        onVendorChange = { vendor = it },
                        onDescriptionChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun LibraryMetadataIconPanel(
    app: LibraryAppUiItem,
    onPickIcon: () -> Unit,
    onResetIcon: () -> Unit,
    modifier: Modifier,
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(132.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!,
                        contentDescription = null,
                        modifier = Modifier.size(112.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_default_midlet),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.library_metadata_icon),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPickIcon) {
                Text(stringResource(R.string.library_metadata_change_icon))
            }
            TextButton(onClick = onResetIcon) {
                Text(stringResource(R.string.library_metadata_reset_to_original))
            }
        }
    }
}

@Composable
private fun LibraryMetadataFields(
    title: String,
    vendor: String,
    description: String,
    app: LibraryAppUiItem,
    onTitleChange: (String) -> Unit,
    onVendorChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryMetadataField(
            label = R.string.library_metadata_name,
            value = title,
            source = app.sourceTitle,
            onValueChange = onTitleChange,
            singleLine = true,
            required = true,
        )
        LibraryMetadataField(
            label = R.string.library_metadata_vendor,
            value = vendor,
            source = app.sourceAuthor,
            onValueChange = onVendorChange,
            singleLine = true,
        )
        LibraryMetadataField(
            label = R.string.library_metadata_description,
            value = description,
            source = app.sourceDescription,
            onValueChange = onDescriptionChange,
            singleLine = false,
        )
    }
}

private fun decodeMetadataIcon(path: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }
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
            minLines = if (singleLine) 1 else 5,
            maxLines = if (singleLine) 1 else 12,
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
