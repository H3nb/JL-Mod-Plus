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
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewGroupCompat;

import javax.microedition.lcdui.overlay.OverlayView;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.LegacyThemeColors;

/** Structural host for compatibility-sensitive guest Views and app-owned runtime chrome. */
public final class RuntimeHostView {
	public final FrameLayout root;
	public final LinearLayout virtualDisplay;
	public final ComposeView toolbar;
	public final FrameLayout displayableContainer;
	public final OverlayView overlay;
	/** Editor-only Activity-owned chrome. It never participates in virtual-key input/persistence. */
	public final AppCompatButton layoutEditDone;
	/** Small Activity-owned bubble. It is a normal View in :midlet, not a system overlay. */
	public final View memoryEditorBubble;
	public final View memoryEditorBubbleIcon;
	public final TextView memoryEditorBubbleProgress;
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
		FrameLayout bubble = new FrameLayout(context);
		bubble.setContentDescription(context.getString(R.string.memory_editor_bubble));
		bubble.setAlpha(0.90f);
		bubble.setElevation(dp(context, 6));
		bubble.setVisibility(View.GONE);
		GradientDrawable bubbleBackground = new GradientDrawable();
		bubbleBackground.setShape(GradientDrawable.OVAL);
		bubbleBackground.setColor(LegacyThemeColors.accent(context));
		bubble.setBackground(bubbleBackground);

		AppCompatImageView bubbleIcon = new AppCompatImageView(context);
		bubbleIcon.setImageResource(R.drawable.ic_runtime_memory);
		bubbleIcon.setImageTintList(ColorStateList.valueOf(
				resolveThemeColor(context, android.R.attr.textColorPrimaryInverse, Color.WHITE)));
		bubbleIcon.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
		bubble.addView(bubbleIcon, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		TextView bubbleProgress = new TextView(context);
		bubbleProgress.setGravity(Gravity.CENTER);
		bubbleProgress.setTextColor(resolveThemeColor(
				context, android.R.attr.textColorPrimaryInverse, Color.WHITE));
		bubbleProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
		bubbleProgress.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		bubbleProgress.setIncludeFontPadding(false);
		bubbleProgress.setVisibility(View.GONE);
		bubble.addView(bubbleProgress, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
				dp(context, 56), dp(context, 56), Gravity.END | Gravity.CENTER_VERTICAL);
		bubbleParams.setMarginEnd(dp(context, 12));
		root.addView(bubble, bubbleParams);
		memoryEditorBubble = bubble;
		memoryEditorBubbleIcon = bubbleIcon;
		memoryEditorBubbleProgress = bubbleProgress;

		notices = new ComposeView(context);
		FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		root.addView(notices, noticeParams);

		// Layout editing is the only state that needs this control, so keep it out of the permanent
		// toolbar and out of the virtual-key model. A plain Android Button is enough for one action.
		AppCompatButton done = new AppCompatButton(context);
		done.setText(context.getString(R.string.layout_edit_done));
		done.setAllCaps(false);
		done.setGravity(Gravity.CENTER);
		done.setTextColor(resolveThemeColor(
				context, android.R.attr.textColorPrimaryInverse, Color.WHITE));
		done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		done.setMinWidth(dp(context, 80));
		done.setMinHeight(dp(context, 48));
		done.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
		done.setElevation(dp(context, 8));
		done.setVisibility(View.GONE);
		done.setSupportBackgroundTintList(
				ColorStateList.valueOf(LegacyThemeColors.accent(context)));
		root.addView(done, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		layoutEditDone = done;
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
