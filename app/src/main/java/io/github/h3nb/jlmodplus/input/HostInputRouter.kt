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
 * semantics. Physical ownership is scoped to the host target captured at DOWN time.
 */
class HostInputRouter(
    private val host: ControllerHostSink,
    private val targetProvider: () -> ControllerHostTarget? = { host.currentControllerTarget() },
) {
    private data class PhysicalKey(val deviceId: Int, val keyCode: Int)
    private data class Capture(
        val command: HostCommand,
        val target: ControllerHostTarget?,
    )

    private val captured = LinkedHashMap<PhysicalKey, Capture>()

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val physicalKey = PhysicalKey(event.deviceId, event.keyCode)

        // Once a physical source is captured, it remains router-owned until its matching UP even
        // if the modal closes or the target changes in between. This is the stale-edge tombstone:
        // no later edge from that physical press may fall through to the replacement/guest surface.
        val existing = captured[physicalKey]
        if (existing != null) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> {
                    captured.remove(physicalKey)
                    val stillOwned = captured.values.any {
                        it.command == existing.command && it.target == existing.target
                    }
                    if (!stillOwned && targetProvider() == existing.target) {
                        host.onHostCommand(existing.command, false)
                    }
                    true
                }
                else -> true
            }
        }

        val mappedMenu = KeyMapper.isOptionsMenuKey(event.keyCode)
        val gamepadEvent = ControllerInputRouter.isGamepadEvent(event)
        val modal = host.isControllerModalActive()
        // At the Activity/guest boundary a modal owns unknown physical keys to prevent guest leak.
        // Dialog-window keys are classified before reaching this router.
        if (!mappedMenu && !gamepadEvent && !modal) return false

        val canvasVisible = host.currentCanvas() != null
        val command = if (mappedMenu) HostCommand.OpenMenu else HostCommand.fromAndroidKeyCode(event.keyCode)
        val hostOwned = mappedMenu || modal || !canvasVisible || command?.isGlobalShortcut == true
        if (!hostOwned) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                if (command == null) return modal
                if (captured.containsKey(physicalKey)) return true

                // Capture BEFORE the host callback. The callback may synchronously replace the
                // surface and invoke clear() re-entrantly; the outer call must not bind to the new UI.
                val capture = Capture(command, targetProvider())
                val alreadyOwned = captured.values.any {
                    it.command == capture.command && it.target == capture.target
                }
                captured[physicalKey] = capture
                if (alreadyOwned) {
                    if (gamepadEvent) host.onControllerInputAccepted()
                    return true
                }

                val handled = host.onHostCommand(command, true)
                if (!handled && !command.isGlobalShortcut && !modal) {
                    if (captured[physicalKey] == capture) captured.remove(physicalKey)
                    return false
                }
                if (gamepadEvent && (handled || modal || !canvasVisible || command.isGlobalShortcut)) {
                    host.onControllerInputAccepted()
                }
                true
            }

            KeyEvent.ACTION_UP -> modal

            else -> modal
        }
    }

    fun releaseDevice(deviceId: Int) {
        val removed = ArrayList<Capture>()
        captured.entries
            .filter { it.key.deviceId == deviceId }
            .sortedWith(compareBy({ it.key.keyCode }, { it.value.command.ordinal }))
            .forEach { (key, capture) ->
                if (captured.remove(key) != null) removed += capture
            }
        val currentTarget = targetProvider()
        removed.distinct().sortedBy { it.command.ordinal }.forEach { capture ->
            val stillOwned = captured.values.any {
                it.command == capture.command && it.target == capture.target
            }
            if (!stillOwned && currentTarget == capture.target) {
                host.onHostCommand(capture.command, false)
            }
        }
    }

    /**
     * Clears physical ownership at an Activity/focus boundary. Captures whose target is already
     * stale are discarded; current-target commands receive one final UP.
     *
     * Logical host-surface transitions deliberately do not call this method: their old captures
     * remain as target-stamped tombstones until the matching physical UP, which consumes that UP
     * without replaying it into the replacement surface.
     */
    fun clear() {
        val currentTarget = targetProvider()
        val active = captured.values
            .filter { it.target == currentTarget }
            .distinct()
            .sortedBy { it.command.ordinal }
        captured.clear()
        active.forEach { host.onHostCommand(it.command, false) }
    }
}
