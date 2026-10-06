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

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

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
        SettingsOption("system", "Follow system settings"),
    ),
    language = SettingsOption("", "Follow system settings"),
    languages = listOf(
        SettingsOption("", "Follow system settings"),
        SettingsOption("en", "English"),
        SettingsOption("id", "Bahasa Indonesia"),
    ),
    switches = listOf(
        SettingsSwitch("pref_actionbar_switch", "Show runtime toolbar", "Keep the runtime toolbar visible on MIDlet Canvas screens.", true),
        SettingsSwitch("pref_statusbar_switch", "Show system status bar", "Keep the system status bar visible. Enabling it disables the display-cutout area.", false),
        SettingsSwitch(
            "pref_use_display_cutout",
            "Use display cutout area",
            "Allow compatible MIDlet Canvas content and the runtime toolbar to use the display-cutout area. Enabling it hides the system status bar.",
            true,
        ),
        SettingsSwitch("pref_wakelock_switch", "Keep screen on", null, false),
        SettingsSwitch(
            "pref_screenshot_switch",
            "Raw screenshot",
            "Saves the MIDlet frame at its virtual resolution before scaling, linear filtering, and the screen shader.",
            false,
        ),
        SettingsSwitch("pref_vibration_switch", "Vibration", null, true),
    ),
    experimentalSwitches = listOf(
        SettingsSwitch(
            "micro3d_using_message",
            "Mascot Capsule 3D notification",
            "Show a message when Mascot Capsule 3D is used.",
            false,
        ),
    ),
    showProfiles = true,
    workingDirectory = "/storage/emulated/0/JL-Mod Plus",
    libraryChoices = listOf(
        SettingsChoice(
            "pref_apps_view",
            "Layout",
            SettingsOption("grid", "Grid"),
            listOf(SettingsOption("list", "List"), SettingsOption("grid", "Grid")),
        ),
        SettingsChoice(
            "pref_apps_icon_ratio",
            "Icon aspect ratio",
            SettingsOption("square", "1:1"),
            listOf(SettingsOption("square", "1:1"), SettingsOption("portrait", "3:4")),
        ),
        SettingsChoice(
            "pref_apps_icon_shape",
            "Icon shape",
            SettingsOption("round", "Rounded corners"),
            listOf(SettingsOption("round", "Rounded corners"), SettingsOption("square", "Sharp corners")),
        ),
        SettingsChoice(
            "pref_apps_grid_spacing",
            "Grid spacing",
            SettingsOption("standard", "Standard (8 dp)"),
            listOf(
                SettingsOption("none", "None (0 dp)"),
                SettingsOption("compact", "Compact (4 dp)"),
                SettingsOption("standard", "Standard (8 dp)"),
                SettingsOption("spacious", "Spacious (12 dp)"),
            ),
        ),
    ),
    librarySwitches = listOf(
        SettingsSwitch(
            "pref_apps_enhanced_icons",
            "Enhanced icons",
            "Adjusts icon size and color so icons look more consistent in the Library.",
            true,
        ),
        SettingsSwitch(
            "pref_apps_hide_grid_titles",
            "Hide MIDlet titles",
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
