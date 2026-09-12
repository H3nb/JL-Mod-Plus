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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

private const val BUILT_IN_PRESET_KEY = "__builtin__"

private data class PresetChoice(
    val name: String,
    val isBuiltIn: Boolean,
    val hasKeyboardLayout: Boolean,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: Int = 0,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetSummary(
    state: ConfigUiState,
    onUsePreset: () -> Unit,
    onSavePreset: () -> Unit,
    events: ConfigFormEvents,
) {
    ConfigSection(title = stringResource(R.string.presets)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.preset_current_configuration),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = setupSummary(state.form),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            provenanceSummary(state.profileStatus)?.let { provenance ->
                Text(
                    text = provenance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConfigActionPreference(
            title = stringResource(R.string.preset_use),
            description = stringResource(R.string.preset_use_summary),
            onClick = onUsePreset,
        )
        ConfigActionPreference(
            title = stringResource(R.string.preset_save_as),
            description = stringResource(R.string.preset_save_as_summary),
            onClick = onSavePreset,
        )
        if (state.hasPreviousSetup) {
            ConfigActionPreference(
                title = stringResource(R.string.preset_restore_previous),
                description = stringResource(R.string.preset_restore_previous_summary),
                onClick = events::onRestorePreviousSetup,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetDialogs(
    state: ConfigUiState,
    events: ConfigFormEvents,
    pickerVisible: Boolean,
    saveDialogVisible: Boolean,
    onPickerDismiss: () -> Unit,
    onSaveDismiss: () -> Unit,
) {
    if (pickerVisible) {
        PresetPickerDialog(
            templates = state.profileTemplates,
            onDismissRequest = onPickerDismiss,
            onManage = {
                onPickerDismiss()
                events.onManageProfiles()
            },
            onApply = { choice ->
                val applied = if (choice.isBuiltIn) {
                    events.onApplyBuiltInTemplate()
                } else {
                    events.onApplyTemplate(choice.name)
                }
                if (applied) onPickerDismiss()
            },
        )
    }
    if (saveDialogVisible) {
        SavePresetDialog(
            existingNames = state.profileNames,
            templates = state.profileTemplates,
            hasKeyboardLayout = state.hasKeyboardLayout,
            onDismissRequest = onSaveDismiss,
            onConfirm = { name, includeKeyboard ->
                if (events.onSaveTemplate(name, includeKeyboard)) onSaveDismiss()
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
private fun provenanceSummary(status: ConfigUiState.ProfileStatus): String? = when {
    status.activeProfile != null -> stringResource(R.string.preset_active, status.activeProfile)
    status.modified && status.sourceProfile != null ->
        stringResource(R.string.preset_based_on_modified, status.sourceProfile)
    status.builtInDefault -> stringResource(R.string.preset_builtin)
    else -> null
}

@Composable
private fun PresetPickerDialog(
    templates: List<ConfigUiState.ProfileTemplate>,
    onDismissRequest: () -> Unit,
    onManage: () -> Unit,
    onApply: (PresetChoice) -> Unit,
) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val maxHeight = adaptiveDialogLayout().maxHeight
    val selectedChoice = when {
        selectedKey == BUILT_IN_PRESET_KEY -> PresetChoice(
            name = "",
            isBuiltIn = true,
            hasKeyboardLayout = false,
        )
        selectedKey != null -> templates.firstOrNull { it.name == selectedKey }?.let { template ->
            PresetChoice(
                name = template.name,
                isBuiltIn = false,
                hasKeyboardLayout = template.hasKeyboardLayout,
                screenWidth = template.screenWidth,
                screenHeight = template.screenHeight,
                orientation = template.orientation,
            )
        }
        else -> null
    }
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    item(key = BUILT_IN_PRESET_KEY) {
                        PresetPickerRow(
                            name = stringResource(R.string.preset_builtin),
                            summary = stringResource(R.string.preset_builtin_summary),
                            selected = selectedKey == BUILT_IN_PRESET_KEY,
                            onClick = { selectedKey = BUILT_IN_PRESET_KEY },
                        )
                    }
                    items(templates, key = { it.name }) { template ->
                        PresetPickerRow(
                            name = template.name,
                            summary = presetDescription(template),
                            selected = selectedKey == template.name,
                            onClick = { selectedKey = template.name },
                        )
                    }
                }
                selectedChoice?.let { choice ->
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    Text(
                        text = when {
                            choice.isBuiltIn -> stringResource(R.string.preset_apply_builtin_message)
                            choice.hasKeyboardLayout -> stringResource(R.string.preset_apply_with_keyboard_message)
                            else -> stringResource(R.string.preset_apply_without_keyboard_message)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onManage) { Text(stringResource(R.string.preset_manage)) }
                TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    enabled = selectedChoice != null,
                    onClick = { selectedChoice?.let(onApply) },
                ) { Text(stringResource(R.string.preset_apply)) }
            }
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
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.padding(start = 4.dp),
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
}

@Composable
private fun SavePresetDialog(
    existingNames: List<String>,
    templates: List<ConfigUiState.ProfileTemplate>,
    hasKeyboardLayout: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var includeKeyboard by rememberSaveable(hasKeyboardLayout) { mutableStateOf(hasKeyboardLayout) }
    val trimmed = value.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) } ||
        templates.any { it.name.equals(trimmed, ignoreCase = true) }
    val invalidName = !Profile.isValidName(trimmed)
    val valid = !invalidName && !duplicate
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
                                    else -> stringResource(R.string.preset_invalid_name)
                                },
                            )
                        }
                    } else null,
                )
                if (hasKeyboardLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = includeKeyboard,
                                onValueChange = { includeKeyboard = it },
                                role = Role.Checkbox,
                            )
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
