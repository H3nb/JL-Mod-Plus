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

/** Pure runtime chrome and safe-rectangle policy; coordinates are window-relative pixels. */
final class GuestWindowPolicy {
	private GuestWindowPolicy() { }

	static Chrome resolve(boolean canvas, boolean fullscreen, boolean userAllowsCutout) {
		return new Chrome(canvas, !canvas || !fullscreen, !canvas, !canvas,
				canvas && userAllowsCutout);
	}

	/** Compact landscape trades 8dp of touch height for guest space; enlarged text keeps 48dp. */
	static int canvasToolbarHeightDp(int windowWidth, int windowHeight, float fontScale) {
		return windowHeight > 0 && windowWidth > windowHeight && fontScale <= 1f ? 40 : 48;
	}

	/** Account only for the safe area that still overlaps this component, not its ancestors. */
	static Padding paddingForBounds(int windowWidth, int windowHeight,
			int viewLeft, int viewTop, int viewWidth, int viewHeight,
			int safeLeft, int safeTop, int safeRight, int safeBottom) {
		int left = Math.min(Math.max(0, safeLeft - viewLeft), Math.max(0, viewWidth - 1));
		int right = Math.min(Math.max(0, viewLeft + viewWidth - windowWidth + safeRight),
				Math.max(0, viewWidth - left - 1));
		int top = Math.min(Math.max(0, safeTop - viewTop), Math.max(0, viewHeight - 1));
		int bottom = Math.min(Math.max(0, viewTop + viewHeight - windowHeight + safeBottom),
				Math.max(0, viewHeight - top - 1));
		return new Padding(left, top, right, bottom);
	}

	/** Horizontal clearance where a rectangular control band meets a rounded corner. */
	static int roundedCornerClearance(int radius, int centerY, int bandTop, int bandBottom,
			boolean topCorner) {
		if (radius <= 0 || bandBottom <= bandTop) return 0;
		int distance = topCorner ? centerY - bandTop : bandBottom - centerY;
		if (distance <= 0) return 0;
		distance = Math.min(distance, radius);
		return (int) Math.ceil(radius - Math.sqrt((double) radius * radius - (double) distance * distance));
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
