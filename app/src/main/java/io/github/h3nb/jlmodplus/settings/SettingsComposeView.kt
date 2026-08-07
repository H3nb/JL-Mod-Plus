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

package io.github.h3nb.jlmodplus.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.XmlResourceParser
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.Config
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import io.github.h3nb.jlmodplus.util.Constants.PREF_CAMERA_DEFAULT_DEVICE
import io.github.h3nb.jlmodplus.util.Constants.PREF_CAMERA_DEFAULT_SNAPSHOT
import io.github.h3nb.jlmodplus.util.Constants.PREF_CAMERA_JPEG_QUALITY
import io.github.h3nb.jlmodplus.util.Constants.PREF_CAMERA_MAX_SNAPSHOT
import io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR
import io.github.h3nb.jlmodplus.util.Constants.PREF_EXPAND_TO_CUTOUT
import io.github.h3nb.jlmodplus.util.Constants.PREF_KEEP_SCREEN
import io.github.h3nb.jlmodplus.util.Constants.PREF_SCREENSHOT_SWITCH
import io.github.h3nb.jlmodplus.util.Constants.PREF_STATUSBAR
import io.github.h3nb.jlmodplus.util.Constants.PREF_THEME
import io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR
import io.github.h3nb.jlmodplus.util.Constants.PREF_VIBRATION
import io.github.h3nb.jlmodplus.util.XmlUtils
import java.util.Locale

internal data class SettingsChoice(
    val value: String,
    val label: String,
)

internal data class SettingsSwitchState(
    val key: String,
    val title: Int,
    val summary: Int? = null,
    val icon: Int?,
    val defaultValue: Boolean,
)

internal class SettingsUiState(context: Context) {
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    internal val themeChoices = context.resources.getStringArray(R.array.pref_theme_values)
        .zip(context.resources.getStringArray(R.array.pref_theme_entries))
        .map { (value, label) -> SettingsChoice(value, label) }
    internal val languageChoices = loadLanguageChoices(context)
    internal val cameraDeviceChoices = listOf(
        SettingsChoice("auto", context.getString(R.string.camera_default_device_auto)),
        SettingsChoice("rear", context.getString(R.string.camera_default_device_rear)),
        SettingsChoice("front", context.getString(R.string.camera_default_device_front)),
    )
    internal val cameraDefaultSnapshotChoices = listOf(
        SettingsChoice("320x240", "320×240"),
        SettingsChoice("640x480", "640×480"),
        SettingsChoice("1280x960", "1280×960"),
        SettingsChoice("1600x1200", "1600×1200"),
        SettingsChoice("2048x1536", "2048×1536"),
    )
    internal val cameraMaxSnapshotChoices = listOf(
        SettingsChoice("640x480", "640×480"),
        SettingsChoice("1280x960", "1280×960"),
        SettingsChoice("1600x1200", "1600×1200"),
        SettingsChoice("2048x1536", "2048×1536"),
    )
    internal val cameraJpegQualityChoices = listOf(
        SettingsChoice("80", "80"),
        SettingsChoice("90", "90"),
        SettingsChoice("100", "100"),
    )

    internal var themeValue by mutableStateOf(
        preferences.getString(PREF_THEME, context.getString(R.string.pref_theme_default))
            ?: context.getString(R.string.pref_theme_default),
    )
    internal var languageValue by mutableStateOf(currentLanguageValue())
    internal var directoryValue by mutableStateOf(
        preferences.getString(PREF_EMULATOR_DIR, Config.getEmulatorDir()) ?: Config.getEmulatorDir(),
    )
    internal var directoryErrorPath by mutableStateOf<String?>(null)

    internal var cameraDeviceValue by mutableStateOf(
        preferences.getString(PREF_CAMERA_DEFAULT_DEVICE, "auto") ?: "auto",
    )
    internal var cameraDefaultSnapshotValue by mutableStateOf(
        canonicalCameraSize(preferences.getString(PREF_CAMERA_DEFAULT_SNAPSHOT, "640x480") ?: "640x480"),
    )
    internal var cameraMaxSnapshotValue by mutableStateOf(
        canonicalCameraSize(preferences.getString(PREF_CAMERA_MAX_SNAPSHOT, "2048x1536") ?: "2048x1536"),
    )
    internal var cameraJpegQualityValue by mutableStateOf(
        preferences.getInt(PREF_CAMERA_JPEG_QUALITY, 90).toString(),
    )

