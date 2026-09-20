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
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostInputRouterTest {
    @Test
    fun modalConsumesOrdinaryKeyboardKeyInsteadOfLeakingToGuest() {
        val host = RecordingHost(modal = true)
        val router = HostInputRouter(host)

        assertTrue(router.onKeyEvent(keyEvent(3, KeyEvent.KEYCODE_Q, KeyEvent.ACTION_DOWN)))
        assertTrue(host.commands.isEmpty())
    }

    @Test
    fun ordinaryKeyboardKeyFallsThroughWhenNoHostModalOwnsIt() {
        val host = RecordingHost(modal = false)
        val router = HostInputRouter(host)

        assertFalse(router.onKeyEvent(keyEvent(3, KeyEvent.KEYCODE_Q, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun releaseDeviceOnlyReleasesCommandsCapturedFromThatDevice() {
        val host = RecordingHost(modal = true)
        val router = HostInputRouter(host)

        assertTrue(router.onKeyEvent(keyEvent(4, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN)))
        assertTrue(router.onKeyEvent(keyEvent(5, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
        host.commands.clear()

        router.releaseDevice(4)

        assertEquals(listOf(HostCommand.NavigateLeft to false), host.commands)
    }

    private fun keyEvent(deviceId: Int, keyCode: Int, action: Int): KeyEvent =
        KeyEvent(
            0L,
            0L,
            action,
            keyCode,
            0,
            0,
            deviceId,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )

    private class RecordingHost(
        private val modal: Boolean,
    ) : ControllerHostSink {
        val commands = mutableListOf<Pair<HostCommand, Boolean>>()

        override fun currentCanvas(): Canvas? = null

        override fun currentDisplayable(): Displayable? = null

        override fun onHostCommand(command: HostCommand, pressed: Boolean): Boolean {
            commands += command to pressed
            return true
        }

        override fun onControllerInputAccepted() = Unit

        override fun onControllerNotice(message: String) = Unit

        override fun isControllerModalActive(): Boolean = modal
    }
}
