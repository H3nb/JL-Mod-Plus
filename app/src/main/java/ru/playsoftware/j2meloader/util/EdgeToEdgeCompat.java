/*
 *
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

package ru.playsoftware.j2meloader.util;

import android.app.Activity;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Enables the platform's enforced edge-to-edge contract without changing older baselines. */
public final class EdgeToEdgeCompat {
	private EdgeToEdgeCompat() {
	}

	public static void enableIfSupported(Activity activity) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			WindowCompat.enableEdgeToEdge(activity.getWindow());
		}
	}

	/**
	 * Enables edge-to-edge for the Compose-owned library host on every supported Android API.
	 * The library screen applies its own safe drawing insets, so the Activity content must not be
	 * padded as a conventional View host.
	 */
	public static void enableForComposeLibrary(Activity activity) {
		WindowCompat.enableEdgeToEdge(activity.getWindow());
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			activity.getWindow().setNavigationBarContrastEnforced(false);
		}
	}

	/**
	 * Keeps conventional host screens inside the unobscured area while their window remains
	 * edge-to-edge. AppCompat's decor ActionBar is a sibling of {@code android.R.id.content}; on
	 * Android 15+ the content frame otherwise fills the whole window and is drawn underneath both
	 * the ActionBar and system cutouts.
	 */
	public static void protectHostContent(Activity activity) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			return;
		}
		View content = activity.findViewById(android.R.id.content);
		if (content == null) {
			return;
		}
		View decor = activity.getWindow().getDecorView();
		View actionBar = activity.findViewById(androidx.appcompat.R.id.action_bar_container);
		int contentPaddingLeft = content.getPaddingLeft();
		int contentPaddingTop = content.getPaddingTop();
		int contentPaddingRight = content.getPaddingRight();
		int contentPaddingBottom = content.getPaddingBottom();
		int actionBarPaddingLeft = actionBar == null ? 0 : actionBar.getPaddingLeft();
		int actionBarPaddingTop = actionBar == null ? 0 : actionBar.getPaddingTop();
		int actionBarPaddingRight = actionBar == null ? 0 : actionBar.getPaddingRight();
		int actionBarPaddingBottom = actionBar == null ? 0 : actionBar.getPaddingBottom();

		ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
			Insets safe = windowInsets.getInsetsIgnoringVisibility(
					WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
			Bounds actionBarBounds = actionBar != null && actionBar.getVisibility() == View.VISIBLE
					? boundsInWindow(actionBar) : null;
			Insets required = calculateRequiredPadding(boundsInWindow(view), boundsInWindow(decor),
					actionBarBounds, safe, Math.max(safe.bottom, ime.bottom));
			setPaddingIfChanged(view,
					contentPaddingLeft + required.left,
					contentPaddingTop + required.top,
					contentPaddingRight + required.right,
					contentPaddingBottom + required.bottom);

			if (actionBar != null) {
				Bounds barBounds = boundsInWindow(actionBar);
				Bounds decorBounds = boundsInWindow(decor);
				int left = Math.max(0, decorBounds.left + safe.left - barBounds.left);
				int right = Math.max(0, barBounds.right - (decorBounds.right - safe.right));
				setPaddingIfChanged(actionBar,
						actionBarPaddingLeft + left,
						actionBarPaddingTop,
						actionBarPaddingRight + right,
						actionBarPaddingBottom);
			}
			return windowInsets;
		});

		if (actionBar != null) {
			actionBar.addOnLayoutChangeListener((view, left, top, right, bottom,
					oldLeft, oldTop, oldRight, oldBottom) -> {
				if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
					dispatchCurrentInsets(content);
				}
			});
		}
		ViewCompat.requestApplyInsets(content);
		content.post(() -> dispatchCurrentInsets(content));
	}

	private static void dispatchCurrentInsets(View content) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(content);
		if (insets != null) {
			ViewCompat.dispatchApplyWindowInsets(content, insets);
		}
	}

	static Insets calculateRequiredPadding(Bounds contentBounds, Bounds decorBounds,
			@Nullable Bounds actionBarBounds, Insets safe, int bottomInset) {
		int safeTop = decorBounds.top + safe.top;
		if (actionBarBounds != null) {
			safeTop = Math.max(safeTop, actionBarBounds.bottom);
		}
		return Insets.of(
				Math.max(0, decorBounds.left + safe.left - contentBounds.left),
				Math.max(0, safeTop - contentBounds.top),
				Math.max(0, contentBounds.right - (decorBounds.right - safe.right)),
				Math.max(0, contentBounds.bottom - (decorBounds.bottom - bottomInset)));
	}

	private static Bounds boundsInWindow(View view) {
		int[] location = new int[2];
		view.getLocationInWindow(location);
		return new Bounds(location[0], location[1],
				location[0] + view.getWidth(), location[1] + view.getHeight());
	}

	static final class Bounds {
		final int left;
		final int top;
		final int right;
		final int bottom;

		Bounds(int left, int top, int right, int bottom) {
			this.left = left;
			this.top = top;
			this.right = right;
			this.bottom = bottom;
		}
	}

	private static void setPaddingIfChanged(View view, int left, int top, int right, int bottom) {
		if (view.getPaddingLeft() != left || view.getPaddingTop() != top
				|| view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
			view.setPadding(left, top, right, bottom);
		}
	}
}
