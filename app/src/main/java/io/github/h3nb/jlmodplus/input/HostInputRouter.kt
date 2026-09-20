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
 * Routes host-owned physical key edges before ordinary Android/View dispatch.
 *
 * The universal KeyMapper `M` target is source-agnostic for user-added bindings: a keyboard key,
 * phone key, or gamepad button assigned to M becomes [HostCommand.OpenMenu]. Android BACK keeps
 * its established Activity/Back-dispatch path so this router does not change its edge/long-press
 * semantics. Other inferred host navigation commands remain gamepad-only.
 */
class HostInputRouter(
    private val host: ControllerHostSink,
) {
    private data class PhysicalKey(val deviceId: Int, val keyCode: Int)

    private val captured = LinkedHashMap<PhysicalKey, HostCommand>()

    fun onKeyEvent(event: KeyEvent): Boolean {
        // Physical Android Back belongs to Activity/System back dispatch, even while a host modal
        // is visible. Logical M remains independently assignable from every non-Back physical key.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val mappedMenu = KeyMapper.isOptionsMenuKey(event.keyCode)
        val gamepadEvent = ControllerInputRouter.isGamepadEvent(event)
        val modal = host.isControllerModalActive()
        // A host modal owns every physical key source, including ordinary keyboards. Check the
        // modal before the source-class early return so keys cannot leak into the guest behind it.
        if (!mappedMenu && !gamepadEvent && !modal) return false

        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)
        val canvasVisible = host.currentCanvas() != null
        val command = if (mappedMenu) {
            HostCommand.OpenMenu
        } else {
            HostCommand.fromAndroidKeyCode(event.keyCode)
        }
        val hostOwned = mappedMenu || modal || !canvasVisible || command?.isGlobalShortcut == true

        // Unknown vendor/controller keys must remain available to KeyMapper. A modal is the one
        // exception: its host ownership prevents an arbitrary gamepad key from leaking into the
        // guest. A key explicitly mapped to M is host-owned regardless of its physical source.
        if (!hostOwned) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                if (command == null) return modal
                if (captured.containsKey(physicalKey)) return true
                if (captured.containsValue(command)) {
                    // The command is already down through another physical source. Capture this
                    // source without emitting a duplicate DOWN; the final owner will emit the UP.
                    captured[physicalKey] = command
                    if (gamepadEvent) host.onControllerInputAccepted()
                    return true
                }
                val handled = host.onHostCommand(command, true)
                // A Screen without an app-owned modal may still be a native/View-backed guest
                // surface. Let its ordinary gamepad navigation continue through Android dispatch
                // when the host has no command owner, just as Canvas keys do.
                if (!handled && !command.isGlobalShortcut && !modal) {
                    return false
                }
                captured[physicalKey] = command
                // Only a real gamepad interaction should reveal an auto-hidden touch overlay.
                // Keyboard/phone keys mapped to M share the host command but retain their old UI
                // feedback behavior.
                if (gamepadEvent && (handled || modal || !canvasVisible || command.isGlobalShortcut)) {
                    host.onControllerInputAccepted()
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val capturedCommand = captured.remove(physicalKey)
                if (capturedCommand != null) {
                    if (!captured.containsValue(capturedCommand)) {
                        host.onHostCommand(capturedCommand, false)
                    }
                    return true
                }
                return modal
            }

            else -> return modal
        }
    }



    fun releaseDevice(deviceId: Int) {
        val affected = LinkedHashSet<HostCommand>()
        captured.entries
            .filter { it.key.deviceId == deviceId }
            .sortedWith(compareBy({ it.key.keyCode }, { it.value.ordinal }))
            .forEach { (key, command) ->
                if (captured.remove(key) != null) affected += command
            }
        affected.sortedBy { it.ordinal }.forEach { command ->
            if (!captured.containsValue(command)) host.onHostCommand(command, false)
        }
    }

    fun clear() {
        val active = captured.values.toSet().sortedBy { it.ordinal }
        captured.clear()
        active.forEach { host.onHostCommand(it, false) }
    }
}

