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

package io.github.h3nb.jlmodplus.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ENHANCED_ICONS
import io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ICON_RATIO
import io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ICON_SHAPE
import io.github.h3nb.jlmodplus.util.Constants.PREF_KEEP_SCREEN
import io.github.h3nb.jlmodplus.util.Constants.PREF_STATUSBAR
import io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR
import io.github.h3nb.jlmodplus.applist.LibraryAboutScreen
import io.github.h3nb.jlmodplus.applist.LibraryInfoDialog
import io.github.h3nb.jlmodplus.applist.LibraryInformationDialog
import io.github.h3nb.jlmodplus.updates.UpdateRepository
import io.github.h3nb.jlmodplus.updates.UpdateStatus
import io.github.h3nb.jlmodplus.updates.UpdatesScreen
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.PreferenceNavigationRow
import io.github.h3nb.jlmodplus.ui.PreferenceSwitchRow
import io.github.h3nb.jlmodplus.ui.PreferenceTextContent
import io.github.h3nb.jlmodplus.ui.PreferenceChevron
import io.github.h3nb.jlmodplus.ui.TransientNoticeHost
import io.github.h3nb.jlmodplus.ui.TransientNoticeState
import io.github.h3nb.jlmodplus.ui.AccentPalette
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward

data class SettingsOption(
    val value: String,
    val label: String,
)

data class SettingsChoice @JvmOverloads constructor(
    val key: String,
    val title: String,
    val selected: SettingsOption,
    val options: List<SettingsOption>,
    val summary: String? = null,
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
    val diagnosticSwitches: List<SettingsSwitch>,
    val showProfiles: Boolean,
    val workingDirectory: String,
    val directoryError: String? = null,
    val accent: SettingsOption = SettingsOption("sapphire", "Sapphire blue"),
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
    fun onOpenCrashReports() = Unit
    fun onOpenDonation() = Unit
    fun onOpenProjectRepository() = Unit
    fun onOpenUpdateRelease(url: String) = Unit
    fun onOpenUpdateDownload(url: String) = Unit
}

