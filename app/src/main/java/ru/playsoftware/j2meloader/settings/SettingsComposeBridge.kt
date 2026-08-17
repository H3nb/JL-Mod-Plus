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

package ru.playsoftware.j2meloader.settings

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

data class SettingsOption(
    val value: String,
    val label: String,
)

data class SettingsSwitch(
    val key: String,
    val title: String,
    val summary: String?,
    val checked: Boolean,
)

data class SettingsUiState(
    val theme: SettingsOption,
    val themes: List<SettingsOption>,
    val language: SettingsOption,
    val languages: List<SettingsOption>,
    val switches: List<SettingsSwitch>,
    val experimentalSwitches: List<SettingsSwitch>,
    val showProfiles: Boolean,
    val workingDirectory: String,
    val directoryError: String? = null,
)

interface SettingsActions {
    fun onBack()
    fun onThemeChanged(value: String)
    fun onLanguageChanged(value: String)
    fun onToggle(key: String, checked: Boolean)
    fun onOpenProfiles()
    fun onChooseDirectory()
    fun onDismissDirectoryError()
}

/** Host bridge; SharedPreferences and activity-result ownership remain in SettingsActivity. */
class SettingsComposeController(
    composeView: ComposeView,
    initialState: SettingsUiState,
    private val actions: SettingsActions,
) {
    private var state by mutableStateOf(initialState)

    init {
        composeView.id = R.id.settings_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                SettingsScreen(state = state, actions = actions)
            }
        }
    }

    fun update(nextState: SettingsUiState) {
        state = nextState
    }
}

/** The Activity shell applies host safe-area padding, so Compose does not apply it again. */
private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var choiceDialog by remember { mutableStateOf<SettingsChoice?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(
                                androidx.appcompat.R.string.abc_action_bar_up_description,
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                contentPadding = padding,
            ) {
                item {
                    SettingsSection(stringResource(R.string.settings_section_appearance)) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.pref_theme_title),
                            selected = state.theme,
                            onClick = { choiceDialog = SettingsChoice.Theme },
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            title = stringResource(R.string.pref_language),
                            selected = state.language,
                            onClick = { choiceDialog = SettingsChoice.Language },
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_midlet_runtime)) {
                        state.switches.forEachIndexed { index, setting ->
                            SettingsSwitchRow(
                                setting = setting,
                                onCheckedChange = { checked -> actions.onToggle(setting.key, checked) },
                            )
                            if (index != state.switches.lastIndex) SettingsDivider()
                        }
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_storage)) {
                        SettingsActionRow(
                            title = stringResource(R.string.pref_emulator_dir),
                            summary = state.workingDirectory,
                            onClick = actions::onChooseDirectory,
                        )
                    }
                }
                if (state.experimentalSwitches.isNotEmpty()) {
                    item {
                        SettingsSection(stringResource(R.string.pref_category_experimental)) {
                            state.experimentalSwitches.forEachIndexed { index, setting ->
                                SettingsSwitchRow(
                                    setting = setting,
                                    onCheckedChange = { checked -> actions.onToggle(setting.key, checked) },
                                )
                                if (index != state.experimentalSwitches.lastIndex) SettingsDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.directoryError?.let { message ->
        AlertDialog(
            modifier = settingsDialogModifier(landscape),
            properties = DialogProperties(usePlatformDefaultWidth = !landscape),
            onDismissRequest = {},
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            dismissButton = {
                TextButton(onClick = actions::onDismissDirectoryError) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDismissDirectoryError()
                    actions.onChooseDirectory()
                }) {
                    Text(stringResource(R.string.choose))
                }
            },
        )
    }

    when (choiceDialog) {
        SettingsChoice.Theme -> SettingsChoiceDialog(
            title = stringResource(R.string.pref_theme_title),
            selected = state.theme,
            options = state.themes,
            onDismiss = { choiceDialog = null },
            onSelected = { value ->
                choiceDialog = null
                actions.onThemeChanged(value)
            },
        )

        SettingsChoice.Language -> SettingsChoiceDialog(
            title = stringResource(R.string.pref_language),
            selected = state.language,
            options = state.languages,
            onDismiss = { choiceDialog = null },
            onSelected = { value ->
                choiceDialog = null
                actions.onLanguageChanged(value)
            },
        )

        null -> Unit
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    selected: SettingsOption,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = selected.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    )
}

private enum class SettingsChoice {
    Theme,
    Language,
}

@Composable
private fun SettingsChoiceDialog(
    title: String,
    selected: SettingsOption,
    options: List<SettingsOption>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    AlertDialog(
        modifier = settingsDialogModifier(landscape),
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = if (landscape) 220.dp else 360.dp),
            ) {
                items(options, key = { it.value }) { option ->
                    val selectedOption = option.value == selected.value
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedOption) FontWeight.Medium else FontWeight.Normal,
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = selectedOption,
                                onClick = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { onSelected(option.value) },
                            ),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SettingsSwitchRow(
    setting: SettingsSwitch,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = setting.summary?.let { summary ->
            {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = setting.checked,
                onCheckedChange = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = setting.checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = summary?.let { value ->
            {
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    )
}

private fun settingsDialogModifier(landscape: Boolean): Modifier {
    return if (landscape) {
        Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 760.dp)
    } else {
        Modifier.widthIn(max = 560.dp)
    }
}
