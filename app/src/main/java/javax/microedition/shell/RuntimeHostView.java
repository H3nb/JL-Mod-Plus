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

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.compose.ui.platform.ComposeView;
import androidx.core.view.ViewGroupCompat;

import javax.microedition.lcdui.overlay.OverlayView;

import ru.playsoftware.j2meloader.R;

/** Structural host for compatibility-sensitive guest Views and app-owned runtime chrome. */
public final class RuntimeHostView {
	public final FrameLayout root;
	public final LinearLayout virtualDisplay;
	public final ComposeView toolbar;
	public final FrameLayout displayableContainer;
	public final OverlayView overlay;
	/**
	 * Compatibility-only handles retained for MicroActivity's lightweight Memory Editor proxy.
	 * They are intentionally plain, unattached Views: all Memory Editor UI now lives in the
	 * :memory_engine overlay service process.
	 */
	public final View memoryEditor;
	public final View memoryEditorBubble;
	public final ComposeView notices;

	public RuntimeHostView(Context context) {
		root = new FrameLayout(context);
		root.setId(R.id.midletFrame);
		ViewGroupCompat.installCompatInsetsDispatch(root);

		virtualDisplay = new LinearLayout(context);
		virtualDisplay.setId(R.id.virtual_display);
		virtualDisplay.setOrientation(LinearLayout.VERTICAL);
		root.addView(virtualDisplay, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		toolbar = new ComposeView(context);
		toolbar.setId(R.id.toolbar);
		toolbar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
		virtualDisplay.addView(toolbar, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		displayableContainer = new FrameLayout(context);
		displayableContainer.setId(R.id.displayable_container);
		virtualDisplay.addView(displayableContainer, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

		overlay = new OverlayView(context, null);
		overlay.setId(R.id.overlay);
		root.addView(overlay, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		// Keep constructor compatibility without allocating or attaching Memory Editor ComposeViews
		// in :midlet. These views are only used to obtain an application Context for the proxy.
		memoryEditor = new View(context);
		memoryEditorBubble = new View(context);

		notices = new ComposeView(context);
		FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		root.addView(notices, noticeParams);
	}

	public FrameLayout getRoot() {
		return root;
	}
}
