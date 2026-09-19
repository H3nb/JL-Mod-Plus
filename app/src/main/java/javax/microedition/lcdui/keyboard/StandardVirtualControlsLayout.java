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
package javax.microedition.lcdui.keyboard;

/**
 * Pure geometry resolver for the built-in D-pad/analog standard templates.
 *
 * Landscape keeps shoulder buttons near the upper corners, movement in the lower-left thumb zone,
 * and the F, *, and 0 action cluster in the lower-right. Portrait uses the same zones in a
 * vertically stacked arrangement so the shoulders stay clear of the movement and action controls.
 */
final class StandardVirtualControlsLayout {
	private static final float BOTTOM_COLUMN_OFFSET_KEYS = 0.85f;
	private static final float PORTRAIT_ROW_OFFSET_KEYS = 1.00f;
	private static final float EDGE_MARGIN_KEYS = 0.16f;
	private static final float PORTRAIT_MOVEMENT_X_FRACTION = 0.27f;
	private static final float PORTRAIT_MOVEMENT_Y_FRACTION = 0.70f;
	private static final float PORTRAIT_ACTION_X_FRACTION = 0.74f;
	private static final float PORTRAIT_ACTION_Y_FRACTION = 0.56f;
	private static final float PORTRAIT_BOTTOM_Y_FRACTION = 0.78f;
	private static final float PORTRAIT_SHOULDER_X_FRACTION = 0.19f;
	private static final float PORTRAIT_SHOULDER_Y_FRACTION = 0.12f;
	private static final float LANDSCAPE_MOVEMENT_X_FRACTION = 0.26f;
	private static final float LANDSCAPE_MOVEMENT_Y_FRACTION = 0.60f;
	private static final float LANDSCAPE_ACTION_X_FRACTION = 0.75f;
	private static final float LANDSCAPE_ACTION_Y_FRACTION = 0.54f;
	private static final float LANDSCAPE_BOTTOM_Y_FRACTION = 0.74f;
	private static final float LANDSCAPE_SHOULDER_X_FRACTION = 0.16f;
	private static final float LANDSCAPE_SHOULDER_Y_FRACTION = 0.15f;
	private static final float SHOULDER_WIDTH_KEYS = 2.05f;
	private static final float SHOULDER_HEIGHT_KEYS = 0.90f;
	private static final float BOTTOM_DECK_CENTER_FRACTION = 0.58f;

	final float keySize;
	final float movementCenterX;
	final float movementCenterY;
	final float shoulderLeftX;
	final float shoulderRightX;
	final float shoulderCenterY;
	final float shoulderWidth;
	final float shoulderHeight;
	final float bottomLeftX;
	final float bottomRightX;
	final float actionCenterX;
	final float actionCenterY;
	final float bottomRowY;
	final boolean movementUsesLeftGutter;
	final boolean actionsUseRightGutter;

	private StandardVirtualControlsLayout(
			float keySize,
			float movementCenterX,
			float movementCenterY,
			float shoulderLeftX,
			float shoulderRightX,
			float shoulderCenterY,
			float shoulderWidth,
			float shoulderHeight,
			float bottomLeftX,
			float bottomRightX,
			float actionCenterX,
			float actionCenterY,
			float bottomRowY,
			boolean movementUsesLeftGutter,
			boolean actionsUseRightGutter) {
		this.keySize = keySize;
		this.movementCenterX = movementCenterX;
		this.movementCenterY = movementCenterY;
		this.shoulderLeftX = shoulderLeftX;
		this.shoulderRightX = shoulderRightX;
		this.shoulderCenterY = shoulderCenterY;
		this.shoulderWidth = shoulderWidth;
		this.shoulderHeight = shoulderHeight;
		this.bottomLeftX = bottomLeftX;
		this.bottomRightX = bottomRightX;
		this.actionCenterX = actionCenterX;
		this.actionCenterY = actionCenterY;
		this.bottomRowY = bottomRowY;
		this.movementUsesLeftGutter = movementUsesLeftGutter;
		this.actionsUseRightGutter = actionsUseRightGutter;
	}

