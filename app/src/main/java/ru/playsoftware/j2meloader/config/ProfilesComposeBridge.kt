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

package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

data class ProfileUiItem(
    val name: String,
    val isDefault: Boolean,
    val canEdit: Boolean,
)

data class ProfilesUiState(
    val profiles: List<ProfileUiItem> = emptyList(),
)

/** The Activity shell already applies the host safe-area padding. */
private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

interface ProfilesActions {
    fun onBack()
    fun onCreate(name: String)
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

    fun updateProfiles(profiles: List<Profile>, defaultName: String?) {
        state = ProfilesUiState(
            profiles = profiles.sorted().map { profile ->
                ProfileUiItem(
                    name = profile.name,
                    isDefault = profile.name == defaultName,
                    canEdit = profile.hasConfig() || profile.hasOldConfig(),
                )
            },
        )
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                title = { Text(stringResource(R.string.profiles)) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { nameDialog = ProfileNameDialog.Create }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add_profile_description),
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.profiles, key = { it.name }) { profile ->
                    ProfileRow(profile = profile, onClick = { selectedProfile = profile })
                }
            }
        }
    }

    selectedProfile?.let { profile ->
        ProfileActionsDialog(
            profile = profile,
            onDismiss = { selectedProfile = null },
            onDefault = { actions.onSetDefault(profile.name) },
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
}

@Composable
private fun ProfileRow(profile: ProfileUiItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (profile.isDefault) {
                stringResource(R.string.default_label, profile.name)
            } else {
                profile.name
            },
            modifier = Modifier.weight(1f),
            fontWeight = if (profile.isDefault) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (profile.canEdit) {
                    ProfileDialogAction(R.string.set_as_default, onDismiss, onDefault)
                    ProfileDialogAction(R.string.edit, onDismiss, onEdit)
                }
                ProfileDialogAction(R.string.action_context_rename, onDismiss, onRename)
                ProfileDialogAction(R.string.action_context_delete, onDismiss, onDelete)
            }
        },
        confirmButton = {},
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
    val duplicate = trimmed == original || trimmed in existingNames
    val valid = !empty && !duplicate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (dialog is ProfileNameDialog.Create) R.string.enter_name else R.string.enter_new_name,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    touched = true
                    val filtered = input.text.filterNot { it in "/\\:*?\"<>|" }
                    value = input.copy(
                        text = filtered,
                        selection = TextRange(input.selection.end.coerceAtMost(filtered.length)),
                    )
                },
                singleLine = true,
                isError = touched && (empty || duplicate),
                supportingText = if (touched && (empty || duplicate)) {
                    {
                        when {
                            empty -> Text(stringResource(R.string.error_name))
                            duplicate -> Text(stringResource(R.string.not_saved_exists))
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
