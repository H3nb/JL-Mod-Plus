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
 * Commands owned by the Android host.  These names intentionally do not use
 * MIDP/Canvas key codes: a host surface is not a guest MIDlet.
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

    /** START and SELECT are emulator shortcuts even while a Canvas is running. */
    val isGlobalShortcut: Boolean
        get() = this == OpenMenu || this == OpenKeypad

    /** Host commands are edge-triggered; no Android repeat should be synthesized for them. */
    val isOneShot: Boolean
        get() = true

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
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_5,
            -> PreviousTab
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_6,
            -> NextTab
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_9,
            -> OpenMenu
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_10,
            -> OpenKeypad
            else -> null
        }
    }
}
