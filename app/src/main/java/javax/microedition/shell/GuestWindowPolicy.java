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

final class GuestWindowPolicy {
	private GuestWindowPolicy() {
	}

	static boolean canUseDisplayCutout(boolean canvas,
			boolean statusBarEnabled, boolean actionBarEnabled, boolean userAllowsCutout) {
		// The Compose ActionBar is part of the host content and may occupy the cutout area. The
		// system status bar cannot be combined with a cutout-enabled MIDlet because both reserve
		// the same top inset.
		return userAllowsCutout && canvas && !statusBarEnabled;
	}

	/**
	 * Resolves the complete host-chrome contract for one displayable kind.
	 *
	 * <p>The runtime toolbar, system bars, cutout eligibility, and guest padding must all use the
	 * same interpretation of the three user-facing switches. Keeping this state as one value makes
	 * transitions between Canvas and form displayables deterministic and testable without an
	 * Android window.</p>
	 */
	static Chrome resolve(boolean canvas, boolean statusBarEnabled,
			boolean actionBarEnabled, boolean userAllowsCutout) {
		return new Chrome(
				canvas,
				!canvas || actionBarEnabled,
				!canvas || statusBarEnabled,
				!canvas,
				canUseDisplayCutout(canvas, statusBarEnabled, actionBarEnabled, userAllowsCutout));
	}

	static Padding calculate(boolean canvas, boolean statusBarEnabled,
			boolean actionBarEnabled, boolean userAllowsCutout,
			int systemLeft, int statusTop, int systemRight, int navigationBottom,
			int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom, int imeBottom) {
		return calculate(resolve(canvas, statusBarEnabled, actionBarEnabled, userAllowsCutout),
				systemLeft, statusTop, systemRight, navigationBottom,
				cutoutLeft, cutoutTop, cutoutRight, cutoutBottom, imeBottom);
	}

	static Padding calculate(Chrome chrome,
			int systemLeft, int statusTop, int systemRight, int navigationBottom,
			int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom, int imeBottom) {
		if (chrome.canvas) {
			return new Padding(
					chrome.cutoutAllowed ? 0 : cutoutLeft,
					chrome.cutoutAllowed ? 0 : Math.max(chrome.statusBarVisible ? statusTop : 0, cutoutTop),
					chrome.cutoutAllowed ? 0 : cutoutRight,
					chrome.cutoutAllowed ? 0 : cutoutBottom);
		}
		return new Padding(
				Math.max(systemLeft, cutoutLeft),
				Math.max(statusTop, cutoutTop),
				Math.max(systemRight, cutoutRight),
				Math.max(Math.max(navigationBottom, imeBottom), cutoutBottom));
	}

	static final class Chrome {
		final boolean canvas;
		final boolean toolbarVisible;
		final boolean statusBarVisible;
		final boolean navigationBarVisible;
		final boolean cutoutAllowed;

		private Chrome(boolean canvas, boolean toolbarVisible, boolean statusBarVisible,
				boolean navigationBarVisible, boolean cutoutAllowed) {
			this.canvas = canvas;
			this.toolbarVisible = toolbarVisible;
			this.statusBarVisible = statusBarVisible;
			this.navigationBarVisible = navigationBarVisible;
			this.cutoutAllowed = cutoutAllowed;
		}
	}

	static final class Padding {
		final int left;
		final int top;
		final int right;
		final int bottom;

		private Padding(int left, int top, int right, int bottom) {
			this.left = left;
			this.top = top;
			this.right = right;
			this.bottom = bottom;
		}
	}
}
