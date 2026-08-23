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
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val NoOpSettingsActions = object : SettingsActions {
    override fun onBack() = Unit
    override fun onThemeChanged(value: String) = Unit
    override fun onLanguageChanged(value: String) = Unit
    override fun onToggle(key: String, checked: Boolean) = Unit
    override fun onOpenProfiles() = Unit
    override fun onChooseDirectory() = Unit
    override fun onDismissDirectoryError() = Unit
}

private val PreviewSettingsState = SettingsUiState(
    theme = SettingsOption("dark", "Dark"),
    themes = listOf(
        SettingsOption("light", "Light"),
        SettingsOption("dark", "Dark"),
        SettingsOption("system", "Follow System Settings"),
    ),
    language = SettingsOption("", "Follow System Settings"),
    languages = listOf(
        SettingsOption("", "Follow System Settings"),
        SettingsOption("en", "English"),
        SettingsOption("id", "Bahasa Indonesia"),
    ),
    switches = listOf(
        SettingsSwitch("pref_actionbar_switch", "Enable ActionBar", "For fullscreen applications", true),
        SettingsSwitch("pref_statusbar_switch", "Enable Statusbar", "For fullscreen applications", false),
        SettingsSwitch(
            "pref_use_display_cutout",
            "Use Display Cutout In MIDlet",
            "Allow compatible MIDlet Canvas content to use the display-cutout area when the runtime can do so safely.",
            true,
        ),
        SettingsSwitch("pref_wakelock_switch", "Keep Screen On", null, false),
        SettingsSwitch("pref_screenshot_switch", "Raw Screenshot", "Disable scaling and filtering for screenshots", false),
        SettingsSwitch("pref_vibration_switch", "Enable Vibration", null, true),
    ),
    experimentalSwitches = listOf(
        SettingsSwitch("micro3d_using_message", "Detect Mascot Capsule 3D", "Show a message when used", false),
    ),
    showProfiles = true,
    workingDirectory = "/storage/emulated/0/JL-Mod Plus",
    libraryChoices = listOf(
        SettingsChoice(
            "pref_apps_view",
            "Library View",
            SettingsOption("grid", "Grid"),
            listOf(SettingsOption("list", "List"), SettingsOption("grid", "Grid")),
        ),
        SettingsChoice(
            "pref_apps_icon_ratio",
            "Icon Ratio",
            SettingsOption("square", "1:1"),
            listOf(SettingsOption("square", "1:1"), SettingsOption("portrait", "3:4")),
        ),
        SettingsChoice(
            "pref_apps_icon_shape",
            "Icon Shape",
            SettingsOption("round", "Rounded"),
            listOf(SettingsOption("round", "Rounded"), SettingsOption("square", "Sharp Corners")),
        ),
        SettingsChoice(
            "pref_apps_grid_spacing",
            "Grid Spacing",
            SettingsOption("standard", "Standard"),
            listOf(
                SettingsOption("none", "None"),
                SettingsOption("compact", "Compact"),
                SettingsOption("standard", "Standard"),
                SettingsOption("spacious", "Spacious"),
            ),
        ),
    ),
    librarySwitches = listOf(
        SettingsSwitch(
            "pref_apps_enhanced_icons",
            "Enhanced Icons",
            "Improve source icons with adaptive sizing and color treatment.",
            true,
        ),
        SettingsSwitch(
            "pref_apps_hide_grid_titles",
            "Hide MIDlet Titles",
            "Show only MIDlet icons in grid view.",
            false,
        ),
    ),
)

@PreviewTest
@Preview(name = "Settings light", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun SettingsLightScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        SettingsScreen(state = PreviewSettingsState, actions = NoOpSettingsActions)
    }
}

@PreviewTest
@Preview(name = "Settings landscape", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
fun SettingsLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        SettingsScreen(state = PreviewSettingsState, actions = NoOpSettingsActions)
    }
}

@PreviewTest
@Preview(
    name = "Settings dark large font",
    widthDp = 360,
    heightDp = 640,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun SettingsDarkLargeFontScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        SettingsScreen(state = PreviewSettingsState, actions = NoOpSettingsActions)
    }
}
