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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewGroupCompat;

import javax.microedition.lcdui.overlay.OverlayView;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.ui.LegacyThemeColors;

/** Structural host for compatibility-sensitive guest Views and app-owned runtime chrome. */
public final class RuntimeHostView {
	public final FrameLayout root;
	public final LinearLayout virtualDisplay;
	public final ComposeView toolbar;
	public final FrameLayout displayableContainer;
	public final OverlayView overlay;
	/** Small Activity-owned bubble. It is a normal View in :midlet, not a system overlay. */
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

		// The bubble deliberately stays a plain Android View. A second Compose tree would be wasteful
		// for one icon and would add avoidable allocations in the MIDlet process.
		AppCompatImageButton bubble = new AppCompatImageButton(context);
		bubble.setImageResource(R.drawable.ic_memory_editor_search);
		bubble.setContentDescription(context.getString(R.string.memory_editor_bubble));
		bubble.setImageTintList(ColorStateList.valueOf(
				resolveThemeColor(context, android.R.attr.textColorPrimaryInverse, Color.WHITE)));
		bubble.setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13));
		bubble.setAlpha(0.90f);
		bubble.setElevation(dp(context, 6));
		bubble.setVisibility(View.GONE);
		GradientDrawable bubbleBackground = new GradientDrawable();
		bubbleBackground.setShape(GradientDrawable.OVAL);
		bubbleBackground.setColor(LegacyThemeColors.accent(context));
		bubble.setBackground(bubbleBackground);
		FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
				dp(context, 52), dp(context, 52), Gravity.END | Gravity.CENTER_VERTICAL);
		bubbleParams.setMarginEnd(dp(context, 12));
		root.addView(bubble, bubbleParams);
		memoryEditorBubble = bubble;

		notices = new ComposeView(context);
		FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		root.addView(notices, noticeParams);
	}

	private static int dp(Context context, int value) {
		return Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
	}

	private static int resolveThemeColor(Context context, int attribute, int fallback) {
		TypedValue value = new TypedValue();
		if (!context.getTheme().resolveAttribute(attribute, value, true)) {
			return fallback;
		}
		if (value.resourceId != 0) {
			try {
				return ContextCompat.getColor(context, value.resourceId);
			} catch (RuntimeException ignored) {
				return fallback;
			}
		}
		return value.data;
	}

	public FrameLayout getRoot() {
		return root;
	}
}