    internal var actionBarEnabled by mutableStateOf(preferences.getBoolean(PREF_TOOLBAR, false))
    internal var expandToCutout by mutableStateOf(preferences.getBoolean(PREF_EXPAND_TO_CUTOUT, true))
    internal var statusBarEnabled by mutableStateOf(preferences.getBoolean(PREF_STATUSBAR, false))
    internal var keepScreenOnEnabled by mutableStateOf(preferences.getBoolean(PREF_KEEP_SCREEN, false))
    internal var rawScreenshot by mutableStateOf(preferences.getBoolean(PREF_SCREENSHOT_SWITCH, false))
    internal var vibrationEnabled by mutableStateOf(preferences.getBoolean(PREF_VIBRATION, true))
    internal var mascotMessage by mutableStateOf(preferences.getBoolean("micro3d_using_message", false))
    internal var audioSpeed by mutableStateOf(preferences.getBoolean("pref_emulation_audio_speed", false))
    internal var extremeSpeeds by mutableStateOf(preferences.getBoolean("pref_emulation_extreme_speeds", false))

    init {
        preferences.edit {
            putString(PREF_CAMERA_DEFAULT_SNAPSHOT, cameraDefaultSnapshotValue)
            putString(PREF_CAMERA_MAX_SNAPSHOT, cameraMaxSnapshotValue)
        }
    }

    fun setDirectory(path: String) {
        directoryValue = path
        directoryErrorPath = null
    }

    fun showDirectoryError(path: String) {
        directoryErrorPath = path
    }

    internal fun setTheme(value: String) {
        themeValue = value
        preferences.edit { putString(PREF_THEME, value) }
    }

