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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

private const val BUILT_IN_PRESET_KEY = "builtin"
private val PresetPickerMaximumWidth = 840.dp

private fun savedPresetKey(name: String): String = "saved:$name"

private data class PresetChoice(
    val name: String,
    val isBuiltIn: Boolean,
    val hasKeyboardLayout: Boolean,
)

@Composable
internal fun PresetSummary(
    state: ConfigUiState,
    onUsePreset: () -> Unit,
    onSavePreset: () -> Unit,
    events: ConfigFormEvents,
) {
    var updateConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    ConfigSection(
        title = stringResource(R.string.presets),
        highlighted = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.preset_current_configuration),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = presetStatusTitle(state.profileStatus),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            provenanceSummary(state.profileStatus)?.let { provenance -> Text(
                text = provenance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            ) }
            Text(
                text = setupSummary(state.form),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PresetActionButtons(
                onUsePreset = onUsePreset,
                onSavePreset = onSavePreset,
                updatePresetName = state.updatePresetName,
                onUpdatePreset = { updateConfirmation = it },
            )
        }
    }
    updateConfirmation?.let { name ->
        UpdatePresetDialog(
            name = name,
            onDismissRequest = { updateConfirmation = null },
            onConfirm = {
                if (events.onUpdatePreset(name)) updateConfirmation = null
            },
        )
    }
}

@Composable
private fun PresetActionButtons(
    onUsePreset: () -> Unit,
    onSavePreset: () -> Unit,
    updatePresetName: String?,
    onUpdatePreset: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactActions = maxWidth < 440.dp
            if (compactActions) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetPrimaryButton(onUsePreset, Modifier.fillMaxWidth())
                    PresetSecondaryButton(onSavePreset, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PresetPrimaryButton(onUsePreset, Modifier.weight(1f))
                    PresetSecondaryButton(onSavePreset, Modifier.weight(1f))
                }
            }
        }
        updatePresetName?.let { name ->
            OutlinedButton(
                onClick = { onUpdatePreset(name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("preset_update_action"),
            ) {
                PresetActionLabel(R.string.preset_update, name)
            }
        }
    }
}

@Composable
private fun PresetPrimaryButton(onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        PresetActionLabel(R.string.preset_use)
    }
}

@Composable
private fun PresetSecondaryButton(onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { PresetActionLabel(R.string.preset_save_as) }
}

@Composable
private fun PresetActionLabel(label: Int, vararg formatArgs: Any) {
    Text(
        text = stringResource(label, *formatArgs),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

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
            onApply = { choice, scope ->
                val applied = if (choice.isBuiltIn) {
                    events.onApplyBuiltInTemplate(scope)
                } else {
                    events.onApplyTemplate(choice.name, scope)
                }
                if (applied) onPickerDismiss()
                applied
            },
        )
    }
    if (saveDialogVisible) {
        SavePresetDialog(
            existingNames = state.profileNames,
            templates = state.profileTemplates,
            onDismissRequest = onSaveDismiss,
            onConfirm = { name ->
                if (events.onSaveTemplate(name)) onSaveDismiss()
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
    status.modified && status.sourceProfile != null ->
        stringResource(R.string.preset_based_on_modified, status.sourceProfile)
    status.activeProfile != null ->
        stringResource(R.string.preset_following, status.activeProfile)
    else -> null
}

@Composable
private fun presetStatusTitle(status: ConfigUiState.ProfileStatus): String = when {
    status.activeProfile != null -> status.activeProfile
    status.sourceProfile != null -> status.sourceProfile
    status.builtInDefault -> stringResource(R.string.preset_builtin)
    else -> stringResource(R.string.preset_custom_configuration)
}

@Composable
private fun PresetPickerDialog(
    templates: List<ConfigUiState.ProfileTemplate>,
    onDismissRequest: () -> Unit,
    onManage: () -> Unit,
    onApply: (PresetChoice, ConfigFormEvents.PresetApplyScope) -> Boolean,
) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePartsVisible by rememberSaveable { mutableStateOf(false) }
    var applyErrorVisible by rememberSaveable { mutableStateOf(false) }
    var scopeKey by rememberSaveable(selectedKey) {
        mutableStateOf(ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD.name)
    }
    val maxHeight = adaptiveDialogLayout().maxHeight
    val selectedChoice = when {
        selectedKey == BUILT_IN_PRESET_KEY -> PresetChoice(
            name = "",
            isBuiltIn = true,
            hasKeyboardLayout = false,
        )
        selectedKey != null -> templates.firstOrNull { savedPresetKey(it.name) == selectedKey }?.let { template ->
            PresetChoice(
                name = template.name,
                isBuiltIn = false,
                hasKeyboardLayout = template.hasKeyboardLayout,
            )
        }
        else -> null
    }
    val selectedScope = if (selectedChoice?.hasKeyboardLayout == true) {
        runCatching { ConfigFormEvents.PresetApplyScope.valueOf(scopeKey) }
            .getOrDefault(ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD)
    } else {
        ConfigFormEvents.PresetApplyScope.SETTINGS
    }
    AlertDialog(
        maxWidth = PresetPickerMaximumWidth,
        textScrollable = false,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = BUILT_IN_PRESET_KEY) {
                    PresetPickerRow(
                        name = stringResource(R.string.preset_builtin),
                        summary = stringResource(R.string.preset_builtin_summary),
                        selected = selectedKey == BUILT_IN_PRESET_KEY,
                        onChooseParts = null,
                        onClick = {
                            selectedKey = BUILT_IN_PRESET_KEY
                            applyErrorVisible = false
                        },
                    )
                }
                items(templates, key = { savedPresetKey(it.name) }) { template ->
                    PresetPickerRow(
                        name = template.name,
                        summary = presetDescription(template),
                        selected = selectedKey == savedPresetKey(template.name),
                        onChooseParts = if (template.hasKeyboardLayout) {
                            {
                                selectedKey = savedPresetKey(template.name)
                                applyErrorVisible = false
                                choosePartsVisible = true
                            }
                        } else {
                            null
                        },
                        onClick = {
                            selectedKey = savedPresetKey(template.name)
                            applyErrorVisible = false
                        },
                    )
                }
                selectedChoice?.takeIf { applyErrorVisible }?.let {
                    item(key = "selected-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                            if (applyErrorVisible) {
                                Text(
                                    text = stringResource(R.string.preset_apply_failed_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onManage) { Text(stringResource(R.string.preset_manage)) }
                TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    enabled = selectedChoice != null,
                    onClick = {
                        selectedChoice?.let { choice ->
                            applyErrorVisible = !onApply(choice, selectedScope)
                        }
                    },
                ) { Text(stringResource(R.string.preset_apply)) }
            }
        },
    )
    if (choosePartsVisible && selectedChoice?.hasKeyboardLayout == true) {
        PresetPartsDialog(
            initialScope = selectedScope,
            onDismissRequest = { choosePartsVisible = false },
            onConfirm = { scope ->
                scopeKey = scope.name
                choosePartsVisible = false
            },
        )
    }
}

