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
import androidx.appcompat.widget.TooltipCompat;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
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
	public final LinearLayout layoutEditPanel;
	public final AppCompatImageView layoutEditShowControls;
	public final AppCompatImageView layoutEditGridSize;
	public final AppCompatImageView layoutEditGroupResize;
	public final AppCompatImageView layoutEditSnap;
	public final AppCompatButton layoutEditDone;
	private static final int EDITOR_PANEL_SURFACE = 0xFF232529;
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

		// Keep editor tools in a single activity-owned surface; the guest sees no extra keys.
		LinearLayout panel = new LinearLayout(context);
		panel.setOrientation(LinearLayout.VERTICAL);
		panel.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
		panel.setElevation(dp(context, 8));
		panel.setClipToOutline(true);
		panel.setVisibility(View.GONE);
		GradientDrawable panelBackground = new GradientDrawable();
		panelBackground.setCornerRadius(dp(context, 12));
		panelBackground.setColor(0xF2232529);
		panel.setBackground(panelBackground);

		LinearLayout tools = new LinearLayout(context);
		tools.setOrientation(LinearLayout.HORIZONTAL);
		AppCompatImageView showControls = editorTool(context, R.drawable.ic_visibility,
				R.string.runtime_virtual_controls_show_controls);
		AppCompatImageView gridSize = editorTool(context, R.drawable.ic_editor_grid,
				R.string.layout_editor_grid_size);
		AppCompatImageView groupResize = editorTool(context, R.drawable.ic_editor_group_resize,
				R.string.layout_editor_group_on);
		AppCompatImageView snap = editorTool(context, R.drawable.ic_editor_snap,
				R.string.layout_editor_snap_on);
		tools.addView(showControls, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
		tools.addView(gridSize, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
		tools.addView(groupResize, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
		tools.addView(snap, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
		panel.addView(tools);

		View divider = new View(context);
		divider.setBackgroundColor(0x2EFFFFFF);
		LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
		dividerParams.setMargins(dp(context, 8), 0, dp(context, 8), 0);
		panel.addView(divider, dividerParams);

		AppCompatButton done = new AppCompatButton(context);
		done.setText(context.getString(R.string.layout_edit_done));
		done.setAllCaps(false);
		done.setGravity(Gravity.CENTER);
		done.setTextColor(editorAccent(context));
		done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		done.setMinWidth(dp(context, 144));
		done.setMinHeight(dp(context, 48));
		done.setPadding(dp(context, 12), 0, dp(context, 12), 0);
		applyEditorPressFeedback(context, done, android.R.attr.selectableItemBackground);
		panel.addView(done, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)));
		root.addView(panel, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		layoutEditPanel = panel;
		layoutEditShowControls = showControls;
		layoutEditGridSize = gridSize;
		layoutEditGroupResize = groupResize;
		layoutEditSnap = snap;
		layoutEditDone = done;
		setLayoutEditSnapEnabled(true);
		setLayoutEditGroupEnabled(true);
	}

	private static AppCompatImageView editorTool(Context context, int icon, int label) {
		AppCompatImageView view = new AppCompatImageView(context);
		view.setImageResource(icon);
		view.setImageTintList(ColorStateList.valueOf(0xD9FFFFFF));
		applyEditorPressFeedback(context, view, android.R.attr.selectableItemBackgroundBorderless);
		view.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
		view.setContentDescription(context.getString(label));
		view.setClickable(true);
		view.setFocusable(true);
		TooltipCompat.setTooltipText(view, context.getString(label));
		return view;
	}


	/** Render the session snap state, including a shape change for non-color cues. */
	public void setLayoutEditSnapEnabled(boolean enabled) {
		int label = enabled ? R.string.layout_editor_snap_on : R.string.layout_editor_snap_off;
		layoutEditSnap.setImageResource(enabled
				? R.drawable.ic_editor_snap : R.drawable.ic_editor_snap_off);
		layoutEditSnap.setImageTintList(ColorStateList.valueOf(
				enabled ? layoutEditDone.getCurrentTextColor() : 0x99FFFFFF));
		layoutEditSnap.setContentDescription(layoutEditSnap.getContext().getString(label));
		TooltipCompat.setTooltipText(layoutEditSnap, layoutEditSnap.getContentDescription());
	}

	public void setLayoutEditGroupEnabled(boolean enabled) {
		int label = enabled ? R.string.layout_editor_group_on : R.string.layout_editor_group_off;
		layoutEditGroupResize.setImageTintList(ColorStateList.valueOf(
				enabled ? layoutEditDone.getCurrentTextColor() : 0x99FFFFFF));
		layoutEditGroupResize.setContentDescription(layoutEditGroupResize.getContext().getString(label));
		TooltipCompat.setTooltipText(layoutEditGroupResize, layoutEditGroupResize.getContentDescription());
	}

	private static int editorAccent(Context context) {
		int accent = LegacyThemeColors.accent(context) | Color.BLACK;
		return ColorUtils.calculateContrast(accent, EDITOR_PANEL_SURFACE) >= 4.5
				? accent : Color.WHITE;
	}

	private static void applyEditorPressFeedback(Context context, View view, int attribute) {
		TypedValue style = new TypedValue();
		if (context.getTheme().resolveAttribute(attribute, style, true) &&
				style.resourceId != 0) {
			view.setBackgroundResource(style.resourceId);
		}
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
