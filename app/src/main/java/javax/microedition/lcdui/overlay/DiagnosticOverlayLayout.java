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

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import javax.microedition.lcdui.graphics.CanvasWrapper;

/**
 * Shared host layout for diagnostic pills drawn above the guest surface.
 *
 * <p>Coordinates returned by this class are pixels, matching {@link CanvasWrapper}. The row
 * position is calculated from the actual pill font metrics so FPS and emulation-speed diagnostics
 * do not overlap on devices with different density or font scale.</p>
 */
final class DiagnosticOverlayLayout {
	static final float PILL_SCALE = 0.68f;

	private static final float MARGIN_DP = 10f;
	private static final float ROW_GAP_DP = 6f;

	private DiagnosticOverlayLayout() {
	}

	static float left(View view) {
		return margin(view) + safeLeftInset(view);
	}

	static float rowTop(View view, CanvasWrapper canvas, int row) {
		int safeRow = Math.max(0, row);
		float density = density(view);
		return margin(view) + safeTopInset(view)
				+ safeRow * (canvas.getPillHeight(PILL_SCALE) + ROW_GAP_DP * density);
	}

	private static float margin(View view) {
		return MARGIN_DP * density(view);
	}

	private static float density(View view) {
		float density = view.getResources().getDisplayMetrics().density;
		return density > 0f ? density : 1f;
	}

	private static int safeLeftInset(View view) {
		return Math.max(0, safeInsets(view).left - contentOffsetX(view));
	}

	private static int safeTopInset(View view) {
		return Math.max(0, safeInsets(view).top - contentOffsetY(view));
	}

	private static int contentOffsetX(View view) {
		return view instanceof OverlayView ? ((OverlayView) view).getContentOffsetX() : 0;
	}

	private static int contentOffsetY(View view) {
		return view instanceof OverlayView ? ((OverlayView) view).getContentOffsetY() : 0;
	}

	private static Insets safeInsets(View view) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		if (insets == null) {
			return Insets.of(0, 0, 0, 0);
		}
		Insets cutout = insets.getInsetsIgnoringVisibility(
				WindowInsetsCompat.Type.displayCutout());
		Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
		return Insets.of(
				Math.max(cutout.left, systemBars.left),
				Math.max(cutout.top, systemBars.top),
				Math.max(cutout.right, systemBars.right),
				Math.max(cutout.bottom, systemBars.bottom));
	}
}
