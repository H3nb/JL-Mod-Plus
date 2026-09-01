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
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.AccentPalette
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.adaptiveDialogLayout
import ru.playsoftware.j2meloader.ui.rememberLazyListCanScrollForward
import ru.playsoftware.j2meloader.ui.rememberScrollCanScrollForward

data class SettingsOption(
    val value: String,
    val label: String,
)

data class SettingsChoice(
    val key: String,
    val title: String,
    val selected: SettingsOption,
    val options: List<SettingsOption>,
)

data class SettingsSwitch @JvmOverloads constructor(
    val key: String,
    val title: String,
    val summary: String?,
    val checked: Boolean,
    val enabled: Boolean = true,
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
    val accent: SettingsOption = SettingsOption("blue", "Default Blue"),
    val accents: List<SettingsOption> = emptyList(),
    val libraryChoices: List<SettingsChoice> = emptyList(),
    val librarySwitches: List<SettingsSwitch> = emptyList(),
)

interface SettingsActions {
    fun onBack()
    fun onThemeChanged(value: String)
    fun onAccentChanged(value: String) = Unit
    fun onLanguageChanged(value: String)
    fun onLibraryChoiceChanged(key: String, value: String) = Unit
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var choiceDialog by remember { mutableStateOf<SettingsDialogChoice?>(null) }
    var libraryChoiceDialog by remember { mutableStateOf<SettingsChoice?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
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
                            onClick = { choiceDialog = SettingsDialogChoice.Theme },
                        )
                        SettingsChoiceRow(
                            title = stringResource(R.string.pref_accent_title),
                            selected = state.accent,
                            showAccentPreview = true,
                            onClick = { choiceDialog = SettingsDialogChoice.Accent },
                        )
                        SettingsChoiceRow(
                            title = stringResource(R.string.pref_language),
                            selected = state.language,
                            onClick = { choiceDialog = SettingsDialogChoice.Language },
                        )
                    }
                }
                if (state.libraryChoices.isNotEmpty() || state.librarySwitches.isNotEmpty()) {
                    item {
                        SettingsSection(stringResource(R.string.settings_section_library_appearance)) {
                            state.libraryChoices.forEach { choice ->
                                SettingsChoiceRow(
                                    title = choice.title,
                                    selected = choice.selected,
                                    onClick = { libraryChoiceDialog = choice },
                                )
                            }
                            state.librarySwitches.forEach { setting ->
                                SettingsSwitchRow(
                                    setting = setting,
                                    onCheckedChange = { checked ->
                                        actions.onToggle(setting.key, checked)
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.settings_section_midlet_runtime)) {
                        state.switches.forEach { setting ->
                            SettingsSwitchRow(
                                setting = setting,
                                onCheckedChange = { checked -> actions.onToggle(setting.key, checked) },
                            )
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
                if (state.showProfiles) {
                    item {
                        SettingsSection(stringResource(R.string.settings_section_profiles)) {
                            SettingsActionRow(
                                title = stringResource(R.string.profiles),
                                summary = stringResource(R.string.settings_profiles_summary),
                                onClick = actions::onOpenProfiles,
                            )
                        }
                    }
                }
                if (state.experimentalSwitches.isNotEmpty()) {
                    item {
                        SettingsSection(stringResource(R.string.pref_category_experimental)) {
                            state.experimentalSwitches.forEach { setting ->
                                SettingsSwitchRow(
                                    setting = setting,
                                    onCheckedChange = { checked -> actions.onToggle(setting.key, checked) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.directoryError?.let { message ->
        val maxMessageHeight = adaptiveDialogLayout().maxContentHeight(reservedHeight = 176.dp)
        AlertDialog(
            textScrollable = false,
            onDismissRequest = {},
            title = { Text(stringResource(R.string.error)) },
            text = {
                val scrollState = rememberScrollState()
                val canScrollForward = rememberScrollCanScrollForward(scrollState)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxMessageHeight),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxMessageHeight)
                            .verticalScroll(scrollState),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ScrollableContentHint(
                        visible = canScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            },
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
        SettingsDialogChoice.Theme -> SettingsChoiceDialog(
            title = stringResource(R.string.pref_theme_title),
            selected = state.theme,
            options = state.themes,
            onDismiss = { choiceDialog = null },
            onSelected = { value ->
                choiceDialog = null
                actions.onThemeChanged(value)
            },
        )

        SettingsDialogChoice.Accent -> SettingsChoiceDialog(
            title = stringResource(R.string.pref_accent_title),
            selected = state.accent,
            options = state.accents,
            showAccentPreview = true,
            onDismiss = { choiceDialog = null },
            onSelected = { value ->
                choiceDialog = null
                actions.onAccentChanged(value)
            },
        )

        SettingsDialogChoice.Language -> SettingsChoiceDialog(
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

    libraryChoiceDialog?.let { choice ->
        SettingsChoiceDialog(
            title = choice.title,
            selected = choice.selected,
            options = choice.options,
            onDismiss = { libraryChoiceDialog = null },
            onSelected = { value ->
                libraryChoiceDialog = null
                actions.onLibraryChoiceChanged(choice.key, value)
            },
        )
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 1.dp),
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
private fun SettingsChoiceRow(
    title: String,
    selected: SettingsOption,
    showAccentPreview: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = selected.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAccentPreview) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        AccentPalette.fromKey(selected.value).previewColor(isSystemInDarkTheme()),
                        CircleShape,
                    ),
            )
        }
    }
}

private enum class SettingsDialogChoice {
    Theme,
    Accent,
    Language,
}

@Composable
private fun SettingsChoiceDialog(
    title: String,
    selected: SettingsOption,
    options: List<SettingsOption>,
    showAccentPreview: Boolean = false,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val maxListHeight = adaptiveDialogLayout().maxContentHeight(reservedHeight = 120.dp)
    val canScrollForward = rememberLazyListCanScrollForward(listState)
    AlertDialog(
        textScrollable = false,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    state = listState,
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
                            trailingContent = if (showAccentPreview) {
                                {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(
                                                AccentPalette.fromKey(option.value)
                                                    .previewColor(isSystemInDarkTheme()),
                                                CircleShape,
                                            ),
                                    )
                                }
                            } else {
                                null
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
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(
                value = setting.checked,
                enabled = setting.enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (setting.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            setting.summary?.let { summary ->
                Text(
                    text = summary,
                    color = if (setting.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Switch(
            checked = setting.checked,
            onCheckedChange = null,
            enabled = setting.enabled,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        summary?.let { value ->
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
