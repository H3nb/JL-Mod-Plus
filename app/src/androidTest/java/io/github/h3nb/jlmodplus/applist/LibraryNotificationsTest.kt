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

package io.github.h3nb.jlmodplus.applist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.updates.UpdateCandidate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryNotificationsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun updateRemainsAfterSwipeAndOpensUpdates() {
        var opens = 0
        composeRule.setContent {
            JLModPlusTheme {
                LibraryNotificationsScreen(
                    update = candidate("1.2.0"),
                    onBack = {},
                    onOpenUpdate = { opens++ },
                )
            }
        }

        composeRule.onNodeWithText("JL-Mod Plus 1.2.0")
            .performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("JL-Mod Plus 1.2.0").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, opens) }
        composeRule.onNodeWithText("JL-Mod Plus 1.2.0").performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun updateIsReplacedAndClearedWhenNoLongerAvailable() {
        val update = mutableStateOf<UpdateCandidate?>(candidate("1.2.0"))
        composeRule.setContent {
            JLModPlusTheme {
                LibraryNotificationsScreen(
                    update = update.value,
                    onBack = {},
                    onOpenUpdate = {},
                )
            }
        }

        composeRule.onNodeWithText("JL-Mod Plus 1.2.0").assertIsDisplayed()
        composeRule.runOnIdle { update.value = candidate("1.3.0") }
        composeRule.onNodeWithText("JL-Mod Plus 1.2.0").assertDoesNotExist()
        composeRule.onNodeWithText("JL-Mod Plus 1.3.0").assertIsDisplayed()

        composeRule.runOnIdle { update.value = null }
        composeRule.onNodeWithText("JL-Mod Plus 1.3.0").assertDoesNotExist()
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.library_notifications_empty_title,
            )
        ).assertIsDisplayed()
    }

    private fun candidate(version: String) = UpdateCandidate(
        versionName = version,
        versionCode = 100,
        releaseUrl = "https://github.com/H3nb/JL-Mod-Plus/releases/tag/v$version",
    )
}
