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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputRouterTest {
    @Test
    fun mapsStandardControllerKeysToHostCommands() {
        assertEquals(HostCommand.Activate, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(HostCommand.Back, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(HostCommand.NavigateLeft, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(HostCommand.OpenMenu, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(HostCommand.OpenKeypad, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(null, HostCommand.fromAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_11))
    }

    @Test
    fun distinguishesControllerSourcesFromKeyboardSources() {
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_GAMEPAD))
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_JOYSTICK))
        assertTrue(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_DPAD))
        assertFalse(ControllerInputRouter.isControllerSource(InputDevice.SOURCE_KEYBOARD))
    }
}