@Composable
private fun PresetPartsDialog(
    initialScope: ConfigFormEvents.PresetApplyScope,
    onDismissRequest: () -> Unit,
    onConfirm: (ConfigFormEvents.PresetApplyScope) -> Unit,
) {
    var settings by rememberSaveable(initialScope) {
        mutableStateOf(initialScope != ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT)
    }
    var keyboard by rememberSaveable(initialScope) {
        mutableStateOf(initialScope != ConfigFormEvents.PresetApplyScope.SETTINGS)
    }
    val scope = when {
        settings && keyboard -> ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD
        settings -> ConfigFormEvents.PresetApplyScope.SETTINGS
        keyboard -> ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_choose_parts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.preset_choose_parts_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApplyPartRow(
                    label = stringResource(R.string.preset_settings_part),
                    checked = settings,
                    onCheckedChange = { settings = it },
                    testTag = "preset_apply_settings_part",
                )
                ApplyPartRow(
                    label = stringResource(R.string.preset_keyboard_part),
                    checked = keyboard,
                    onCheckedChange = { keyboard = it },
                    testTag = "preset_apply_keyboard_part",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("preset_parts_apply"),
                enabled = scope != null,
                onClick = { scope?.let(onConfirm) },
            ) {
                Text(stringResource(R.string.preset_apply))
            }
        },
    )
}

@Composable
private fun ApplyPartRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun presetDescription(template: ConfigUiState.ProfileTemplate): String {
    val orientations = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
    val orientation = orientations.getOrNull(template.orientation)
    val configuration = buildString {
        if (template.screenWidth > 0 && template.screenHeight > 0) {
            append(template.screenWidth).append(" × ").append(template.screenHeight)
            if (orientation != null) append(" · ").append(orientation)
        } else {
            append(stringResource(R.string.preset_application_settings))
        }
    }
    val component = when {
        template.hasKeyboardLayout -> stringResource(R.string.preset_includes_keyboard_layout)
        template.keyboardLayoutUnavailable -> stringResource(R.string.preset_keyboard_layout_unavailable)
        else -> null
    }
    return if (component == null) configuration else "$configuration\n$component"
}

@Composable
private fun PresetPickerRow(
    name: String,
    summary: String,
    selected: Boolean,
    onChooseParts: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 72.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(1.dp, colors.secondary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onSecondaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onChooseParts?.let { chooseParts ->
                androidx.compose.material3.IconButton(onClick = chooseParts) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(R.drawable.ic_tune),
                        contentDescription = stringResource(R.string.preset_choose_parts),
                        tint = if (selected) colors.onSecondaryContainer else colors.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    existingNames: List<String>,
    templates: List<ConfigUiState.ProfileTemplate>,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val trimmed = value.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) } ||
        templates.any { it.name.equals(trimmed, ignoreCase = true) }
    val invalidName = !Profile.isValidName(trimmed)
    val valid = !invalidName && !duplicate
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_save_as)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                onClick = { onConfirm(trimmed) },
            ) { Text(stringResource(R.string.save)) }
        },
    )
}

@Composable
private fun UpdatePresetDialog(
    name: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.preset_update_title, name)) },
        text = { Text(stringResource(R.string.preset_update_summary, name)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.preset_update_confirm)) }
        },
    )
}
