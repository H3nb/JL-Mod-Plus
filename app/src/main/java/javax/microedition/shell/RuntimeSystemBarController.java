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

package javax.microedition.shell;

import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Applies an already-resolved runtime chrome policy to an Android Window.
 *
 * <p>Policy ownership remains in {@link GuestWindowPolicy}; this class only translates the
 * resolved status/navigation-bar visibility to platform APIs. The same translation is used by
 * the runtime Activity and its Compose dialog windows so opening a host modal cannot create a
 * second system-bar policy.</p>
 */
final class RuntimeSystemBarController {
	private RuntimeSystemBarController() {
	}

	static void apply(Window window, boolean statusBarVisible, boolean navigationBarVisible) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			window.getDecorView().setSystemUiVisibility(
					legacySystemUiVisibility(statusBarVisible, navigationBarVisible));
			return;
		}

		WindowInsetsControllerCompat controller =
				WindowCompat.getInsetsController(window, window.getDecorView());
		controller.setSystemBarsBehavior(
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		setVisibility(controller, WindowInsetsCompat.Type.navigationBars(), navigationBarVisible);
		setVisibility(controller, WindowInsetsCompat.Type.statusBars(), statusBarVisible);
	}

	private static void setVisibility(
			WindowInsetsControllerCompat controller, int type, boolean visible) {
		if (visible) {
			controller.show(type);
		} else {
			controller.hide(type);
		}
	}

	@SuppressWarnings("deprecation")
	static int legacySystemUiVisibility(boolean statusBarVisible, boolean navigationBarVisible) {
		if (statusBarVisible && navigationBarVisible) {
			return View.SYSTEM_UI_FLAG_VISIBLE;
		}

		int flags = View.SYSTEM_UI_FLAG_VISIBLE;
		if (!navigationBarVisible) {
			flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
		}
		if (!statusBarVisible) {
			flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			if (!navigationBarVisible) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
			}
		}
		return flags;
	}
}
