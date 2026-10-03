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

package javax.microedition.lcdui.overlay;

import android.graphics.RectF;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Safe host viewport expressed in the translated OverlayView canvas coordinates. */
final class DiagnosticOverlayLayout {
	private DiagnosticOverlayLayout() {
	}

	/** Anchor each row to its column's chosen edge without padding metric cells. */
	static float rowLeft(float left, float right, float rowWidth, boolean alignRight,
			int column, int columns, float columnStride) {
		return alignRight ? right - (columns - 1 - column) * columnStride - rowWidth
				: left + column * columnStride;
	}

	static void bounds(View view, RectF bounds, int[] location, int[] rootLocation) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		Insets bars = insets == null ? Insets.NONE : insets.getInsets(WindowInsetsCompat.Type.systemBars());
		Insets cutout = insets == null ? Insets.NONE
				: insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
		Insets safe = Insets.max(bars, cutout);
		View root = view.getRootView();
		view.getLocationInWindow(location);
		root.getLocationInWindow(rootLocation);
		Insets overlap = overlap(safe, rootLocation[0], rootLocation[1], root.getWidth(), root.getHeight(),
				location[0], location[1], view.getWidth(), view.getHeight());
		float margin = 8f * view.getResources().getDisplayMetrics().density;
		int x = view instanceof OverlayView ? ((OverlayView) view).getContentOffsetX() : 0;
		int y = view instanceof OverlayView ? ((OverlayView) view).getContentOffsetY() : 0;
		bounds.set(overlap.left + margin - x, overlap.top + margin - y,
				view.getWidth() - overlap.right - margin - x,
				view.getHeight() - overlap.bottom - margin - y);
	}

	/** Avoid applying bars twice when the host already fitted or padded the child viewport. */
	static Insets overlap(Insets safe, int rootX, int rootY, int rootWidth, int rootHeight,
			int viewX, int viewY, int width, int height) {
		return Insets.of(
				Math.max(0, rootX + safe.left - viewX),
				Math.max(0, rootY + safe.top - viewY),
				Math.max(0, viewX + width - (rootX + rootWidth - safe.right)),
				Math.max(0, viewY + height - (rootY + rootHeight - safe.bottom)));
	}
}
