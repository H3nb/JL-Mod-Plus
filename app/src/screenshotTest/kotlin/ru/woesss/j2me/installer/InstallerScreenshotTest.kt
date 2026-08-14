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

package ru.woesss.j2me.installer

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val NoOpInstallerActions = object : InstallerActions {
    override fun onInstall() = Unit
    override fun onClose() = Unit
    override fun onRunExisting() = Unit
    override fun onLaunchInstalled() = Unit
}

@PreviewTest
@Preview(name = "Installer loading", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun InstallerLoadingScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        InstallerScreen(
            state = InstallerUiState.Loading(
                title = "MIDlet installer",
                status = "loading info…",
            ),
            actions = NoOpInstallerActions,
        )
    }
}

@PreviewTest
@Preview(name = "Installer confirmation", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun InstallerConfirmationScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        InstallerScreen(
            state = InstallerUiState.Confirmation(
                title = "Demo MIDlet",
                message = "Name: Demo MIDlet\nVendor: Example Studio\nVersion: 1.0\n\nInstall this application?",
                installLabel = "Install",
                closeLabel = "Cancel",
                runLabel = null,
                iconPath = null,
            ),
            actions = NoOpInstallerActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Installer overwrite dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun InstallerOverwriteDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        InstallerScreen(
            state = InstallerUiState.Confirmation(
                title = "Demo MIDlet",
                message = "App already installed.\n\nDo you want to reinstall it?\nAll data will be saved.",
                installLabel = "Install",
                closeLabel = "Cancel",
                runLabel = "Start",
                iconPath = null,
            ),
            actions = NoOpInstallerActions,
        )
    }
}

@PreviewTest
@Preview(name = "Installer converting", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun InstallerConvertingScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        InstallerScreen(
            state = InstallerUiState.Converting(
                title = "Demo MIDlet",
                message = "Name: Demo MIDlet\nVendor: Example Studio\nVersion: 1.0",
                status = "Converting JAR…",
            ),
            actions = NoOpInstallerActions,
        )
    }
}

@PreviewTest
@Preview(name = "Installer success", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun InstallerSuccessScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        InstallerScreen(
            state = InstallerUiState.Success(
                title = "Demo MIDlet",
                status = "Application successfully installed!",
                startLabel = "Start",
                closeLabel = "Close",
                iconPath = null,
            ),
            actions = NoOpInstallerActions,
        )
    }
}
