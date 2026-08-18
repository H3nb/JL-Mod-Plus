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

package ru.playsoftware.j2meloader.applist

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R

@Composable
internal fun LibraryMetadataEditorDialog(
    app: LibraryAppUiItem,
    onDismiss: () -> Unit,
    onConfirm: (title: String, vendor: String, version: String, description: String) -> Unit,
) {
    var title by rememberSaveable(app.id, app.title) { mutableStateOf(app.title) }
    var vendor by rememberSaveable(app.id, app.author) { mutableStateOf(app.author) }
    var version by rememberSaveable(app.id, app.version) { mutableStateOf(app.version) }
    var description by rememberSaveable(app.id, app.description) { mutableStateOf(app.description) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxContentHeight = (configuration.screenHeightDp - 220).coerceAtLeast(180).dp
    val valid = title.trim().isNotEmpty()

    AlertDialog(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_metadata_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LibraryMetadataField(
                    label = stringResource(R.string.library_metadata_name),
                    value = title,
                    sourceValue = app.sourceTitle,
                    onValueChange = { title = it },
                    singleLine = true,
                )
                LibraryMetadataField(
                    label = stringResource(R.string.library_metadata_vendor),
                    value = vendor,
                    sourceValue = app.sourceAuthor,
                    onValueChange = { vendor = it },
                    singleLine = true,
                )
                LibraryMetadataField(
                    label = stringResource(R.string.library_metadata_version),
                    value = version,
                    sourceValue = app.sourceVersion,
                    onValueChange = { version = it },
                    singleLine = true,
                )
                LibraryMetadataField(
                    label = stringResource(R.string.library_metadata_description),
                    value = description,
                    sourceValue = app.sourceDescription,
                    onValueChange = { description = it },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(title, vendor, version, description)
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
private fun LibraryMetadataField(
    label: String,
    value: String,
    sourceValue: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 6,
        )
        TextButton(
            enabled = value.trim() != sourceValue,
            onClick = { onValueChange(sourceValue) },
        ) {
            Text(stringResource(R.string.library_metadata_reset_to_original))
        }
    }
}
