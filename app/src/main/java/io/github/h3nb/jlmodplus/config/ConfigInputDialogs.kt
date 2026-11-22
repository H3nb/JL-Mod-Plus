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

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ConfigDialogBody(
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The shared dialog owns scrolling and measures the space left by its title/actions.
    Column(Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement, content = content)
}

@Composable
internal fun ConfigNumberDialog(
    title: String,
    description: String? = null,
    initialValue: String,
    label: String?,
    keyboardType: KeyboardType,
    valueSuffix: String? = null,
    resetValue: String? = null,
    validIntegerRange: IntRange? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val canConfirm = validIntegerRange == null ||
        draft.toIntOrNull()?.let { it in validIntegerRange } == true
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            ConfigDialogBody(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = label?.let { value ->
                        { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    suffix = valueSuffix?.let { suffix -> { Text(suffix) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                )
            }
        },
        dismissButton = {
            Row {
                if (resetValue != null) {
                    TextButton(onClick = { draft = resetValue }) {
                        Text(stringResource(R.string.reset))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = canConfirm) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConfigSliderDialog(
    title: String,
    description: String? = null,
    initialValue: Int,
    valueRange: IntRange,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftText by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            ConfigDialogBody(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val draftValue = draftText.toIntOrNull()
                val currentValue = (draftValue ?: initialValue).coerceIn(valueRange)
                Slider(
                    value = currentValue.toFloat(),
                    onValueChange = { value -> draftText = value.roundToInt().toString() },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    // Keep the control visually continuous. A step for every integer makes
                    // Material3 draw hundreds of tick marks, which turns the track into a
                    // distracting double line for ranges such as 0..255.
                    steps = 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),

                )
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    label = { Text(stringResource(R.string.config_current_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(valueRange.first.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(valueRange.last.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Reserve a small clear area for the automatic scroll affordance so it never
                // obscures the range labels when the dialog is height-constrained.
                Spacer(Modifier.height(28.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    draftText.toIntOrNull()?.let { onConfirm(it.coerceIn(valueRange)) }
                },
                enabled = draftText.toIntOrNull() != null,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
internal fun ConfigChoiceDialog(
    title: String,
    description: String? = null,
    selected: String,
    options: List<String>,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
    onImport: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val maxListHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val listState = rememberLazyListState()
                val canScrollForward = rememberLazyListCanScrollForward(listState)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        state = listState,
                    ) {
                        itemsIndexed(options) { index, option ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = option,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = option == selected,
                                        onClick = null,
                                    )
                                },
                                trailingContent = {
                                    if (index > 0 && (onRename != null || onDelete != null)) {
                                        var expanded by remember(option) { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { expanded = true }) {
                                                Icon(
                                                    painterResource(R.drawable.ic_more_vert),
                                                    contentDescription = stringResource(R.string.profile_more_actions, option),
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false },
                                            ) {
                                                if (onRename != null) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.action_context_rename)) },
                                                        onClick = {
                                                            expanded = false
                                                            onRename(option)
                                                        },
                                                    )
                                                }
                                                if (onDelete != null) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.action_context_delete)) },
                                                        onClick = {
                                                            expanded = false
                                                            onDelete(option)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == selected,
                                        role = Role.RadioButton,
                                        onClick = { onSelected(index) },
                                    ),
                            )
                        }
                    }
                    ScrollableContentHint(
                        visible = canScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (onImport != null) {
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.config_asset_add))
                    }
                    Text(
                        text = stringResource(R.string.config_asset_copy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = null,
    )
}
