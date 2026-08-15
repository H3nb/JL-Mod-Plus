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

package javax.microedition.lcdui.commands

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import javax.microedition.lcdui.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

class ScreenSoftBarComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twoCommands_keepLeftAndRightDirectDispatch() {
        val left = Command("Options", Command.SCREEN, 1)
        val right = Command("Back", Command.BACK, 1)
        var selected: Command? = null
        val presentation = ScreenSoftBarPolicy.present(listOf(left, right))
        composeRule.setContent {
            JLModPlusTheme {
                ScreenSoftBarContent(
                    presentation = presentation,
                    menuVisible = false,
                    onOpenMenu = {},
                    onDismissMenu = {},
                    onCommand = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Back").performClick()

        assertSame(right, selected)
    }

    @Test
    fun semanticCommands_keepOkBackDirectAndItemsInMenu() {
        val first = Command("Options", Command.SCREEN, 1)
        val second = Command("Confirm", Command.OK, 1)
        val third = Command("Back", Command.BACK, 1)
        val fourth = Command("Help", Command.ITEM, 1)
        var selected: Command? = null
        composeRule.setContent {
            JLModPlusTheme {
                ScreenSoftBarContent(
                    presentation = ScreenSoftBarPolicy.present(listOf(first, second, third, fourth)),
                    menuVisible = true,
                    onOpenMenu = {},
                    onDismissMenu = {},
                    onCommand = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Menu").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Help").performClick()

        assertSame(fourth, selected)
    }

    @Test
    fun backAndExitCommandsPreferRightSoftKey() {
        val item = Command("Buy", Command.ITEM, 1)
        val back = Command("Back", Command.BACK, 1)
        val exit = Command("Exit", Command.EXIT, 2)

        val presentation = ScreenSoftBarPolicy.present(listOf(item, back, exit))

        assertSame(back, presentation.right)
        assertNull(presentation.left)
        assertEquals(listOf(item, exit), presentation.overflow)
    }
}
