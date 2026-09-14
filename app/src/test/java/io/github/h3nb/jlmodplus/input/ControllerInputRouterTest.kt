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

package io.github.h3nb.jlmodplus.input

import android.view.InputDevice
import android.view.KeyEvent
import javax.microedition.lcdui.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputRouterTest {
    @Test
    fun mapsControllerButtonsToStableControlTokens() {
        assertEquals(
            ControllerInputRouter.CONTROL_BUTTON_A,
            ControllerInputRouter.controlTokenForKeyCode(KeyEvent.KEYCODE_BUTTON_A),
        )
        assertEquals(
            ControllerInputRouter.CONTROL_BUTTON_START,
            ControllerInputRouter.controlTokenForKeyCode(KeyEvent.KEYCODE_BUTTON_START),
        )
        assertEquals(
            ControllerInputRouter.CONTROL_DPAD_LEFT,
            ControllerInputRouter.controlTokenForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT),
        )
    }

    @Test
    fun distinguishesControllerSourcesFromKeyboardSources() {
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_GAMEPAD))
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_JOYSTICK))
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_DPAD))
        assertFalse(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_KEYBOARD))
    }

    @Test
    fun canonicalGuestKeysPreserveZeroAndMenuAsDifferentValues() {
        assertEquals(Canvas.KEY_NUM0, ControllerInputRouter.guestKeyCode(GuestKey.NUM0))
        assertEquals(Canvas.KEY_FIRE, ControllerInputRouter.guestKeyCode(GuestKey.FIRE))
        assertTrue(Canvas.KEY_NUM0 != 0)
        assertTrue(ControllerInputRouter.guestKeyCode(GuestKey.NUM0) != 0)
    }
}