/** Host bridge; SharedPreferences and activity-result ownership remain in SettingsActivity. */
class SettingsComposeController(
    composeView: ComposeView,
    initialState: SettingsUiState,
    private val actions: SettingsActions,
) {
    private var state by mutableStateOf(initialState)
    private val notices = TransientNoticeState()

    init {
        composeView.id = R.id.settings_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(state = state, actions = actions)
                    TransientNoticeHost(notices, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    fun update(nextState: SettingsUiState) {
        state = nextState
    }

    fun showNotice(message: String) {
        notices.show(message)
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
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var infoDialog by remember { mutableStateOf<LibraryInfoDialog?>(null) }
    val context = LocalContext.current
    val updates = if (LocalInspectionMode.current) null else remember(context) {
        UpdateRepository.get(context)
    }
    val settingsListState = rememberLazyListState()

    val libraryOptions = state.libraryChoices.map { SettingsOptionEntry.Choice(it) } +
        state.librarySwitches.map { SettingsOptionEntry.Toggle(it) }
    val libraryIconOptions = libraryOptions.filter { it.key in libraryIconKeys }
    val libraryGridOptions = libraryOptions.filterNot { it.key in libraryIconKeys }
    val runtimeScreenOptions = state.switches
        .filter { it.key in runtimeScreenKeys }
        .map { SettingsOptionEntry.Toggle(it) }
    val runtimeFeatureOptions = state.switches
        .filterNot { it.key in runtimeScreenKeys }
        .map { SettingsOptionEntry.Toggle(it) }

    BackHandler(enabled = infoDialog != null) { infoDialog = null }
    BackHandler(enabled = page != SettingsPage.Main && infoDialog == null) {
        page = SettingsPage.Main
    }
    if (page != SettingsPage.Main) {
        when (page) {
            SettingsPage.Updates -> if (updates != null) {
                UpdatesScreen(
                    repository = updates,
                    onBack = { page = SettingsPage.Main },
                    onOpenRelease = actions::onOpenUpdateRelease,
                    onOpenDownload = actions::onOpenUpdateDownload,
                )
            }
            SettingsPage.About -> LibraryAboutScreen(
                onBack = { page = SettingsPage.Main },
                onLicenses = { infoDialog = LibraryInfoDialog.Licenses },
                onOpenGitHub = actions::onOpenProjectRepository,
            )
            SettingsPage.Main -> Unit
        }
        infoDialog?.let { dialog ->
            LibraryInformationDialog(dialog = dialog, onDismiss = { infoDialog = null })
        }
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                state = settingsListState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                contentPadding = padding,
            ) {
                settingsSection(
                    key = "appearance-and-language",
                    title = R.string.settings_section_appearance_language,
                    rowCount = 3,
                    rowKey = { when (it) { 0 -> "theme"; 1 -> "accent"; else -> "language" } },
                    rowType = { SettingsItemType.Choice },
                ) { index ->
                    when (index) {
                        0 -> SettingsChoiceRow(
                            title = stringResource(R.string.pref_theme_title),
                            selected = state.theme,
                            onClick = { choiceDialog = SettingsDialogChoice.Theme },
                        )
                        1 -> SettingsChoiceRow(
                            title = stringResource(R.string.pref_accent_title),
                            selected = state.accent,
                            showAccentPreview = true,
                            onClick = { choiceDialog = SettingsDialogChoice.Accent },
                        )
                        else -> SettingsChoiceRow(
                            title = stringResource(R.string.pref_language),
                            selected = state.language,
                            onClick = { choiceDialog = SettingsDialogChoice.Language },
                        )
                    }
                }
                settingsOptionSection(
                    key = "library-icons",
                    title = R.string.settings_section_library_icons,
                    entries = libraryIconOptions,
                    onChoice = { libraryChoiceDialog = it },
                    onToggle = { setting, checked -> actions.onToggle(setting.key, checked) },
                )
                settingsOptionSection(
                    key = "library-grid",
                    title = R.string.settings_section_library_grid,
                    entries = libraryGridOptions,
                    onChoice = { libraryChoiceDialog = it },
                    onToggle = { setting, checked -> actions.onToggle(setting.key, checked) },
                )
                settingsOptionSection(
                    key = "runtime-screen",
                    title = R.string.settings_section_runtime_screen,
                    entries = runtimeScreenOptions,
                    onChoice = { libraryChoiceDialog = it },
                    onToggle = { setting, checked -> actions.onToggle(setting.key, checked) },
                )
                settingsOptionSection(
                    key = "runtime-features",
                    title = R.string.settings_section_runtime_features,
                    entries = runtimeFeatureOptions,
                    onChoice = { libraryChoiceDialog = it },
                    onToggle = { setting, checked -> actions.onToggle(setting.key, checked) },
                )
                settingsSection(
                    key = "storage-and-profiles",
                    title = R.string.settings_section_storage_profiles,
                    rowCount = if (state.showProfiles) 2 else 1,
                    rowKey = { if (it == 0) "directory" else "profiles" },
                    rowType = { SettingsItemType.Action },
                ) { index ->
                    if (index == 0) {
                        SettingsActionRow(
                            title = stringResource(R.string.pref_emulator_dir),
                            value = state.workingDirectory,
                            onClick = actions::onChooseDirectory,
                        )
                    } else {
                        SettingsActionRow(
                            title = stringResource(R.string.preset_manage),
                            summary = stringResource(R.string.settings_profiles_summary),
                            onClick = actions::onOpenProfiles,
                        )
                    }
                }
                settingsSection(
                    key = "maintenance",
                    title = R.string.library_settings_section_maintenance,
                    rowCount = 2 + state.diagnosticSwitches.size,
                    rowKey = { index ->
                        when (index) {
                            0 -> "updates"
                            1 -> "diagnostics"
                            else -> state.diagnosticSwitches[index - 2].key
                        }
                    },
                    rowType = { if (it < 2) SettingsItemType.Action else SettingsItemType.Switch },
                ) { index ->
                    when (index) {
                        0 -> SettingsActionRow(
                            title = stringResource(R.string.updates_title),
                            summary = stringResource(
                                if (updates?.status is UpdateStatus.Available) R.string.updates_more_available
                                else R.string.updates_more_summary,
                            ),
                            onClick = { page = SettingsPage.Updates },
                        )
                        1 -> SettingsActionRow(
                            title = stringResource(R.string.crash_reports),
                            summary = stringResource(R.string.library_action_crash_reports_summary),
                            onClick = actions::onOpenCrashReports,
                        )
                        else -> {
                            val setting = state.diagnosticSwitches[index - 2]
                            SettingsSwitchRow(
                                setting = setting,
                                onCheckedChange = { checked -> actions.onToggle(setting.key, checked) },
                            )
                        }
                    }
                }
                settingsSection(
                    key = "support",
                    title = R.string.library_settings_section_support,
                    rowCount = 2,
                    rowKey = { if (it == 0) "about" else "donate" },
                    rowType = { SettingsItemType.Action },
                ) { index ->
                    if (index == 0) {
                        SettingsActionRow(
                            title = stringResource(R.string.about),
                            summary = stringResource(R.string.library_action_about_summary),
                            onClick = { page = SettingsPage.About },
                        )
                    } else {
                        SettingsActionRow(
                            title = stringResource(R.string.library_donation_action),
                            summary = stringResource(R.string.library_donation_summary),
                            onClick = { infoDialog = LibraryInfoDialog.Donation },
                        )
                    }
                }
            }
        }
    }
    infoDialog?.let { dialog ->
        LibraryInformationDialog(
            dialog = dialog,
            onDismiss = { infoDialog = null },
            onDonate = actions::onOpenDonation,
        )
    }

    state.directoryError?.let { message ->
        val maxMessageHeight = adaptiveDialogLayout().maxHeight
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
            summary = choice.summary,
            onDismiss = { libraryChoiceDialog = null },
            onSelected = { value ->
                libraryChoiceDialog = null
                actions.onLibraryChoiceChanged(choice.key, value)
            },
        )
    }
}

