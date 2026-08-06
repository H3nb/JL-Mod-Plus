/*
 * Copyright 2026 H3NB
 *
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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

internal class ProfilesUiState {
    internal var profilesState by mutableStateOf<List<Profile>>(emptyList())
    internal var defaultNameState by mutableStateOf<String?>(null)

    fun setProfiles(profiles: List<Profile>, defaultName: String?) {
        profilesState = profiles.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        defaultNameState = defaultName
    }

    fun addProfile(profile: Profile) {
        setProfiles(profilesState + profile, defaultNameState)
    }

    fun removeProfile(profile: Profile) {
        setProfiles(profilesState.filter { it != profile }, defaultNameState)
    }

    fun refresh() {
        profilesState = profilesState.toList()
    }

    fun setDefault(profile: Profile) {
        defaultNameState = profile.name
        profilesState = profilesState.toList()
    }

    fun isDefault(profile: Profile): Boolean = defaultNameState == profile.name
}

internal data class ProfileNameDialogState(
    val titleRes: Int,
    val initialName: String,
    val profileIndex: Int,
)

@Composable
internal fun ProfilesScreen(
    state: ProfilesUiState,
    nameDialog: ProfileNameDialogState?,
    nameValue: String,
    onNameValueChange: (String) -> Unit,
    onNameConfirm: () -> Unit,
    onNameDismiss: () -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onAction: (Profile, Int) -> Unit,
) {
    AppComposeTheme {
        ProfilesContent(
            profiles = state.profilesState,
            defaultName = state.defaultNameState,
            onBack = onBack,
            onAdd = onAdd,
            onAction = onAction,
        )
        if (nameDialog != null) {
            AlertDialog(
                onDismissRequest = onNameDismiss,
                title = { Text(stringResource(nameDialog.titleRes)) },
                text = {
                    TextField(
                        value = nameValue,
                        onValueChange = onNameValueChange,
                        label = { Text(stringResource(R.string.enter_name)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = onNameConfirm) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onNameDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfilesContent(
    profiles: List<Profile>,
    defaultName: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onAction: (Profile, Int) -> Unit,
    initialMenuProfile: Profile? = null,
    hasEditableData: (Profile) -> Boolean = {
        it.hasConfig() || it.hasOldConfig()
    },
) {
    var menuProfile by androidx.compose.runtime.remember {
        mutableStateOf(initialMenuProfile)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            ProfilesTopBar(onBack = onBack, onAdd = onAdd)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (profiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_data_for_display),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        fontSize = 18.sp,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(profiles, key = { it.name }) { profile ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { menuProfile = profile },
                                        onLongClick = { menuProfile = profile },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = if (profile.name == defaultName) {
                                        stringResource(R.string.default_label, profile.name)
                                    } else {
                                        profile.name
                                    },
                                    fontSize = 18.sp,
                                )
                                if (menuProfile == profile) {
                                    DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = { menuProfile = null },
                                    ) {
                                        ProfileMenuItems(
                                            profile = profile,
                                            hasEditableData = hasEditableData(profile),
                                            onAction = onAction,
                                            dismiss = { menuProfile = null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilesTopBar(onBack: () -> Unit, onAdd: () -> Unit) {
    val backDescription = stringResource(R.string.back)
    val addDescription = stringResource(R.string.add)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = backDescription
                },
                onClick = onBack,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24),
                    contentDescription = null,
                )
            }
            Text(
                text = stringResource(R.string.profiles),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = addDescription
                },
                onClick = onAdd,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun ProfileMenuItems(
    profile: Profile,
    hasEditableData: Boolean,
    onAction: (Profile, Int) -> Unit,
    dismiss: () -> Unit,
) {
    if (hasEditableData) {
        ProfileActionItem(profile, R.id.action_context_default, R.string.set_as_default, onAction, dismiss)
        ProfileActionItem(profile, R.id.action_context_edit, R.string.edit, onAction, dismiss)
    }
    ProfileActionItem(profile, R.id.action_context_rename, R.string.action_context_rename, onAction, dismiss)
    ProfileActionItem(profile, R.id.action_context_delete, R.string.action_context_delete, onAction, dismiss)
}

@Composable
private fun ProfileActionItem(
    profile: Profile,
    actionId: Int,
    labelRes: Int,
    onAction: (Profile, Int) -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = {
            dismiss()
            onAction(profile, actionId)
        },
    )
}

private fun previewProfiles(): List<Profile> = listOf(
    Profile("Default"),
    Profile("Adventure"),
    Profile("Touch controls"),
)

@Preview(name = "Profiles", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun ProfilesListPreview() {
    AppComposeTheme {
        ProfilesContent(
            profiles = previewProfiles(),
            defaultName = "Default",
            onBack = {},
            onAdd = {},
            onAction = { _, _ -> },
        )
    }
}

@Preview(
    name = "Profiles dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ProfilesListDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        ProfilesContent(
            profiles = previewProfiles(),
            defaultName = "Default",
            onBack = {},
            onAdd = {},
            onAction = { _, _ -> },
        )
    }
}

@Preview(name = "Profiles empty", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun ProfilesEmptyPreview() {
    AppComposeTheme {
        ProfilesContent(
            profiles = emptyList(),
            defaultName = null,
            onBack = {},
            onAdd = {},
            onAction = { _, _ -> },
        )
    }
}

@Preview(
    name = "Profiles empty dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ProfilesEmptyDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        ProfilesContent(
            profiles = emptyList(),
            defaultName = null,
            onBack = {},
            onAdd = {},
            onAction = { _, _ -> },
        )
    }
}

@Preview(name = "Profile menu", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun ProfilesContextMenuPreview() {
    val profile = previewProfiles()[1]
    AppComposeTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.width(280.dp), shadowElevation = 8.dp) {
                Column {
                    ProfileMenuItems(
                        profile = profile,
                        hasEditableData = true,
                        onAction = { _, _ -> },
                        dismiss = {},
                    )
                }
            }
        }
    }
}

@Preview(
    name = "Profile menu dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ProfilesContextMenuDarkPreview() {
    val profile = previewProfiles()[1]
    AppComposeTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(modifier = Modifier.width(280.dp), shadowElevation = 8.dp) {
                    Column {
                        ProfileMenuItems(
                            profile = profile,
                            hasEditableData = true,
                            onAction = { _, _ -> },
                            dismiss = {},
                        )
                    }
                }
            }
        }
    }
}
