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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

data class ProfileUiItem(
    val name: String,
    val isDefault: Boolean,
    val canEdit: Boolean,
    val canSetDefault: Boolean = canEdit,
    val isBuiltIn: Boolean = false,
    val isKeyboardOnly: Boolean = false,
    val hasKeyboardLayout: Boolean = false,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: Int = 0,
    val isUnavailable: Boolean = false,
    val isKeyboardLayoutUnavailable: Boolean = false,
)

data class ProfilesUiState(val profiles: List<ProfileUiItem> = emptyList())

interface ProfilesActions {
    fun onBack()
    fun onCreate(name: String)
    fun onSetBuiltInDefault()
    fun onSetDefault(name: String)
    fun onEdit(name: String)
    fun onRename(oldName: String, newName: String)
    fun onDelete(name: String)
}

class ProfilesComposeController(
    composeView: ComposeView,
    private val actions: ProfilesActions,
) {
    private var state by mutableStateOf(ProfilesUiState())

    init {
        composeView.id = R.id.profiles_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                ProfilesScreen(state = state, actions = actions)
            }
        }
    }

    fun updateProfileItems(items: List<ProfileUiItem>) {
        state = ProfilesUiState(profiles = items)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    state: ProfilesUiState,
    actions: ProfilesActions,
    modifier: Modifier = Modifier,
) {
    var nameDialog by remember { mutableStateOf<ProfileNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<ProfileUiItem?>(null) }
    var defaultDialogVisible by remember { mutableStateOf(false) }
    val builtIn = state.profiles.firstOrNull { it.isBuiltIn }
    val activeDefault = state.profiles.firstOrNull { it.isDefault && !it.isBuiltIn } ?: builtIn
    val profiles = state.profiles.filter { !it.isBuiltIn && !it.isKeyboardOnly && !it.isUnavailable }
    val savedLayouts = state.profiles.filter { !it.isBuiltIn && it.isKeyboardOnly }
    val unavailable = state.profiles.filter { !it.isBuiltIn && it.isUnavailable }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presets)) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { nameDialog = ProfileNameDialog.Create }) {
                        Text(stringResource(R.string.profile_create_title))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ProfileSectionHeader(stringResource(R.string.preset_manager_default_section))
            }
            item(key = "default-policy", span = { GridItemSpan(maxLineSpan) }) {
                DefaultPolicyRow(activeDefault = activeDefault, onClick = { defaultDialogVisible = true })
            }
            if (profiles.isEmpty() && savedLayouts.isEmpty() && unavailable.isEmpty()) {
                item(key = "empty-profiles", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyProfilesCard(onCreate = { nameDialog = ProfileNameDialog.Create })
                }
            }
            if (profiles.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProfileSectionHeader(stringResource(R.string.preset_manager_presets_section))
                }
                gridItems(profiles, key = { it.name }) { profile ->
                    ProfileRow(
                        profile = profile,
                        onEdit = { actions.onEdit(profile.name) },
                        onRename = { nameDialog = ProfileNameDialog.Rename(profile) },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
            if (savedLayouts.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProfileSectionHeader(stringResource(R.string.preset_manager_keyboard_section))
                }
                gridItems(savedLayouts, key = { "keyboard:" + it.name }) { profile ->
                    ProfileRow(
                        profile = profile,
                        onEdit = {},
                        onRename = { nameDialog = ProfileNameDialog.Rename(profile) },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
            if (unavailable.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProfileSectionHeader(stringResource(R.string.preset_unavailable_section))
                }
                gridItems(unavailable, key = { "unavailable:" + it.name }) { profile ->
                    ProfileRow(
                        profile = profile,
                        onEdit = {},
                        onRename = { nameDialog = ProfileNameDialog.Rename(profile) },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
        }
    }

    nameDialog?.let { dialog ->
        ProfileNameDialog(
            dialog = dialog,
            existingNames = state.profiles.mapTo(mutableSetOf()) { it.name },
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                when (dialog) {
                    ProfileNameDialog.Create -> actions.onCreate(name)
                    is ProfileNameDialog.Rename -> actions.onRename(dialog.profile.name, name)
                }
            },
        )
    }
    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.profile_delete_template_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        actions.onDelete(profile.name)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.action_context_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (defaultDialogVisible) {
        DefaultPresetDialog(
            profiles = state.profiles,
            onDismiss = { defaultDialogVisible = false },
            onApply = { profile ->
                defaultDialogVisible = false
                if (profile.isBuiltIn) actions.onSetBuiltInDefault()
                else actions.onSetDefault(profile.name)
            },
        )
    }
}

@Composable
private fun EmptyProfilesCard(onCreate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.profile_create_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.profile_create_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profile_create_title))
            }
        }
    }
}

