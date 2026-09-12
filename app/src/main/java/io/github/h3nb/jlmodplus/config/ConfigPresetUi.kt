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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

private data class PresetChoice(
    val name: String,
    val isBuiltIn: Boolean,
    val hasKeyboardLayout: Boolean,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: Int = 0,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GameSetupSummary(
    state: ConfigUiState,
    events: ConfigFormEvents,
) {
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    var saveDialogVisible by rememberSaveable { mutableStateOf(false) }
    var confirmationTarget by remember { mutableStateOf<PresetChoice?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.preset_game_setup),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = setupSummary(state.form),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provenanceSummary(state.profileStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Button(
                    onClick = { pickerVisible = true },
                ) {
                    Text(stringResource(R.string.preset_use))
                }
                TextButton(
                    onClick = { saveDialogVisible = true },
                ) {
                    Text(stringResource(R.string.preset_save_as))
                }
            }
            if (state.hasPreviousSetup) {
                TextButton(
                    onClick = events::onRestorePreviousSetup,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(stringResource(R.string.preset_restore_previous))
                }
            }
        }
    }

    if (pickerVisible) {
        PresetPickerDialog(
            templates = state.profileTemplates,
            onDismissRequest = { pickerVisible = false },
            onManage = {
                pickerVisible = false
                events.onManageProfiles()
            },
            onSelected = { choice ->
                pickerVisible = false
                confirmationTarget = choice
            },
        )
    }

    confirmationTarget?.let { choice ->
        PresetApplyConfirmationDialog(
            choice = choice,
            onDismissRequest = { confirmationTarget = null },
            onConfirm = {
                confirmationTarget = null
                if (choice.isBuiltIn) events.onApplyBuiltInTemplate()
                else events.onApplyTemplate(choice.name)
            },
        )
    }

    if (saveDialogVisible) {
        SavePresetDialog(
            templates = state.profileTemplates,
            hasKeyboardLayout = state.hasKeyboardLayout,
            onDismissRequest = { saveDialogVisible = false },
            onConfirm = { name, includeKeyboard ->
                saveDialogVisible = false
                events.onSaveTemplate(name, includeKeyboard)
            },
        )
    }
}

@Composable
private fun setupSummary(form: ConfigFormState): String {
    val orientations = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
    val orientation = orientations.getOrElse(form.orientation) { orientations.firstOrNull().orEmpty() }
    val keyboard = stringResource(
        if (form.showKeyboard) R.string.preset_virtual_keyboard_on
        else R.string.preset_virtual_keyboard_off,
    )
    return "${form.screenWidth} × ${form.screenHeight} · $orientation · $keyboard"
}

@Composable
private fun provenanceSummary(status: ConfigUiState.ProfileStatus): String = when {
    status.activeProfile != null -> stringResource(R.string.preset_active, status.activeProfile)
    status.modified && status.sourceProfile != null ->
        stringResource(R.string.preset_based_on_modified, status.sourceProfile)
    status.builtInDefault -> stringResource(R.string.preset_builtin)
    else -> stringResource(R.string.preset_custom)
}

@Composable
private fun PresetPickerDialog(
    templates: List<ConfigUiState.ProfileTemplate>,
    onDismissRequest: () -> Unit,
    onManage: () -> Unit,
    onSelected: (PresetChoice) -> Unit,
) {
    val maxHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_picker_title)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "__builtin__") {
                        PresetPickerRow(
                            name = stringResource(R.string.preset_builtin),
                            summary = stringResource(R.string.preset_builtin_summary),
                            onClick = {
                                onSelected(PresetChoice("", true, false))
                            },
                        )
                    }
                    items(templates, key = { it.name }) { template ->
                        PresetPickerRow(
                            name = template.name,
                            summary = presetDescription(template),
                            onClick = {
                                onSelected(
                                    PresetChoice(
                                        template.name,
                                        false,
                                        template.hasKeyboardLayout,
                                        template.screenWidth,
                                        template.screenHeight,
                                        template.orientation,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onManage) { Text(stringResource(R.string.preset_manage)) }
        },
    )
}

@Composable
private fun presetDescription(template: ConfigUiState.ProfileTemplate): String {
    val orientations = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
    val orientation = orientations.getOrNull(template.orientation)
    return buildString {
        if (template.screenWidth > 0 && template.screenHeight > 0) {
            append(template.screenWidth).append(" × ").append(template.screenHeight)
            if (orientation != null) append(" · ").append(orientation)
        } else {
            append(stringResource(R.string.preset_game_settings))
        }
        if (template.hasKeyboardLayout) {
            append(" · ").append(stringResource(R.string.preset_includes_keyboard_layout))
        }
    }
}

@Composable
private fun PresetPickerRow(
    name: String,
    summary: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetApplyConfirmationDialog(
    choice: PresetChoice,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val body = when {
        choice.isBuiltIn -> stringResource(R.string.preset_apply_builtin_message)
        choice.hasKeyboardLayout -> stringResource(R.string.preset_apply_with_keyboard_message)
        else -> stringResource(R.string.preset_apply_without_keyboard_message)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = if (choice.isBuiltIn) stringResource(R.string.preset_builtin) else choice.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = { Text(body) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.preset_apply)) }
        },
    )
}

@Composable
private fun SavePresetDialog(
    templates: List<ConfigUiState.ProfileTemplate>,
    hasKeyboardLayout: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var includeKeyboard by rememberSaveable(hasKeyboardLayout) { mutableStateOf(hasKeyboardLayout) }
    val trimmed = value.trim()
    val duplicate = templates.any { it.name.equals(trimmed, ignoreCase = true) }
    val invalidCharacters = trimmed.any { it in "/\\:*?\"<>|" }
    val valid = trimmed.isNotEmpty() && !duplicate && !invalidCharacters
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_save_as)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_name)) },
                    isError = value.isNotEmpty() && !valid,
                    supportingText = if (value.isNotEmpty() && !valid) {
                        {
                            Text(
                                when {
                                    duplicate -> stringResource(R.string.preset_name_exists)
                                    invalidCharacters -> stringResource(R.string.preset_invalid_name)
                                    else -> stringResource(R.string.error_name)
                                },
                            )
                        }
                    } else null,
                )
                if (hasKeyboardLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeKeyboard = !includeKeyboard }
                            .semantics { role = Role.Checkbox }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeKeyboard,
                            onCheckedChange = null,
                        )
                        Text(stringResource(R.string.preset_include_keyboard_layout))
                    }
                }
                Text(
                    text = stringResource(R.string.preset_save_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(trimmed, includeKeyboard) },
            ) { Text(stringResource(R.string.save)) }
        },
    )
}
