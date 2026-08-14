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

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class InstallerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateHidesCancellationActions() {
        setState(InstallerUiState.Loading("MIDlet installer", "loading info…"))

        composeRule.onNodeWithText("loading info…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("loading info…").assertIsDisplayed()
        composeRule.onAllNodesWithText("Install").assertCountEquals(0)
        composeRule.onAllNodesWithText("Close").assertCountEquals(0)
    }

    @Test
    fun conversionStateHidesCancellationActions() {
        setState(
            InstallerUiState.Converting(
                title = "Demo MIDlet",
                message = "Name: Demo MIDlet\nVendor: Example\nVersion: 1.0",
                status = "Converting JAR…",
            ),
        )
        composeRule.onNodeWithText("Converting JAR…").assertIsDisplayed()
        composeRule.onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test
    fun confirmationInstallAndCancelAreExplicitEvents() {
        val actions = RecordingInstallerActions()
        setState(
            InstallerUiState.Confirmation(
                title = "Demo MIDlet",
                message = "Install this application?",
                installLabel = "Install",
                closeLabel = "Cancel",
                runLabel = null,
                iconPath = null,
            ),
            actions,
        )

        composeRule.onNodeWithText("Install").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, actions.installCount)
        assertEquals(1, actions.closeCount)
    }

    @Test
    fun overwriteConfirmationPreservesRunAction() {
        val actions = RecordingInstallerActions()
        setState(
            InstallerUiState.Confirmation(
                title = "Demo MIDlet",
                message = "App already installed.\n\nDo you want to reinstall it?",
                installLabel = "Install",
                closeLabel = "Cancel",
                runLabel = "Start",
                iconPath = null,
            ),
            actions,
        )

        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Install").performClick()

        assertEquals(1, actions.runCount)
        assertEquals(1, actions.installCount)
    }

    @Test
    fun successOffersStartAndCloseWithoutReinstallAction() {
        val actions = RecordingInstallerActions()
        setState(
            InstallerUiState.Success(
                title = "Demo MIDlet",
                status = "Application successfully installed!",
                startLabel = "Start",
                closeLabel = "Close",
                iconPath = null,
            ),
            actions,
        )

        composeRule.onNodeWithText("Demo MIDlet").assertIsDisplayed()
        composeRule.onNodeWithText("Application successfully installed!").assertIsDisplayed()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onAllNodesWithText("Install").assertCountEquals(0)

        assertEquals(1, actions.launchCount)
        assertEquals(1, actions.closeCount)
    }

    private fun setState(
        state: InstallerUiState,
        actions: RecordingInstallerActions = RecordingInstallerActions(),
    ) {
        composeRule.setContent {
            JLModPlusTheme {
                InstallerScreen(state = state, actions = actions)
            }
        }
    }

    private class RecordingInstallerActions : InstallerActions {
        var installCount = 0
        var closeCount = 0
        var runCount = 0
        var launchCount = 0

        override fun onInstall() {
            installCount++
        }

        override fun onClose() {
            closeCount++
        }

        override fun onRunExisting() {
            runCount++
        }

        override fun onLaunchInstalled() {
            launchCount++
        }
    }
}