@Composable
private fun ProfileSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .semantics { heading() }
            .padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun DefaultPolicyRow(
    activeDefault: ProfileUiItem?,
    onClick: () -> Unit,
) {
    val displayName = when {
        activeDefault?.isBuiltIn == true -> stringResource(R.string.profile_builtin_settings)
        activeDefault != null -> activeDefault.name
        else -> stringResource(R.string.profile_builtin_settings)
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.preset_default_policy_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.preset_change_default),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileUiItem,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val editable = !profile.isKeyboardOnly && !profile.isUnavailable && profile.canEdit
    val orientationOptions = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
    val summary = when {
        profile.isKeyboardOnly -> stringResource(R.string.saved_keyboard_layout_summary)
        profile.isUnavailable -> stringResource(R.string.preset_unavailable_summary)
        profile.screenWidth > 0 && profile.screenHeight > 0 -> buildString {
            append(profile.screenWidth).append(" × ").append(profile.screenHeight)
            orientationOptions.getOrNull(profile.orientation)?.let { append(" · ").append(it) }
            if (profile.hasKeyboardLayout) append(" · ").append(stringResource(R.string.preset_includes_keyboard_layout))
            if (profile.isKeyboardLayoutUnavailable) append(" · ").append(stringResource(R.string.preset_keyboard_layout_unavailable))
        }
        else -> stringResource(R.string.profile_template_summary)
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button) {
                if (editable) onEdit() else actionsExpanded = true
            }
            .heightIn(min = 68.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.isDefault) {
                        Text(
                            stringResource(R.string.profile_default_badge_short),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.profile_more_actions, profile.name),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    if (editable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_preset)) },
                            onClick = { actionsExpanded = false; onEdit() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_context_rename)) },
                        onClick = { actionsExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_context_delete)) },
                        onClick = { actionsExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultPresetDialog(
    profiles: List<ProfileUiItem>,
    onDismiss: () -> Unit,
    onApply: (ProfileUiItem) -> Unit,
) {
    val choices = profiles.filter {
        it.isBuiltIn || it.canSetDefault
    }
    val active = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull { it.isBuiltIn }
    var selectedId by rememberSaveable(active?.let(::profileSelectionId)) {
        mutableStateOf(active?.let(::profileSelectionId))
    }
    val selected = choices.firstOrNull { profileSelectionId(it) == selectedId }
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_default_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = adaptiveDialogLayout().maxHeight)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(choices, key = ::profileSelectionId) { profile ->
                    val displayName = if (profile.isBuiltIn) {
                        stringResource(R.string.profile_builtin_settings)
                    } else {
                        profile.name
                    }
                    val selected = selectedId == profileSelectionId(profile)
                    val colors = MaterialTheme.colorScheme
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { selectedId = profileSelectionId(profile) },
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                            )
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
                        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
                        border = if (selected) BorderStroke(1.dp, colors.secondary) else null,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                displayName,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onApply) },
            ) { Text(stringResource(R.string.preset_apply)) }
        },
    )
}

private fun profileSelectionId(profile: ProfileUiItem): String =
    if (profile.isBuiltIn) "builtin" else "saved:${profile.name}"

internal sealed interface ProfileNameDialog {
    data object Create : ProfileNameDialog
    data class Rename(val profile: ProfileUiItem) : ProfileNameDialog
}

@Composable
internal fun ProfileNameDialog(
    dialog: ProfileNameDialog,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val original = (dialog as? ProfileNameDialog.Rename)?.profile?.name.orEmpty()
    var value by rememberSaveable(original, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = original,
                selection = TextRange(original.length),
            ),
        )
    }
    var touched by rememberSaveable(original) { mutableStateOf(false) }
    val trimmed = value.text.trim()
    val empty = trimmed.isEmpty()
    val duplicate = !trimmed.equals(original, ignoreCase = true) &&
        existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val invalidName = !empty && !Profile.isValidName(trimmed)
    val unchanged = dialog is ProfileNameDialog.Rename && trimmed.equals(original, ignoreCase = true)
    val valid = !empty && !duplicate && !invalidName && !unchanged
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (dialog is ProfileNameDialog.Create) R.string.profile_create_title else R.string.profile_rename_title,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    touched = true
                    value = input
                },
                singleLine = true,
                isError = touched && (empty || duplicate || invalidName),
                supportingText = if (touched && (empty || duplicate || invalidName)) {
                    {
                        when {
                            empty -> Text(stringResource(R.string.error_name))
                            duplicate -> Text(stringResource(R.string.preset_name_exists))
                            else -> Text(stringResource(R.string.preset_invalid_name))
                        }
                    }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = valid,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
