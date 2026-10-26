/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.filepicker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog

/** Show transparency without cropping or trimming the source canvas. */
@Composable
private fun artworkBackground(): Modifier {
    val light = MaterialTheme.colorScheme.surfaceContainerLowest
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    return Modifier.drawBehind {
        drawRect(light)
        val tile = 10.dp.toPx()
        for (y in 0..(size.height / tile).toInt()) {
            for (x in 0..(size.width / tile).toInt()) {
                if ((x + y) % 2 == 0) {
                    drawRect(dark, topLeft = Offset(x * tile, y * tile),
                        size = androidx.compose.ui.geometry.Size(tile, tile))
                }
            }
        }
    }
}

@Composable
internal fun FilePickerThumbnail(
    entry: FilePickerEntry,
    loader: FilePickerImageLoader,
    onPreview: () -> Unit,
) {
    val pixels = with(LocalDensity.current) { 56.dp.roundToPx() }
    var bitmap by remember(entry.path, pixels, loader) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.path, pixels, loader) {
        bitmap = loader.load(entry.path, pixels)
    }
    val description = stringResource(R.string.file_picker_preview_named, entry.name)
    Box(
        modifier = Modifier.size(56.dp)
            .clickable(role = Role.Button, onClick = onPreview)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().then(artworkBackground()))
        } else {
            Icon(painterResource(R.drawable.ic_file_picker_file), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
internal fun FilePickerImagePreviewDialog(
    entry: FilePickerEntry,
    loader: FilePickerImageLoader,
    onDismiss: () -> Unit,
    onChoose: () -> Unit,
) {
    val pixels = with(LocalDensity.current) { 420.dp.roundToPx().coerceAtMost(1024) }
    var bitmap by remember(entry.path, pixels, loader) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(entry.path, pixels, loader) { mutableStateOf(false) }
    LaunchedEffect(entry.path, pixels, loader) {
        bitmap = loader.load(entry.path, pixels)
        loaded = true
    }
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("file_picker_preview_dialog"),
        title = { Text(entry.name, maxLines = 2) },
        textScrollable = false,
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp)
                    .aspectRatio(1.2f).then(artworkBackground()),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    !loaded -> {
                        val loadingLabel = stringResource(R.string.file_picker_preview_loading)
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp)
                                .semantics { contentDescription = loadingLabel },
                        )
                    }
                    else -> Text(stringResource(R.string.file_picker_preview_unavailable))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.file_picker_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = onChoose,
                modifier = Modifier.testTag("file_picker_preview_choose"),
            ) { Text(stringResource(R.string.file_picker_choose)) }
        },
    )
}
