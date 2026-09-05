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

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class InstallerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHeightConfirmationScrollsToAllActions() {
        val actions = RecordingInstallerActions()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    InstallerScreen(
                        state = InstallerUiState.Confirmation(
                            title = "Demo MIDlet",
                            message = List(8) {
                                "Long compatibility information remains readable and accessible."
                            }.joinToString("\n"),
                            installLabel = "Install",
                            closeLabel = "Cancel",
                            runLabel = "Run existing",
                            iconPath = null,
                        ),
                        actions = actions,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Install").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, actions.installCount)
    }

    @Test
    fun loadingStateCanRequestCancellation() {
        val actions = RecordingInstallerActions()
        setState(InstallerUiState.Loading("MIDlet Installer", "Loading info…"), actions)

        composeRule.onNodeWithText("Loading info…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading info…").assertIsDisplayed()
        composeRule.onAllNodesWithText("Install").assertCountEquals(0)
        composeRule.onAllNodesWithText("Close").assertCountEquals(0)
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, actions.closeCount)
    }

    @Test
    fun conversionStateCanRequestCancellation() {
        setState(
            InstallerUiState.Converting(
                title = "Demo MIDlet",
                message = "Name: Demo MIDlet\nVendor: Example\nVersion: 1.0",
                status = "Converting JAR…",
            ),
        )
        composeRule.onNodeWithText("Converting JAR…").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
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

    @Test
    fun errorStateKeepsAVisibleCloseAction() {
        val actions = RecordingInstallerActions()
        setState(
            InstallerUiState.Error(
                title = "App Bundle Import Failed",
                message = "The selected app bundle is invalid or damaged.",
                closeLabel = "Close",
            ),
            actions,
        )

        composeRule.onNodeWithText("The selected app bundle is invalid or damaged.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        assertEquals(1, actions.closeCount)
    }

    @Test fun recoveryActionsRemainReachableInShortWindowWithLargeText() {
        val actions = RecordingInstallerActions()
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme {
                        InstallerScreen(InstallerUiState.Error("Installation incomplete",
                            "Application files were saved, but the Library could not finish updating. Refresh the Library before trying again.",
                            "Close", "Refresh Library", "Storage error"), actions)
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
        capturePopup("recovery-short.png")
        composeRule.onNodeWithText("Copy details").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Refresh Library").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Close").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, actions.installCount)
        assertEquals(1, actions.closeCount)
    }

    @Test fun bulkRetryRemainsReachableInShortWindowWithLargeText() {
        var retries = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme {
                        BulkInstallSurface(BulkInstallViewModel.State.Finished(
                            BulkInstallPlan(1, File("/workdir"), emptyList()),
                            listOf(BulkInstallResult("one", "Game", BulkInstallResultKind.PartiallyInstalled)),
                            cancelled = true), onToggle = {}, onRecommended = {}, onClear = {},
                            onInstall = {}, onRetry = { retries++ }, onCancel = {}, onClose = {})
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
        capturePopup("bulk-short.png")
        composeRule.onNodeWithTag("bulk-results").performScrollToIndex(1)
        composeRule.onNodeWithText("Review unfinished items").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test fun shortPopupWrapsContentInsteadOfFillingMaximumHeight() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                JLModPlusTheme {
                    InstallerScreen(InstallerUiState.Loading("Installer", "Reading"), RecordingInstallerActions())
                }
            }
        }
        val bounds = composeRule.onNodeWithTag("installer-popup").getUnclippedBoundsInRoot()
        assertEquals(448f, (bounds.right - bounds.left).value, 1f)
        assertTrue("Short content must wrap within the adaptive height limit", bounds.bottom - bounds.top < 208.dp)
    }

    private fun capturePopup(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, name).outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
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
