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
        SettingsOption("system", "Follow system settings"),
    ),
    language = SettingsOption("", "Follow system settings"),
    languages = listOf(
        SettingsOption("", "Follow system settings"),
        SettingsOption("en", "English"),
        SettingsOption("id", "Bahasa Indonesia"),
    ),
    switches = listOf(
        SettingsSwitch("pref_actionbar_switch", "Enable ActionBar", "In fullscreen applications", true),
        SettingsSwitch("pref_statusbar_switch", "Enable statusbar", "In fullscreen applications", false),
        SettingsSwitch("pref_wakelock_switch", "Keep screen on", null, false),
        SettingsSwitch("pref_screenshot_switch", "Raw screenshot", "Disable scaling and filtering for screenshots", false),
        SettingsSwitch("pref_vibration_switch", "Enable vibration", null, true),
    ),
    experimentalSwitches = listOf(
        SettingsSwitch("micro3d_using_message", "Detect Mascot Capsule 3D", "Show message when using", false),
    ),
    showProfiles = true,
    workingDirectory = "/storage/emulated/0/JL-Mod Plus",
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
