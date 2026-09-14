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

import android.view.KeyEvent
import javax.microedition.lcdui.keyboard.KeyMapper

/**
 * Routes only host-owned controller key edges.
 *
 * Ordinary controller keys are deliberately returned to Android/View dispatch when a MIDP
 * Canvas is active. Canvas then applies the universal KeyMapper, which is the sole digital guest
 * mapping. The one runtime host action exposed through that same map is `M`: any physical
 * controller key assigned to KeyMapper.KEY_OPTIONS_MENU becomes [HostCommand.OpenMenu].
 */
class HostInputRouter(
    private val host: ControllerHostSink,
) {
    private data class PhysicalKey(val deviceId: Int, val keyCode: Int)

    private val captured = LinkedHashMap<PhysicalKey, HostCommand>()

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!ControllerInputRouter.isGamepadEvent(event)) return false

        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)
        val modal = host.isControllerModalActive()
        val canvasVisible = host.currentCanvas() != null
        val mappedMenu = KeyMapper.isOptionsMenuKey(event.keyCode)
        val command = if (mappedMenu) {
            HostCommand.OpenMenu
        } else {
            HostCommand.fromAndroidKeyCode(event.keyCode)
        }
        val hostOwned = mappedMenu || modal || !canvasVisible || command?.isGlobalShortcut == true

        // Unknown vendor/controller keys must remain available to KeyMapper. A modal is the one
        // exception: its host ownership prevents an arbitrary key from leaking into the guest.
        // A key explicitly mapped to M is also host-owned even while a Canvas is visible.
        if (!hostOwned) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                if (command == null) return modal
                if (captured.containsKey(physicalKey)) return true
                val handled = host.onHostCommand(command, true)
                // A Screen without an app-owned modal may still be a native/View-backed guest
                // surface. Let its ordinary navigation keys continue through Android dispatch
                // when the host has no command owner, just as Canvas keys do.
                if (!handled && !command.isGlobalShortcut && !modal) {
                    return false
                }
                captured[physicalKey] = command
                if (handled || modal || !canvasVisible || command.isGlobalShortcut) {
                    host.onControllerInputAccepted()
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val capturedCommand = captured.remove(physicalKey)
                if (capturedCommand != null) {
                    host.onHostCommand(capturedCommand, false)
                    return true
                }
                return modal
            }

            else -> return modal
        }
    }

    /** Releases only host contacts captured by this router; stale UP events are ignored. */
    fun releaseCapturedKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)
        val command = captured.remove(physicalKey) ?: return false
        host.onHostCommand(command, false)
        return true
    }

    fun clear() {
        val active = captured.values.toList()
        captured.clear()
        active.forEach { host.onHostCommand(it, false) }
    }
}