private enum class SettingsPage { Main, Updates, About }

private val libraryIconKeys = setOf(
    PREF_APPS_ICON_RATIO, PREF_APPS_ICON_SHAPE, PREF_APPS_ENHANCED_ICONS,
)
private val runtimeScreenKeys = setOf(PREF_TOOLBAR, PREF_STATUSBAR, PREF_KEEP_SCREEN)

private sealed interface SettingsOptionEntry {
    val key: String

    data class Choice(val option: SettingsChoice) : SettingsOptionEntry {
        override val key get() = option.key
    }

    data class Toggle(val option: SettingsSwitch) : SettingsOptionEntry {
        override val key get() = option.key
    }
}

private fun LazyListScope.settingsOptionSection(
    key: String,
    title: Int,
    entries: List<SettingsOptionEntry>,
    onChoice: (SettingsChoice) -> Unit,
    onToggle: (SettingsSwitch, Boolean) -> Unit,
) {
    if (entries.isEmpty()) return
    settingsSection(
        key = key,
        title = title,
        rowCount = entries.size,
        rowKey = { entries[it].key },
        rowType = {
            when (entries[it]) {
                is SettingsOptionEntry.Choice -> SettingsItemType.Choice
                is SettingsOptionEntry.Toggle -> SettingsItemType.Switch
            }
        },
    ) { index ->
        when (val entry = entries[index]) {
            is SettingsOptionEntry.Choice -> SettingsChoiceRow(
                title = entry.option.title,
                selected = entry.option.selected,
                onClick = { onChoice(entry.option) },
            )
            is SettingsOptionEntry.Toggle -> SettingsSwitchRow(
                setting = entry.option,
                onCheckedChange = { checked -> onToggle(entry.option, checked) },
            )
        }
    }
}

private enum class SettingsItemType { Heading, Choice, Switch, Action }

private fun LazyListScope.settingsSection(
    key: String,
    title: Int,
    rowCount: Int,
    rowKey: (Int) -> String,
    rowType: (Int) -> SettingsItemType,
    content: @Composable (Int) -> Unit,
) {
    item(key = "$key:heading", contentType = SettingsItemType.Heading) {
        Text(
            text = stringResource(title),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = if (rowCount == 0) 16.dp else 8.dp)
                .semantics { heading() }
                .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 1.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(
        count = rowCount,
        key = { "$key:${rowKey(it)}" },
        contentType = rowType,
    ) { index ->
        SettingsSectionRow(first = index == 0, last = index == rowCount - 1) {
            content(index)
        }
    }
}

@Composable
private fun SettingsSectionRow(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val squareCorner = CornerSize(0.dp)
    val rowShape = shape.copy(
        topStart = if (first) shape.topStart else squareCorner,
        topEnd = if (first) shape.topEnd else squareCorner,
        bottomStart = if (last) shape.bottomStart else squareCorner,
        bottomEnd = if (last) shape.bottomEnd else squareCorner,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = if (last) 8.dp else 0.dp)
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .semantics { isTraversalGroup = true },
        propagateMinConstraints = true,
    ) { content() }
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
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreferenceTextContent(
            title = title,
            value = selected.label,
            modifier = Modifier.weight(1f),
        )
        if (showAccentPreview) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        AccentPalette.fromKey(selected.value).previewColor(isSystemInDarkTheme(), LocalContext.current),
                        CircleShape,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
        PreferenceChevron()
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
    summary: String? = null,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val maxListHeight = adaptiveDialogLayout().maxHeight
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
                        .heightIn(max = maxListHeight)
                        .selectableGroup(),
                    state = listState,
                ) {
                    if (!summary.isNullOrBlank()) {
                        item(key = "explanation") {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(options, key = { it.value }) { option ->
                        val selectedOption = option.value == selected.value
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
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
                                                    .previewColor(isSystemInDarkTheme(), LocalContext.current),
                                                CircleShape,
                                            )
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedOption,
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
        confirmButton = null,
    )
}

@Composable
private fun SettingsSwitchRow(
    setting: SettingsSwitch,
    onCheckedChange: (Boolean) -> Unit,
) {
    PreferenceSwitchRow(
        title = setting.title,
        description = setting.summary,
        checked = setting.checked,
        enabled = setting.enabled,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    PreferenceNavigationRow(
        title = title,
        value = value,
        description = summary,
        onClick = onClick,
    )
}