    internal fun setLanguage(value: String) {
        languageValue = value
        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(value),
        )
    }

    internal fun setCameraDevice(value: String) {
        cameraDeviceValue = value
        preferences.edit { putString(PREF_CAMERA_DEFAULT_DEVICE, value) }
    }

    internal fun setCameraDefaultSnapshot(value: String) {
        cameraDefaultSnapshotValue = canonicalCameraSize(value)
        preferences.edit { putString(PREF_CAMERA_DEFAULT_SNAPSHOT, cameraDefaultSnapshotValue) }
    }

    internal fun setCameraMaxSnapshot(value: String) {
        cameraMaxSnapshotValue = canonicalCameraSize(value)
        preferences.edit { putString(PREF_CAMERA_MAX_SNAPSHOT, cameraMaxSnapshotValue) }
    }

    internal fun setCameraJpegQuality(value: String) {
        cameraJpegQualityValue = value
        preferences.edit { putInt(PREF_CAMERA_JPEG_QUALITY, value.toIntOrNull() ?: 90) }
    }

    internal fun setSwitch(key: String, value: Boolean) {
        when (key) {
            PREF_TOOLBAR -> actionBarEnabled = value
            PREF_STATUSBAR -> statusBarEnabled = value
            PREF_EXPAND_TO_CUTOUT -> expandToCutout = value
            PREF_KEEP_SCREEN -> keepScreenOnEnabled = value
            PREF_SCREENSHOT_SWITCH -> rawScreenshot = value
            PREF_VIBRATION -> vibrationEnabled = value
            "micro3d_using_message" -> mascotMessage = value
            "pref_emulation_audio_speed" -> audioSpeed = value
            "pref_emulation_extreme_speeds" -> extremeSpeeds = value
        }
        preferences.edit { putBoolean(key, value) }
    }

    private fun currentLanguageValue(): String {
        val locale = AppCompatDelegate.getApplicationLocales().get(0) ?: return ""
        return languageChoices.firstOrNull {
            it.value == locale.toLanguageTag() || it.value == locale.language
        }?.value ?: ""
    }
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onProfiles: () -> Unit,
    onChooseDirectory: () -> Unit,
) {
    AppComposeTheme {
        SettingsContent(
            themeChoices = state.themeChoices,
            selectedTheme = state.themeValue,
            languageChoices = state.languageChoices,
            selectedLanguage = state.languageValue,
            cameraDeviceChoices = state.cameraDeviceChoices,
            selectedCameraDevice = state.cameraDeviceValue,
            cameraDefaultSnapshotChoices = state.cameraDefaultSnapshotChoices,
            selectedCameraDefaultSnapshot = state.cameraDefaultSnapshotValue,
            cameraMaxSnapshotChoices = state.cameraMaxSnapshotChoices,
            selectedCameraMaxSnapshot = state.cameraMaxSnapshotValue,
            cameraJpegQualityChoices = state.cameraJpegQualityChoices,
            selectedCameraJpegQuality = state.cameraJpegQualityValue,
            directory = state.directoryValue,
            switches = listOf(
                SettingsSwitchState(PREF_TOOLBAR, R.string.pref_enable_actionbar_title, R.string.pref_enable_actionbar_summary, R.drawable.ic_setting_enable_action_bar, state.actionBarEnabled),
                SettingsSwitchState(PREF_STATUSBAR, R.string.pref_enable_statusbar_title, R.string.pref_enable_actionbar_summary, R.drawable.ic_setting_enable_statusbar, state.statusBarEnabled),
                SettingsSwitchState(PREF_EXPAND_TO_CUTOUT, R.string.pref_expand_to_cutout_title, R.string.pref_expand_to_cutout_summary, null, state.expandToCutout),
                SettingsSwitchState(PREF_KEEP_SCREEN, R.string.pref_wakelock_title, icon = R.drawable.ic_setting_keep_screen_on, defaultValue = state.keepScreenOnEnabled),
                SettingsSwitchState(PREF_SCREENSHOT_SWITCH, R.string.pref_screenshot_title, R.string.pref_screenshot_summary, R.drawable.ic_setting_screenshot, state.rawScreenshot),
                SettingsSwitchState(PREF_VIBRATION, R.string.pref_vibration_title, icon = R.drawable.ic_setting_enable_vibration, defaultValue = state.vibrationEnabled),
            ),
            experimentalSwitches = listOf(
                SettingsSwitchState("micro3d_using_message", R.string.pref_mascot_title, R.string.pref_mascot_summary, R.drawable.ic_setting_message, state.mascotMessage),
                SettingsSwitchState("pref_emulation_audio_speed", R.string.pref_emulation_audio_speed_title, R.string.pref_emulation_audio_speed_summary, null, state.audioSpeed),
                SettingsSwitchState("pref_emulation_extreme_speeds", R.string.pref_emulation_extreme_speeds_title, R.string.pref_emulation_extreme_speeds_summary, null, state.extremeSpeeds),
            ),
            directoryErrorPath = state.directoryErrorPath,
            onBack = onBack,
            onThemeSelected = state::setTheme,
            onLanguageSelected = state::setLanguage,
            onCameraDeviceSelected = state::setCameraDevice,
            onCameraDefaultSnapshotSelected = state::setCameraDefaultSnapshot,
            onCameraMaxSnapshotSelected = state::setCameraMaxSnapshot,
            onCameraJpegQualitySelected = state::setCameraJpegQuality,
            onSwitchChanged = state::setSwitch,
            onProfiles = onProfiles,
            onChooseDirectory = onChooseDirectory,
            onDismissDirectoryError = { state.directoryErrorPath = null },
        )
    }
}

