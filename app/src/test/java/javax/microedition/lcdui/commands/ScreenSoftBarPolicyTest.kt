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

import javax.microedition.lcdui.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenSoftBarPolicyTest {
    @Test
    fun okBackAndItems_followMidpPlacement() {
        val ok = Command("Confirm", Command.OK, 1)
        val back = Command("Back", Command.BACK, 1)
        val firstItem = Command("Buy", Command.ITEM, 1)
        val secondItem = Command("Info", Command.ITEM, 2)

        val presentation = ScreenSoftBarPolicy.present(listOf(secondItem, back, ok, firstItem))

        assertNull(presentation.left)
        assertSame(ok, presentation.middle)
        assertSame(back, presentation.right)
        assertEquals(listOf(firstItem, secondItem), presentation.overflow)
    }

    @Test
    fun aSingleCommand_staysOnTheLeft() {
        val command = Command("Help", Command.HELP, 1)

        val presentation = ScreenSoftBarPolicy.present(listOf(command))

        assertSame(command, presentation.left)
        assertNull(presentation.middle)
        assertNull(presentation.right)
        assertEquals(emptyList<Command>(), presentation.overflow)
    }

    @Test
    fun aSingleOkCommand_doesNotAppearTwice() {
        val command = Command("Confirm", Command.OK, 1)

        val presentation = ScreenSoftBarPolicy.present(listOf(command))

        assertSame(command, presentation.left)
        assertNull(presentation.middle)
        assertNull(presentation.right)
    }

    @Test
    fun lowerPriorityValueWinsWithinBackCommands() {
        val later = Command("Later", Command.BACK, 2)
        val preferred = Command("Preferred", Command.BACK, 1)

        val presentation = ScreenSoftBarPolicy.present(listOf(later, preferred))

        assertSame(preferred, presentation.right)
        assertSame(later, presentation.left)
    }
}
