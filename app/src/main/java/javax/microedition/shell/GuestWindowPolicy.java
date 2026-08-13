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

	static boolean canUseDisplayCutout(boolean canvas, boolean skinLayerAvailable,
			boolean statusBarEnabled, boolean actionBarEnabled) {
		return canvas && skinLayerAvailable && !statusBarEnabled && !actionBarEnabled;
	}

	static Padding calculate(boolean canvas, boolean skinLayerAvailable, boolean statusBarEnabled,
			boolean actionBarEnabled, int systemLeft, int statusTop, int systemRight, int navigationBottom,
			int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom, int imeBottom) {
		boolean canUseCutout = canUseDisplayCutout(canvas, skinLayerAvailable,
				statusBarEnabled, actionBarEnabled);
		if (canvas) {
			return new Padding(
					canUseCutout ? 0 : cutoutLeft,
					canUseCutout ? 0 : Math.max(statusBarEnabled ? statusTop : 0, cutoutTop),
					canUseCutout ? 0 : cutoutRight,
					canUseCutout ? 0 : cutoutBottom);
		}
		return new Padding(
				Math.max(systemLeft, cutoutLeft),
				Math.max(statusTop, cutoutTop),
				Math.max(systemRight, cutoutRight),
				Math.max(Math.max(navigationBottom, imeBottom), cutoutBottom));
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
