/*
 * Copyright 2026 H3NB
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

package io.github.h3nb.jlmodplus.ui;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.ViewGroupCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Window policy shared by standard, non-floating application surfaces.
 *
 * <p>The game host has a separate geometry contract and must not use this
 * helper for its Canvas root.</p>
 */
public final class WindowInsetsPolicy {
	private WindowInsetsPolicy() {
	}

	/** Enables the same edge-to-edge window behavior on API 23+ as target 35+. */
	public static void enableEdgeToEdge(@NonNull Window window) {
		WindowCompat.enableEdgeToEdge(window);
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
				window,
				window.getDecorView()
		);
		boolean darkTheme = (window.getContext().getResources().getConfiguration().uiMode
				& Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		controller.setAppearanceLightStatusBars(!darkTheme);
		controller.setAppearanceLightNavigationBars(!darkTheme);
	}

	/**
	 * Keeps an edge-to-edge picker background behind the system bars while
	 * protecting only its top toolbar and bottom action row.
	 *
	 * <p>The content between those two chrome elements remains full height. The
	 * bottom row receives the IME inset as well, so action buttons stay reachable
	 * when a text-entry dialog is active.</p>
	 */
	public static void installChromeInsetsPadding(
			@NonNull View root,
			@NonNull View topBar,
			@NonNull View bottomBar
	) {
		if (root instanceof ViewGroup) {
			// Required before consuming insets so sibling dispatch remains correct
			// on API 29 and lower.
			ViewGroupCompat.installCompatInsetsDispatch((ViewGroup) root);
		}

		final int topInitialLeft = topBar.getPaddingLeft();
		final int topInitialTop = topBar.getPaddingTop();
		final int topInitialRight = topBar.getPaddingRight();
		final int topInitialBottom = topBar.getPaddingBottom();
		final int topInitialHeight = topBar.getLayoutParams().height;
		final int bottomInitialLeft = bottomBar.getPaddingLeft();
		final int bottomInitialTop = bottomBar.getPaddingTop();
		final int bottomInitialRight = bottomBar.getPaddingRight();
		final int bottomInitialBottom = bottomBar.getPaddingBottom();
		ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
			Insets insets = windowInsets.getInsets(
					WindowInsetsCompat.Type.systemBars()
							| WindowInsetsCompat.Type.displayCutout()
							| WindowInsetsCompat.Type.captionBar()
							| WindowInsetsCompat.Type.systemGestures()
							| WindowInsetsCompat.Type.mandatorySystemGestures()
							| WindowInsetsCompat.Type.tappableElement()
							| WindowInsetsCompat.Type.ime()
			);
			topBar.setPadding(
					topInitialLeft + insets.left,
					topInitialTop + insets.top,
					topInitialRight + insets.right,
					topInitialBottom
			);
			bottomBar.setPadding(
					bottomInitialLeft + insets.left,
					bottomInitialTop,
					bottomInitialRight + insets.right,
					bottomInitialBottom + insets.bottom
			);
			if (topInitialHeight >= 0) {
				ViewGroup.LayoutParams layoutParams = topBar.getLayoutParams();
				int desiredHeight = topInitialHeight + insets.top;
				if (layoutParams.height != desiredHeight) {
					layoutParams.height = desiredHeight;
					topBar.setLayoutParams(layoutParams);
				}
			}
			return WindowInsetsCompat.CONSUMED;
		});
		ViewCompat.requestApplyInsets(root);
	}
}
