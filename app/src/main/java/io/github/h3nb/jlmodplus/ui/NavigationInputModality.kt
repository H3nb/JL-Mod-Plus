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

package io.github.h3nb.jlmodplus.ui

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Returns true for keys that indicate navigation or activation of an app-owned surface.
 * Text-entry keys are intentionally excluded so typing in a search or editor does not turn on
 * controller-style focus indicators.
 */
internal fun isNavigationKeyCode(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_TAB,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_ESCAPE,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_MOVE_HOME,
    KeyEvent.KEYCODE_MOVE_END,
    -> true
    else -> false
}

internal fun isNavigationKeyEvent(event: KeyEvent): Boolean = isNavigationKeyCode(event.keyCode)

/** Hides a navigation-only indicator as soon as the user starts a touch gesture. */
internal fun Modifier.clearNavigationFocusOnTouch(onTouchInput: () -> Unit): Modifier =
    pointerInput(onTouchInput) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) {
                    onTouchInput()
                }
            }
        }
    }

/** Shows a navigation-only indicator for keyboard/DPAD navigation without consuming the event. */
internal fun Modifier.showNavigationFocusForKey(onNavigationInput: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.action == KeyEvent.ACTION_DOWN && isNavigationKeyEvent(nativeEvent)) {
            onNavigationInput()
        }
        false
    }
