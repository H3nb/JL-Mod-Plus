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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    val hasSettings: Boolean,
    val hasKeyboardLayout: Boolean,
    val completeSnapshotReady: Boolean,
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = presetStatusTitle(state.profileStatus),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            provenanceSummary(state.profileStatus)?.let { provenance -> Text(
                text = provenance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            ) }
            Text(
                text = setupSummary(state.form),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        stringResource(R.string.profile_follows_updates)
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
    var applyErrorVisible by rememberSaveable { mutableStateOf(false) }
    val maxHeight = adaptiveDialogLayout().maxHeight
    val selectedChoice = when {
        selectedKey == BUILT_IN_PRESET_KEY -> PresetChoice(
            name = "",
            isBuiltIn = true,
            hasSettings = true,
            hasKeyboardLayout = false,
            completeSnapshotReady = false,
        )
        selectedKey != null -> templates.firstOrNull { savedPresetKey(it.name) == selectedKey }?.let { template ->
            PresetChoice(
                name = template.name,
                isBuiltIn = false,
                hasSettings = template.hasSettings,
                hasKeyboardLayout = template.hasKeyboardLayout,
                completeSnapshotReady = template.completeSnapshotReady,
            )
        }
        else -> null
    }
    val availableScopes = buildList {
        if (selectedChoice?.completeSnapshotReady == true) {
            add(ConfigFormEvents.PresetApplyScope.WHOLE_PROFILE)
        }
        if (selectedChoice?.hasSettings == true) add(ConfigFormEvents.PresetApplyScope.SETTINGS)
        if (selectedChoice?.hasKeyboardLayout == true) add(ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT)
    }
    var scopeKey by rememberSaveable(selectedKey) { mutableStateOf(availableScopes.firstOrNull()?.name) }
    val selectedScope = availableScopes.firstOrNull { it.name == scopeKey }
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
                        onClick = {
                            selectedKey = savedPresetKey(template.name)
                            applyErrorVisible = false
                        },
                    )
                }
                if (selectedChoice != null) {
                    item(key = "apply-scope-title") {
                        Text(
                            text = stringResource(R.string.preset_choose_parts),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(availableScopes, key = { "scope:${it.name}" }) { scope ->
                        PresetScopeRow(
                            scope = scope,
                            selected = selectedScope == scope,
                            isBuiltIn = selectedChoice.isBuiltIn,
                            hasLayout = selectedChoice.hasKeyboardLayout,
                            onClick = {
                                scopeKey = scope.name
                                applyErrorVisible = false
                            },
                        )
                    }
                    if (applyErrorVisible) item(key = "apply-error") {
                        Text(
                            text = stringResource(R.string.preset_apply_failed_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onManage) { Text(stringResource(R.string.preset_manage)) }
                TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    enabled = selectedChoice != null && selectedScope != null,
                    onClick = {
                        selectedChoice?.let { choice ->
                            selectedScope?.let { scope -> applyErrorVisible = !onApply(choice, scope) }
                        }
                    },
                ) { Text(stringResource(R.string.preset_apply)) }
            }
        },
    )
}

@Composable
private fun PresetScopeRow(
    scope: ConfigFormEvents.PresetApplyScope,
    selected: Boolean,
    isBuiltIn: Boolean,
    hasLayout: Boolean,
    onClick: () -> Unit,
) {
    val label = when (scope) {
        ConfigFormEvents.PresetApplyScope.WHOLE_PROFILE -> stringResource(R.string.profile_apply_whole)
        ConfigFormEvents.PresetApplyScope.SETTINGS -> stringResource(R.string.profile_apply_settings_only)
        ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT -> stringResource(R.string.profile_apply_layout_only)
    }
    val description = when (scope) {
        ConfigFormEvents.PresetApplyScope.WHOLE_PROFILE -> stringResource(
            if (hasLayout) R.string.profile_apply_whole_follows
            else R.string.profile_apply_whole_without_layout,
        )
        ConfigFormEvents.PresetApplyScope.SETTINGS -> stringResource(
            if (isBuiltIn) R.string.profile_apply_builtin_settings
            else R.string.profile_apply_settings_keeps_layout,
        )
        ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT -> stringResource(R.string.profile_apply_layout_keeps_settings)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_scope_${scope.name.lowercase()}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun presetDescription(template: ConfigUiState.ProfileTemplate): String {
    if (!template.hasSettings) return stringResource(R.string.saved_keyboard_layout_summary)
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
