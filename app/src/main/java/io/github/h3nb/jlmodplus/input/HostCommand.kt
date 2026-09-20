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

/**
 * Commands owned by the Android host. These names intentionally do not use
 * MIDP/Canvas key codes: a host surface is not a guest MIDlet.
 *
 * Only the minimal navigation set is inferred directly from physical gamepad
 * buttons. Runtime MIDlet menu access is deliberately *not* hard-wired to
 * START/SELECT/L1/R1: users assign any physical digital input to the shared
 * KeyMapper `M` target instead. This keeps one universal digital mapping for
 * keyboard/keypad/gamepad input and prevents host shortcuts from stealing
 * buttons while a Canvas is running.
 */
enum class HostCommand {
    NavigateUp,
    NavigateDown,
    NavigateLeft,
    NavigateRight,
    Activate,
    Back,
    OpenMenu,
    NextTab,
    PreviousTab,
    OpenKeypad;

    /** Commands explicitly emitted by a host surface may still be globally owned. */
    val isGlobalShortcut: Boolean
        get() = this == OpenMenu || this == OpenKeypad

    companion object {
        @JvmStatic
        fun fromAndroidKeyCode(keyCode: Int): HostCommand? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> NavigateUp
            KeyEvent.KEYCODE_DPAD_DOWN -> NavigateDown
            KeyEvent.KEYCODE_DPAD_LEFT -> NavigateLeft
            KeyEvent.KEYCODE_DPAD_RIGHT -> NavigateRight
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_1,
            -> Activate
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_2,
            -> Back
            else -> null
        }
    }
}
