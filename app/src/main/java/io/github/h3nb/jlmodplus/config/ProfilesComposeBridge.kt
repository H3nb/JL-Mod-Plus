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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward

data class ProfileUiItem(
    val name: String,
    val isDefault: Boolean,
    val canEdit: Boolean,
    val isBuiltIn: Boolean = false,
    val isKeyboardOnly: Boolean = false,
    val hasKeyboardLayout: Boolean = false,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: Int = 0,
    val isUnavailable: Boolean = false,
)

data class ProfilesUiState(
    val profiles: List<ProfileUiItem> = emptyList(),
)

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
    var selectedProfile by remember { mutableStateOf<ProfileUiItem?>(null) }
    var nameDialog by remember { mutableStateOf<ProfileNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<ProfileUiItem?>(null) }
    var defaultDialogVisible by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { nameDialog = ProfileNameDialog.Create }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add_preset_description),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Text(
                    text = stringResource(R.string.no_data_for_display),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val builtIn = state.profiles.firstOrNull { it.isBuiltIn }
            val activeDefault = state.profiles.firstOrNull { it.isDefault } ?: builtIn
            val presets = state.profiles.filter {
                !it.isBuiltIn && !it.isKeyboardOnly && !it.isUnavailable && it != activeDefault
            }
            val savedLayouts = state.profiles.filter { it.isKeyboardOnly }
            val unavailable = state.profiles.filter { it.isUnavailable }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                item { ProfileSectionHeader(stringResource(R.string.preset_manager_default_section)) }
                activeDefault?.let { profile ->
                    item(key = "__active_default__") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProfileRow(
                                profile = profile,
                                onClick = { selectedProfile = profile },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { defaultDialogVisible = true }) {
                                Text(stringResource(R.string.preset_change_default))
                            }
                        }
                    }
                }
                if (presets.isNotEmpty()) {
                    item { ProfileSectionHeader(stringResource(R.string.preset_manager_presets_section)) }
                }
                items(presets, key = { it.name }) { profile ->
                    ProfileRow(profile = profile, onClick = { selectedProfile = profile })
                }
                if (savedLayouts.isNotEmpty()) {
                    item { ProfileSectionHeader(stringResource(R.string.preset_manager_keyboard_section)) }
                    items(savedLayouts, key = { "keyboard:" + it.name }) { profile ->
                        ProfileRow(profile = profile, onClick = { selectedProfile = profile })
                    }
                }
                if (unavailable.isNotEmpty()) {
                    item { ProfileSectionHeader(stringResource(R.string.preset_unavailable_section)) }
                    items(unavailable, key = { "unavailable:" + it.name }) { profile ->
                        ProfileRow(profile = profile, onClick = { selectedProfile = profile })
                    }
                }
            }
        }
    }

    selectedProfile?.let { profile ->
        ProfileActionsDialog(
            profile = profile,
            onDismiss = { selectedProfile = null },
            onDefault = {
                if (profile.isBuiltIn) actions.onSetBuiltInDefault() else actions.onSetDefault(profile.name)
            },
            onEdit = { actions.onEdit(profile.name) },
            onRename = { nameDialog = ProfileNameDialog.Rename(profile) },
            onDelete = { deleteTarget = profile },
        )
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
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.action_context_delete) + ": " + profile.name + "?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    actions.onDelete(profile.name)
                }) { Text(stringResource(android.R.string.ok)) }
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
private fun ProfileSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ProfileRow(
    profile: ProfileUiItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = if (profile.isBuiltIn) {
        stringResource(R.string.profile_builtin_settings)
    } else {
        profile.name
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontWeight = if (profile.isDefault) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val orientationOptions = stringArrayResource(R.array.PREF_ORIENTATION_ENTRIES)
            val summary = when {
                profile.isBuiltIn -> stringResource(R.string.profile_builtin_settings_summary)
                profile.isKeyboardOnly -> stringResource(R.string.saved_keyboard_layout_summary)
                profile.isUnavailable -> stringResource(R.string.preset_unavailable_summary)
                profile.screenWidth > 0 && profile.screenHeight > 0 -> buildString {
                    append(profile.screenWidth).append(" × ").append(profile.screenHeight)
                    orientationOptions.getOrNull(profile.orientation)?.let {
                        append(" · ").append(it)
                    }
                    if (profile.hasKeyboardLayout) {
                        append(" · ").append(stringResource(R.string.preset_includes_keyboard_layout))
                    }
                }
                else -> stringResource(R.string.profile_template_summary)
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ProfileActionsDialog(
    profile: ProfileUiItem,
    onDismiss: () -> Unit,
    onDefault: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayName = if (profile.isBuiltIn) {
        stringResource(R.string.profile_builtin_settings)
    } else {
        profile.name
    }
    val maxActionHeight = adaptiveDialogLayout().maxHeight
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState)
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismiss,
        title = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxActionHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxActionHeight)
                        .verticalScroll(scrollState),
                ) {
                    if (!profile.isKeyboardOnly && !profile.isUnavailable && !profile.isDefault) {
                        ProfileDialogAction(R.string.set_as_default_new_games, onDismiss, onDefault)
                    }
                    if (!profile.isBuiltIn && !profile.isKeyboardOnly && !profile.isUnavailable && profile.canEdit) {
                        ProfileDialogAction(R.string.edit_preset, onDismiss, onEdit)
                    }
                    if (!profile.isBuiltIn) {
                        ProfileDialogAction(R.string.action_context_rename, onDismiss, onRename)
                        ProfileDialogAction(R.string.action_context_delete, onDismiss, onDelete)
                    }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = null,
    )
}

@Composable
private fun DefaultPresetDialog(
    profiles: List<ProfileUiItem>,
    onDismiss: () -> Unit,
    onApply: (ProfileUiItem) -> Unit,
) {
    val choices = profiles.filter {
        it.isBuiltIn || (!it.isKeyboardOnly && !it.isUnavailable && it.canEdit)
    }
    val active = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull { it.isBuiltIn }
    var selectedName by rememberSaveable(active?.name) {
        mutableStateOf(active?.name ?: "")
    }
    val selected = choices.firstOrNull { it.name == selectedName }
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_default_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = adaptiveDialogLayout().maxHeight),
            ) {
                items(choices, key = { if (it.isBuiltIn) "__built_in__" else it.name }) { profile ->
                    val displayName = if (profile.isBuiltIn) {
                        stringResource(R.string.profile_builtin_settings)
                    } else {
                        profile.name
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedName == profile.name,
                                onClick = { selectedName = profile.name },
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedName == profile.name, onClick = null)
                        Text(displayName, modifier = Modifier.padding(start = 4.dp))
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

@Composable
private fun ProfileDialogAction(label: Int, onDismiss: () -> Unit, action: () -> Unit) {
    TextButton(
        onClick = {
            onDismiss()
            action()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
    val invalidName = !Profile.isValidName(trimmed)
    val valid = !empty && !duplicate && !invalidName
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
