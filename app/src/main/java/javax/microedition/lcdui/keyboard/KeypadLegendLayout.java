/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

/** Pure sizing policy for adaptive phone-keypad legends. */
final class KeypadLegendLayout {
	enum Mode {
		HORIZONTAL,
		STACKED,
		PRIMARY_ONLY
	}

	private static final float MAX_PRIMARY_SCALE = 1.0f;
	private static final float SECONDARY_TARGET_RATIO = 0.50f;
	private static final float MIN_SECONDARY_SCALE = 0.34f;
	private static final float COMPARABLE_SCALE_DELTA = 0.04f;
	private static final float CONTENT_PADDING_RATIO = 0.08f;
	private static final float GAP_PRIMARY_HEIGHT_RATIO = 0.16f;
	private static final int MIN_VISIBLE_PHONE_DIGITS = 7;

	private KeypadLegendLayout() {
	}

	static boolean hasNumericContext(int visibleDigitsOneThroughNine) {
		return visibleDigitsOneThroughNine >= MIN_VISIBLE_PHONE_DIGITS;
	}

	static float fitPrimaryScale(
			float width,
			float height,
			float primaryWidth,
			float primaryHeight) {
		if (!(width > 0.0f) || !(height > 0.0f) ||
				!(primaryWidth > 0.0f) || !(primaryHeight > 0.0f)) {
			return MAX_PRIMARY_SCALE;
		}
		float padding = Math.min(width, height) * CONTENT_PADDING_RATIO;
		float availableWidth = Math.max(0.0f, width - padding * 2.0f);
		float availableHeight = Math.max(0.0f, height - padding * 2.0f);
		float scale = Math.min(
				MAX_PRIMARY_SCALE,
				Math.min(availableWidth / primaryWidth, availableHeight / primaryHeight));
		return Math.max(0.001f, scale);
	}

	static Plan resolve(
			float width,
			float height,
			float primaryWidth,
			float primaryHeight,
			float secondaryWidth,
			float secondaryHeight,
			float limitingSecondaryWidth) {
		float primaryScale = fitPrimaryScale(width, height, primaryWidth, primaryHeight);
		if (!(secondaryWidth > 0.0f) || !(secondaryHeight > 0.0f) ||
				!(limitingSecondaryWidth > 0.0f)) {
			return Plan.primaryOnly(primaryScale);
		}

		float padding = Math.min(width, height) * CONTENT_PADDING_RATIO;
		float availableWidth = Math.max(0.0f, width - padding * 2.0f);
		float availableHeight = Math.max(0.0f, height - padding * 2.0f);
		float scaledPrimaryWidth = primaryWidth * primaryScale;
		float scaledPrimaryHeight = primaryHeight * primaryScale;
		float gap = scaledPrimaryHeight * GAP_PRIMARY_HEIGHT_RATIO;
		float secondaryCap = SECONDARY_TARGET_RATIO * primaryScale;
		float fitWidth = Math.max(secondaryWidth, limitingSecondaryWidth);

		float horizontalScale = Math.min(
				secondaryCap,
				Math.min(
						(availableWidth - scaledPrimaryWidth - gap) / fitWidth,
						availableHeight / secondaryHeight));
		float stackedScale = Math.min(
				secondaryCap,
				Math.min(
						availableWidth / fitWidth,
						(availableHeight - scaledPrimaryHeight - gap) / secondaryHeight));
		horizontalScale = Math.max(0.0f, horizontalScale);
		stackedScale = Math.max(0.0f, stackedScale);

		boolean horizontalReadable = horizontalScale >= MIN_SECONDARY_SCALE;
		boolean stackedReadable = stackedScale >= MIN_SECONDARY_SCALE;
		if (!horizontalReadable && !stackedReadable) {
			return Plan.primaryOnly(primaryScale);
		}
		if (stackedReadable &&
				(!horizontalReadable || stackedScale > horizontalScale + COMPARABLE_SCALE_DELTA)) {
			return new Plan(Mode.STACKED, primaryScale, stackedScale, gap);
		}
		return new Plan(Mode.HORIZONTAL, primaryScale, horizontalScale, gap);
	}

	static final class Plan {
		private final Mode mode;
		private final float primaryScale;
		private final float secondaryScale;
		private final float gap;

		private Plan(Mode mode, float primaryScale, float secondaryScale, float gap) {
			this.mode = mode;
			this.primaryScale = primaryScale;
			this.secondaryScale = secondaryScale;
			this.gap = gap;
		}

		static Plan primaryOnly(float primaryScale) {
			return new Plan(Mode.PRIMARY_ONLY, primaryScale, 0.0f, 0.0f);
		}

		Mode mode() {
			return mode;
		}

		float primaryScale() {
			return primaryScale;
		}

		float secondaryScale() {
			return secondaryScale;
		}

		float gap() {
			return gap;
		}
	}
}
