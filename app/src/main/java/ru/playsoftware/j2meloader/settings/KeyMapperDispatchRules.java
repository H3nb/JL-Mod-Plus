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

package ru.playsoftware.j2meloader.settings;

import android.graphics.Rect;
import android.view.KeyEvent;

/** Compatibility rules for Activity-level mapper interception. */
public final class KeyMapperDispatchRules {
	private KeyMapperDispatchRules() {
	}

	public static boolean isAssignableKey(int action, int keyCode) {
		if (action != KeyEvent.ACTION_DOWN) {
			return false;
		}
		switch (keyCode) {
			case KeyEvent.KEYCODE_HOME:
			case KeyEvent.KEYCODE_VOLUME_UP:
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				return false;
			default:
				return true;
		}
	}

	public static boolean isInsidePopup(Rect popupBounds, int x, int y) {
		return popupBounds != null && popupBounds.contains(x, y);
	}
}