@Composable
private fun SettingsContent(
    themeChoices: List<SettingsChoice>,
    selectedTheme: String,
    languageChoices: List<SettingsChoice>,
    selectedLanguage: String,
    cameraDeviceChoices: List<SettingsChoice>,
    selectedCameraDevice: String,
    cameraDefaultSnapshotChoices: List<SettingsChoice>,
    selectedCameraDefaultSnapshot: String,
    cameraMaxSnapshotChoices: List<SettingsChoice>,
    selectedCameraMaxSnapshot: String,
    cameraJpegQualityChoices: List<SettingsChoice>,
    selectedCameraJpegQuality: String,
    directory: String,
    switches: List<SettingsSwitchState>,
    experimentalSwitches: List<SettingsSwitchState>,
    directoryErrorPath: String?,
    onBack: () -> Unit,
    onThemeSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onCameraDeviceSelected: (String) -> Unit,
    onCameraDefaultSnapshotSelected: (String) -> Unit,
    onCameraMaxSnapshotSelected: (String) -> Unit,
    onCameraJpegQualitySelected: (String) -> Unit,
    onSwitchChanged: (String, Boolean) -> Unit,
    onProfiles: () -> Unit,
    onChooseDirectory: () -> Unit,
    onDismissDirectoryError: () -> Unit,
) {
    var themeDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var languageDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var cameraSettingsVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var cameraDeviceDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var cameraDefaultDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var cameraMaxDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var cameraQualityDialogVisible by androidx.compose.runtime.remember { mutableStateOf(false) }

    BackHandler(enabled = cameraSettingsVisible) {
        cameraSettingsVisible = false
    }

    val selectedCameraLabel = cameraDeviceChoices.firstOrNull { it.value == selectedCameraDevice }?.label.orEmpty()
    val selectedSnapshotLabel = cameraDefaultSnapshotChoices
        .firstOrNull { it.value == selectedCameraDefaultSnapshot }?.label.orEmpty()
    val cameraSummary = listOf(selectedCameraLabel, selectedSnapshotLabel)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            SettingsTopBar(
                title = stringResource(
                    if (cameraSettingsVisible) R.string.camera_settings_category else R.string.action_settings,
                ),
                onBack = if (cameraSettingsVisible) {
                    { cameraSettingsVisible = false }
                } else {
                    onBack
                },
            )
            if (cameraSettingsVisible) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_screenshot,
                            title = stringResource(R.string.camera_default_device_title),
                            summary = selectedCameraLabel,
                            onClick = { cameraDeviceDialogVisible = true },
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_screenshot,
                            title = stringResource(R.string.camera_default_snapshot_title),
                            summary = listOf(
                                selectedSnapshotLabel,
                                stringResource(R.string.camera_snapshot_auto_orientation_summary),
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            onClick = { cameraDefaultDialogVisible = true },
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_screenshot,
                            title = stringResource(R.string.camera_max_snapshot_title),
                            summary = cameraMaxSnapshotChoices
                                .firstOrNull { it.value == selectedCameraMaxSnapshot }?.label.orEmpty(),
                            onClick = { cameraMaxDialogVisible = true },
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_screenshot,
                            title = stringResource(R.string.camera_jpeg_quality_title),
                            summary = cameraJpegQualityChoices
                                .firstOrNull { it.value == selectedCameraJpegQuality }?.label.orEmpty(),
                            onClick = { cameraQualityDialogVisible = true },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_theme,
                            title = stringResource(R.string.pref_theme_title),
                            summary = themeChoices.firstOrNull { it.value == selectedTheme }?.label.orEmpty(),
                            onClick = { themeDialogVisible = true },
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_translate,
                            title = stringResource(R.string.pref_language),
                            summary = languageChoices.firstOrNull { it.value == selectedLanguage }?.label.orEmpty(),
                            onClick = { languageDialogVisible = true },
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_screenshot,
                            title = stringResource(R.string.camera_settings_category),
                            summary = cameraSummary,
                            onClick = { cameraSettingsVisible = true },
                        )
                    }
                    items(switches, key = { it.key }) { state ->
                        SettingsSwitchRow(state, onSwitchChanged)
                    }
                    item {
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_default,
                            title = stringResource(R.string.profiles),
                            summary = stringResource(R.string.pref_default_settings),
                            onClick = onProfiles,
                        )
                        SettingsChoiceRow(
                            icon = R.drawable.ic_setting_folder,
                            title = stringResource(R.string.pref_emulator_dir),
                            summary = directory,
                            onClick = onChooseDirectory,
                        )
                        Text(
                            text = stringResource(R.string.pref_category_experimental),
                            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(experimentalSwitches, key = { it.key }) { state ->
                        SettingsSwitchRow(state, onSwitchChanged)
                    }
                }
            }
        }
    }

    if (themeDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.pref_theme_title),
            choices = themeChoices,
            selected = selectedTheme,
            onSelected = { onThemeSelected(it); themeDialogVisible = false },
            onDismiss = { themeDialogVisible = false },
        )
    }
    if (languageDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.pref_language),
            choices = languageChoices,
            selected = selectedLanguage,
            onSelected = { onLanguageSelected(it); languageDialogVisible = false },
            onDismiss = { languageDialogVisible = false },
        )
    }
    if (cameraDeviceDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.camera_default_device_title),
            choices = cameraDeviceChoices,
            selected = selectedCameraDevice,
            onSelected = { onCameraDeviceSelected(it); cameraDeviceDialogVisible = false },
            onDismiss = { cameraDeviceDialogVisible = false },
        )
    }
    if (cameraDefaultDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.camera_default_snapshot_title),
            choices = cameraDefaultSnapshotChoices,
            selected = selectedCameraDefaultSnapshot,
            onSelected = { onCameraDefaultSnapshotSelected(it); cameraDefaultDialogVisible = false },
            onDismiss = { cameraDefaultDialogVisible = false },
        )
    }
    if (cameraMaxDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.camera_max_snapshot_title),
            choices = cameraMaxSnapshotChoices,
            selected = selectedCameraMaxSnapshot,
            onSelected = { onCameraMaxSnapshotSelected(it); cameraMaxDialogVisible = false },
            onDismiss = { cameraMaxDialogVisible = false },
        )
    }
    if (cameraQualityDialogVisible) {
        SettingsChoiceDialog(
            title = stringResource(R.string.camera_jpeg_quality_title),
            choices = cameraJpegQualityChoices,
            selected = selectedCameraJpegQuality,
            onSelected = { onCameraJpegQualitySelected(it); cameraQualityDialogVisible = false },
            onDismiss = { cameraQualityDialogVisible = false },
        )
    }
    if (directoryErrorPath != null) {
        AlertDialog(
            onDismissRequest = onDismissDirectoryError,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(R.string.create_apps_dir_failed, directoryErrorPath)) },
            dismissButton = {
                TextButton(onClick = onDismissDirectoryError) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismissDirectoryError(); onChooseDirectory() }) {
                    Text(stringResource(R.string.choose))
                }
            },
        )
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val backDescription = stringResource(R.string.back)
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(
                modifier = Modifier.semantics { contentDescription = backDescription },
                onClick = onBack,
            ) {
                BackGlyph(MaterialTheme.colorScheme.onSecondary)
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSecondary,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(icon: Int, title: String, summary: String, onClick: () -> Unit) {
    SettingsRow(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ChoiceChevron(MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSwitchRow(state: SettingsSwitchState, onSwitchChanged: (String, Boolean) -> Unit) {
    SettingsRow(onClick = { onSwitchChanged(state.key, !state.defaultValue) }) {
        if (state.icon != null) {
            Icon(
                painter = painterResource(state.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(text = stringResource(state.title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            state.summary?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = state.defaultValue,
            onCheckedChange = { onSwitchChanged(state.key, it) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun SettingsRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun SettingsChoiceDialog(
    title: String,
    choices: List<SettingsChoice>,
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelected(choice.value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.value == selected, onClick = { onSelected(choice.value) })
                        Text(
                            text = choice.label,
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun BackGlyph(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(19.dp.toPx(), size.height / 2), androidx.compose.ui.geometry.Offset(5.dp.toPx(), size.height / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(5.dp.toPx(), size.height / 2), androidx.compose.ui.geometry.Offset(11.dp.toPx(), 6.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(5.dp.toPx(), size.height / 2), androidx.compose.ui.geometry.Offset(11.dp.toPx(), size.height - 6.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ChoiceChevron(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        drawLine(color, androidx.compose.ui.geometry.Offset(9.dp.toPx(), 6.dp.toPx()), androidx.compose.ui.geometry.Offset(15.dp.toPx(), size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(15.dp.toPx(), size.height / 2), androidx.compose.ui.geometry.Offset(9.dp.toPx(), size.height - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun canonicalCameraSize(value: String): String {
    val normalized = value.lowercase(Locale.ROOT).replace('×', 'x')
    val parts = normalized.split('x', limit = 2)
    val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return value
    val height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return value
    return if (width >= height) "$width x $height".replace(" ", "")
    else "$height x $width".replace(" ", "")
}

@SuppressLint("DiscouragedApi")
private fun loadLanguageChoices(context: Context): List<SettingsChoice> {
    val tags = mutableListOf("")
    val resourceId = context.resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)
    if (resourceId != 0) {
        val parser: XmlResourceParser = context.resources.getXml(resourceId)
        try {
            while (XmlUtils.nextElement(parser, "locale")) {
                parser.getAttributeValue(0)?.takeIf { it.isNotEmpty() }?.let(tags::add)
            }
        } finally {
            parser.close()
        }
    }
    return tags.distinct().map { tag ->
        if (tag.isEmpty()) {
            SettingsChoice(tag, context.getString(R.string.pref_theme_system))
        } else {
            val locale = Locale.forLanguageTag(tag)
            SettingsChoice(tag, locale.getDisplayName(locale).ifEmpty { tag })
        }
    }
}

@Preview(name = "Settings light", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun SettingsLightPreview() { SettingsPreview(darkTheme = false) }

@Preview(name = "Settings dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsDarkPreview() { SettingsPreview(darkTheme = true) }

@Composable
private fun SettingsPreview(darkTheme: Boolean) {
    val themeChoices = listOf(
        SettingsChoice("light", stringResource(R.string.pref_theme_light)),
        SettingsChoice("dark", stringResource(R.string.pref_theme_night)),
        SettingsChoice("auto-time", stringResource(R.string.pref_theme_auto_time)),
    )
    val languages = listOf(
        SettingsChoice("", stringResource(R.string.pref_theme_system)),
        SettingsChoice("en", "English"),
        SettingsChoice("id", "Indonesia"),
    )
    val cameraDevices = listOf(
        SettingsChoice("auto", stringResource(R.string.camera_default_device_auto)),
        SettingsChoice("rear", stringResource(R.string.camera_default_device_rear)),
        SettingsChoice("front", stringResource(R.string.camera_default_device_front)),
    )
    val defaultSnapshots = listOf(SettingsChoice("640x480", "640×480"))
    val maxSnapshots = listOf(SettingsChoice("2048x1536", "2048×1536"))
    val qualities = listOf(SettingsChoice("90", "90"))
    AppComposeTheme(darkTheme = darkTheme) {
        SettingsContent(
            themeChoices = themeChoices,
            selectedTheme = "dark",
            languageChoices = languages,
            selectedLanguage = "",
            cameraDeviceChoices = cameraDevices,
            selectedCameraDevice = "auto",
            cameraDefaultSnapshotChoices = defaultSnapshots,
            selectedCameraDefaultSnapshot = "640x480",
            cameraMaxSnapshotChoices = maxSnapshots,
            selectedCameraMaxSnapshot = "2048x1536",
            cameraJpegQualityChoices = qualities,
            selectedCameraJpegQuality = "90",
            directory = "/sdcard/JL-Mod Plus",
            switches = listOf(
                SettingsSwitchState(PREF_TOOLBAR, R.string.pref_enable_actionbar_title, R.string.pref_enable_actionbar_summary, R.drawable.ic_setting_enable_action_bar, false),
                SettingsSwitchState(PREF_STATUSBAR, R.string.pref_enable_statusbar_title, R.string.pref_enable_actionbar_summary, R.drawable.ic_setting_enable_statusbar, false),
                SettingsSwitchState(PREF_EXPAND_TO_CUTOUT, R.string.pref_expand_to_cutout_title, R.string.pref_expand_to_cutout_summary, null, true),
                SettingsSwitchState(PREF_KEEP_SCREEN, R.string.pref_wakelock_title, icon = R.drawable.ic_setting_keep_screen_on, defaultValue = false),
                SettingsSwitchState(PREF_SCREENSHOT_SWITCH, R.string.pref_screenshot_title, R.string.pref_screenshot_summary, R.drawable.ic_setting_screenshot, false),
                SettingsSwitchState(PREF_VIBRATION, R.string.pref_vibration_title, icon = R.drawable.ic_setting_enable_vibration, defaultValue = true),
            ),
            experimentalSwitches = listOf(
                SettingsSwitchState("micro3d_using_message", R.string.pref_mascot_title, R.string.pref_mascot_summary, R.drawable.ic_setting_message, false),
            ),
            directoryErrorPath = null,
            onBack = {},
            onThemeSelected = {},
            onLanguageSelected = {},
            onCameraDeviceSelected = {},
            onCameraDefaultSnapshotSelected = {},
            onCameraMaxSnapshotSelected = {},
            onCameraJpegQualitySelected = {},
            onSwitchChanged = { _, _ -> },
            onProfiles = {},
            onChooseDirectory = {},
            onDismissDirectoryError = {},
        )
    }
}