	static StandardVirtualControlsLayout resolve(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float guestLeft,
			float guestTop,
			float guestRight,
			float guestBottom,
			float movementRadius) {
		float width = Math.max(1.0f, screenRight - screenLeft);
		float height = Math.max(1.0f, screenBottom - screenTop);
		boolean landscape = width > height;
		float keySize = landscape
				? Math.min(width / 12.0f, height / 6.0f)
				: Math.min(width / 6.0f, height / 12.0f);
		float halfKey = keySize * 0.5f;
		float edgeMargin = keySize * EDGE_MARGIN_KEYS;
		float bottomColumnOffset = keySize * BOTTOM_COLUMN_OFFSET_KEYS;
		float portraitRowOffset = keySize * PORTRAIT_ROW_OFFSET_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;

		float safeGuestLeft = clamp(guestLeft, screenLeft, screenRight);
		float safeGuestRight = clamp(guestRight, safeGuestLeft, screenRight);
		float safeGuestBottom = clamp(guestBottom, screenTop, screenBottom);
		float leftGutter = Math.max(0.0f, safeGuestLeft - screenLeft);
		float rightGutter = Math.max(0.0f, screenRight - safeGuestRight);
		float bottomDeck = Math.max(0.0f, screenBottom - safeGuestBottom);
		float movementFit = movementRadius * 2.0f + edgeMargin * 2.0f;
		float actionHalfWidth = bottomColumnOffset + halfKey;
		float actionFit = actionHalfWidth * 2.0f + edgeMargin * 2.0f;
		boolean movementUsesLeftGutter = landscape && leftGutter >= movementFit;
		boolean actionsUseRightGutter = landscape && rightGutter >= actionFit;

		float movementCenterX;
		float movementCenterY;
		float actionCenterX;
		float actionCenterY;
		float bottomRowY;
		float shoulderLeftX;
		float shoulderRightX;
		float shoulderCenterY;

		if (landscape) {
			float preferredMovementX = screenLeft + width * LANDSCAPE_MOVEMENT_X_FRACTION;
			float movementMinX = screenLeft + movementRadius + edgeMargin;
			float movementMaxX = screenRight - movementRadius - edgeMargin;
			if (movementUsesLeftGutter) {
				movementMaxX = Math.min(movementMaxX,
						safeGuestLeft - movementRadius - edgeMargin);
			}
			movementCenterX = clamp(preferredMovementX, movementMinX, movementMaxX);
			movementCenterY = clamp(
					screenTop + height * LANDSCAPE_MOVEMENT_Y_FRACTION,
					screenTop + movementRadius + edgeMargin,
					screenBottom - movementRadius - edgeMargin);

			actionCenterX = clamp(
					screenLeft + width * LANDSCAPE_ACTION_X_FRACTION,
					screenLeft + actionHalfWidth + edgeMargin,
					screenRight - actionHalfWidth - edgeMargin);
			actionCenterY = clamp(
					screenTop + height * LANDSCAPE_ACTION_Y_FRACTION,
					screenTop + halfKey + edgeMargin,
					screenBottom - halfKey - edgeMargin);
			bottomRowY = clamp(
					screenTop + height * LANDSCAPE_BOTTOM_Y_FRACTION,
					actionCenterY + halfKey,
					screenBottom - halfKey - edgeMargin);

			float shoulderHalfWidth = shoulderWidth * 0.5f;
			float shoulderHalfHeight = shoulderHeight * 0.5f;
			shoulderLeftX = clamp(
					screenLeft + width * LANDSCAPE_SHOULDER_X_FRACTION,
					screenLeft + shoulderHalfWidth + edgeMargin,
					screenRight - shoulderHalfWidth - edgeMargin);
			shoulderRightX = clamp(
					screenRight - width * LANDSCAPE_SHOULDER_X_FRACTION,
					screenLeft + shoulderHalfWidth + edgeMargin,
					screenRight - shoulderHalfWidth - edgeMargin);
			shoulderCenterY = clamp(
					screenTop + height * LANDSCAPE_SHOULDER_Y_FRACTION,
					screenTop + shoulderHalfHeight + edgeMargin,
					screenBottom - shoulderHalfHeight - edgeMargin);
		} else {
			float minCenterY = screenTop + halfKey + edgeMargin;
			float maxCenterY = screenBottom - portraitRowOffset - halfKey - edgeMargin;
			float actionClusterHeight = keySize + portraitRowOffset + edgeMargin;
			float preferredActionY = bottomDeck >= actionClusterHeight
					? safeGuestBottom + bottomDeck * BOTTOM_DECK_CENTER_FRACTION
					: screenTop + height * PORTRAIT_ACTION_Y_FRACTION;
			actionCenterX = clamp(
					screenLeft + width * PORTRAIT_ACTION_X_FRACTION,
					screenLeft + actionHalfWidth + edgeMargin,
					screenRight - actionHalfWidth - edgeMargin);
			actionCenterY = clamp(
					preferredActionY,
					minCenterY,
					maxCenterY);
			bottomRowY = clamp(
					screenTop + height * PORTRAIT_BOTTOM_Y_FRACTION,
					actionCenterY + portraitRowOffset,
					screenBottom - halfKey - edgeMargin);
			shoulderCenterY = clamp(
					screenTop + height * PORTRAIT_SHOULDER_Y_FRACTION,
					screenTop + shoulderHeight * 0.5f + edgeMargin,
					screenBottom - shoulderHeight * 0.5f - edgeMargin);

			movementCenterX = clamp(
					screenLeft + width * PORTRAIT_MOVEMENT_X_FRACTION,
					screenLeft + movementRadius + edgeMargin,
					screenRight - movementRadius - edgeMargin);
			movementCenterY = clamp(
					screenTop + height * PORTRAIT_MOVEMENT_Y_FRACTION,
					screenTop + movementRadius + edgeMargin,
					screenBottom - movementRadius - edgeMargin);

			float shoulderHalfWidth = shoulderWidth * 0.5f;
			shoulderLeftX = clamp(
					screenLeft + width * PORTRAIT_SHOULDER_X_FRACTION,
					screenLeft + shoulderHalfWidth + edgeMargin,
					screenRight - shoulderHalfWidth - edgeMargin);
			shoulderRightX = clamp(
					screenRight - width * PORTRAIT_SHOULDER_X_FRACTION,
					screenLeft + shoulderHalfWidth + edgeMargin,
					screenRight - shoulderHalfWidth - edgeMargin);
		}

		return new StandardVirtualControlsLayout(
				keySize,
				movementCenterX,
				movementCenterY,
				shoulderLeftX,
				shoulderRightX,
				shoulderCenterY,
				shoulderWidth,
				shoulderHeight,
				actionCenterX - bottomColumnOffset,
				actionCenterX + bottomColumnOffset,
				actionCenterX,
				actionCenterY,
				bottomRowY,
				movementUsesLeftGutter,
				actionsUseRightGutter);
	}

	private static float clamp(float value, float min, float max) {
		if (min > max) return (min + max) * 0.5f;
		return Math.max(min, Math.min(max, value));
	}
}
