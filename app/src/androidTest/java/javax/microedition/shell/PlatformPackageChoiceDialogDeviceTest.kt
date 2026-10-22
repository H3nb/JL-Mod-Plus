// SPDX-License-Identifier: Apache-2.0
package javax.microedition.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PlatformPackageChoiceDialogDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun packageChoiceUsesProjectCopyAndOnlyInvokesTheSelectedAction() {
        var install = 0
        var browser = 0
        var cancel = 0
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

        rule.setContent {
            JLModPlusTheme {
                PlatformPackageChoiceDialog(
                    onInstall = { install++ },
                    onBrowser = { browser++ },
                    onCancel = { cancel++ },
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.platform_package_choice_title)).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.platform_package_choice_message)).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.platform_package_install))
            .assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, install)
            assertEquals(0, browser)
            assertEquals(0, cancel)
        }
    }

    @Test fun shortLandscapeLargeTextCanReachBrowserAndCancel() {
        var browser = 0
        var cancel = 0
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme {
                        PlatformPackageChoiceDialog(
                            onInstall = {},
                            onBrowser = { browser++ },
                            onCancel = { cancel++ },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText(resources.getString(R.string.platform_package_browser))
            .performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, browser)
            assertEquals(0, cancel)
        }
        rule.onNodeWithText(resources.getString(android.R.string.cancel))
            .performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, cancel) }
    }
}
