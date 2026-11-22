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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import kotlin.math.roundToInt

/**
 * Preserve the normal compact layout while avoiding squeezed resolution labels at large text sizes.
 */
internal fun screenPresetColumnCount(availableWidthDp: Float, fontScale: Float): Int {
    val normalColumns = when {
        availableWidthDp >= 480f -> 3
        availableWidthDp >= 250f -> 2
        else -> 1
    }
    val tileWidth = 112f * fontScale.coerceAtLeast(1f)
    val fittingColumns = ((availableWidthDp + 8f) / (tileWidth + 8f)).toInt().coerceAtLeast(1)
    return minOf(normalColumns, fittingColumns)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenPresetDialog(
    presets: List<Size>,
    removablePresets: List<Size>,
    selectedPreset: Size?,
    onDismissRequest: () -> Unit,
    onSelected: (Size) -> Unit,
    onAdd: (Size) -> Unit,
    onRemove: (Size) -> Unit,
    useModalBottomSheet: Boolean = true,
) {
    var swapped by rememberSaveable { mutableStateOf(false) }
    var customResolutionVisible by rememberSaveable { mutableStateOf(false) }
    val swapToggleDescription = stringResource(R.string.config_swap_screen_size)
    val dialogLayout = adaptiveDialogLayout()
    val availablePresets = if (selectedPreset != null && !presets.contains(selectedPreset)) {
        listOf(selectedPreset) + presets
    } else {
        presets
    }

    val dialogContent: @Composable () -> Unit = {
        Surface(
            modifier = dialogLayout.modifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.config_select_screen_size),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (selectedPreset != null) {
                            Text(
                                text = stringResource(
                                    R.string.config_current_screen_size,
                                    selectedPreset.toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconToggleButton(
                        checked = swapped,
                        onCheckedChange = { swapped = it },
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .background(
                                if (swapped) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                CircleShape,
                            )
                            .semantics { contentDescription = swapToggleDescription },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap_horiz),
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.config_help_screen_size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = screenPresetColumnCount(
                            maxWidth.value,
                            LocalDensity.current.fontScale,
                        )
                        val tileWidth = (maxWidth - 8.dp * (columns - 1)) / columns
                        FlowRow(
                            maxItemsInEachRow = columns,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availablePresets.forEach { source ->
                                val display = if (swapped) Size(source.height, source.width) else source
                                val selected = display == selectedPreset
                                Surface(
                                    modifier = Modifier
                                        .width(tileWidth)
                                        .heightIn(min = 56.dp)
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = {
                                                onSelected(display)
                                                onDismissRequest()
                                            },
                                        ),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = display.toString(),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (removablePresets.contains(source)) {
                                            IconButton(
                                                modifier = Modifier.size(40.dp),
                                                onClick = { onRemove(source) },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_delete),
                                                    contentDescription = stringResource(R.string.remove_screen_preset),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { customResolutionVisible = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.config_custom_resolution))
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }

    if (!customResolutionVisible) {
        if (useModalBottomSheet) {
            Dialog(
                onDismissRequest = onDismissRequest,
                properties = dialogLayout.properties,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    dialogContent()
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                dialogContent()
            }
        }
    } else {
        CustomResolutionDialog(
            initialSize = selectedPreset,
            onDismissRequest = { customResolutionVisible = false },
            onSave = { size ->
                customResolutionVisible = false
                onAdd(size)
                onSelected(size)
                onDismissRequest()
            },
        )
    }
}

@Composable
internal fun CustomResolutionDialog(
    initialSize: Size?,
    onDismissRequest: () -> Unit,
    onSave: (Size) -> Unit,
) {
    val fallback = initialSize ?: Size(240, 320)
    var width by rememberSaveable(fallback.width, fallback.height) {
        mutableStateOf(fallback.width.toString())
    }
    var height by rememberSaveable(fallback.width, fallback.height) {
        mutableStateOf(fallback.height.toString())
    }
    var lockAspect by rememberSaveable(fallback.width, fallback.height) { mutableStateOf(false) }
    var aspectWidth by rememberSaveable(fallback.width, fallback.height) { mutableIntStateOf(fallback.width) }
    var aspectHeight by rememberSaveable(fallback.width, fallback.height) { mutableIntStateOf(fallback.height) }
    val validWidth = width.toIntOrNull()?.takeIf { it > 0 }
    val validHeight = height.toIntOrNull()?.takeIf { it > 0 }
    val valid = validWidth != null && validHeight != null
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_custom_resolution)) },
        text = {
            ConfigDialogBody(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { value ->
                            width = value
                            if (lockAspect) {
                                val newWidth = value.toIntOrNull()
                                if (newWidth != null && aspectWidth > 0) {
                                    height = (newWidth * aspectHeight.toFloat() / aspectWidth)
                                        .roundToInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.PREF_WIDTH)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val oldWidth = width
                        width = height
                        height = oldWidth
                        val oldAspectWidth = aspectWidth
                        aspectWidth = aspectHeight
                        aspectHeight = oldAspectWidth
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap_horiz),
                            contentDescription = stringResource(R.string.SWAP_SIZES),
                        )
                    }
                    OutlinedTextField(
                        value = height,
                        onValueChange = { value ->
                            height = value
                            if (lockAspect) {
                                val newHeight = value.toIntOrNull()
                                if (newHeight != null && aspectHeight > 0) {
                                    width = (newHeight * aspectWidth.toFloat() / aspectHeight)
                                        .roundToInt().toString()
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.PREF_HEIGHT)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                ConfigSwitchPreference(
                    title = stringResource(R.string.config_lock_custom_resolution_ratio),
                    description = stringResource(R.string.config_help_lock_custom_resolution_ratio),
                    checked = lockAspect,
                    onCheckedChange = { checked ->
                        if (checked) {
                            width.toIntOrNull()?.takeIf { it > 0 }?.let { aspectWidth = it }
                            height.toIntOrNull()?.takeIf { it > 0 }?.let { aspectHeight = it }
                        }
                        lockAspect = checked
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(Size(validWidth!!, validHeight!!)) },
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@Composable
internal fun FontSizesDialog(
    small: String,
    medium: String,
    large: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var smallDraft by remember(small) { mutableStateOf(small) }
    var mediumDraft by remember(medium) { mutableStateOf(medium) }
    var largeDraft by remember(large) { mutableStateOf(large) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minimumThreeFieldWidth = (132f * 3 * fontScale).dp + 24.dp
    val useHorizontalFields = adaptiveDialogLayout().width >= minimumThreeFieldWidth
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_font_sizes)) },
        text = {
            ConfigDialogBody(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_help_font_sizes_long),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (useHorizontalFields) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FontSizeField(smallDraft, { smallDraft = it }, R.string.PREF_FONT_SMALL, Modifier.weight(1f))
                        FontSizeField(mediumDraft, { mediumDraft = it }, R.string.PREF_FONT_MEDIUM, Modifier.weight(1f))
                        FontSizeField(largeDraft, { largeDraft = it }, R.string.PREF_FONT_LARGE, Modifier.weight(1f))
                    }
                } else {
                    FontSizeField(smallDraft, { smallDraft = it }, R.string.PREF_FONT_SMALL)
                    FontSizeField(mediumDraft, { mediumDraft = it }, R.string.PREF_FONT_MEDIUM)
                    FontSizeField(largeDraft, { largeDraft = it }, R.string.PREF_FONT_LARGE)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = smallDraft.toIntOrNull() != null &&
                    mediumDraft.toIntOrNull() != null && largeDraft.toIntOrNull() != null,
                onClick = { onConfirm(smallDraft, mediumDraft, largeDraft) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun FontSizeField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
