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
    private var modalBoundaryActive = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        // A modal may have been opened by touch/toolbar since the previous controller event.
        // Close the whole guest key ledger exactly once on the ownership transition so keys that
        // were DOWN before the modal cannot remain logically pressed behind it.
        syncModalBoundary()

        // BACK is the built-in default M binding, but MicroActivity deliberately owns its legacy
        // short/long-press behavior and Android's system Back callback. Only additional physical
        // bindings to M are intercepted here.
        val mappedMenu = event.keyCode != KeyEvent.KEYCODE_BACK &&
            KeyMapper.isOptionsMenuKey(event.keyCode)
        val gamepadEvent = ControllerInputRouter.isGamepadEvent(event)
        if (!mappedMenu && !gamepadEvent) return false

        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)
        val modal = host.isControllerModalActive()
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
                val handled = host.onHostCommand(command, true)
                // OpenMenu/OpenKeypad may synchronously create a host modal. Re-check after the
                // command so the guest ledger is released at the same edge that opened it.
                syncModalBoundary()
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
                    host.onHostCommand(capturedCommand, false)
                    syncModalBoundary()
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
        syncModalBoundary()
        return true
    }

    fun onModalChanged(active: Boolean): Boolean {
        if (active == modalBoundaryActive) return false
        if (active) host.currentCanvas()?.clearInputState()
        modalBoundaryActive = active
        return true
    }

    private fun syncModalBoundary() {
        onModalChanged(host.isControllerModalActive())
    }

    fun clear() {
        val active = captured.values.toList()
        captured.clear()
        active.forEach { host.onHostCommand(it, false) }
        modalBoundaryActive = false
    }
}
