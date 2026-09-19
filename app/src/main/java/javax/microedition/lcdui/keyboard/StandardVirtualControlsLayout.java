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
 * The resolver prefers real free space around the MIDlet viewport instead of fixed percentages of
 * the host display. Wide layouts therefore use the left/right gutters as ergonomic thumb zones,
 * while tall layouts use the free deck below the MIDlet when it is large enough. If neither region
 * is available, the same model falls back to a compact overlay near the lower corners.
 */
final class StandardVirtualControlsLayout {
	private static final float COLUMN_OFFSET_KEYS = 1.00f;
	private static final float ROW_OFFSET_KEYS = 1.00f;
	private static final float EDGE_MARGIN_KEYS = 0.16f;
	private static final float PORTRAIT_MOVEMENT_X_FRACTION = 0.27f;
	private static final float SIDE_ACTION_CENTER_Y_FRACTION = 0.66f;
	private static final float SIDE_MOVEMENT_X_OFFSET_KEYS = 0.07f;
	private static final float SIDE_MOVEMENT_Y_OFFSET_KEYS = 0.15f;
	private static final float BOTTOM_DECK_CENTER_FRACTION = 0.58f;

	final float keySize;
	final float movementCenterX;
	final float movementCenterY;
	final float leftColumnX;
	final float rightColumnX;
	final float actionCenterX;
	final float topRowY;
	final float actionCenterY;
	final float bottomRowY;
	final boolean movementUsesLeftGutter;
	final boolean actionsUseRightGutter;

	private StandardVirtualControlsLayout(
			float keySize,
			float movementCenterX,
			float movementCenterY,
			float leftColumnX,
			float rightColumnX,
			float actionCenterX,
			float topRowY,
			float actionCenterY,
			float bottomRowY,
			boolean movementUsesLeftGutter,
			boolean actionsUseRightGutter) {
		this.keySize = keySize;
		this.movementCenterX = movementCenterX;
		this.movementCenterY = movementCenterY;
		this.leftColumnX = leftColumnX;
		this.rightColumnX = rightColumnX;
		this.actionCenterX = actionCenterX;
		this.topRowY = topRowY;
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
		float keySize = width > height
				? Math.min(width / 12.0f, height / 6.0f)
				: Math.min(width / 6.0f, height / 12.0f);
		float halfKey = keySize * 0.5f;
		float edgeMargin = keySize * EDGE_MARGIN_KEYS;
		float columnOffset = keySize * COLUMN_OFFSET_KEYS;
		float rowOffset = keySize * ROW_OFFSET_KEYS;

		float safeGuestLeft = clamp(guestLeft, screenLeft, screenRight);
		float safeGuestTop = clamp(guestTop, screenTop, screenBottom);
		float safeGuestRight = clamp(guestRight, safeGuestLeft, screenRight);
		float safeGuestBottom = clamp(guestBottom, safeGuestTop, screenBottom);
		float leftGutter = Math.max(0.0f, safeGuestLeft - screenLeft);
		float rightGutter = Math.max(0.0f, screenRight - safeGuestRight);
		float bottomDeck = Math.max(0.0f, screenBottom - safeGuestBottom);
		boolean landscape = width > height;

		float movementFit = movementRadius * 2.0f + edgeMargin * 2.0f;
		float clusterHalfWidth = columnOffset + halfKey;
		float actionFit = clusterHalfWidth * 2.0f + edgeMargin * 2.0f;
		boolean movementUsesLeftGutter = landscape && leftGutter >= movementFit;
		boolean actionsUseRightGutter = landscape && rightGutter >= actionFit;
		boolean sideErgonomics = movementUsesLeftGutter || actionsUseRightGutter;

		float minCenterY = screenTop + rowOffset + halfKey + edgeMargin;
		float maxCenterY = screenBottom - rowOffset - halfKey - edgeMargin;
		float actionCenterY;
		float fullClusterHeight = rowOffset * 2.0f + keySize + edgeMargin * 2.0f;
		if (sideErgonomics) {
			actionCenterY = screenTop + height * SIDE_ACTION_CENTER_Y_FRACTION;
		} else if (bottomDeck >= fullClusterHeight) {
			actionCenterY = safeGuestBottom + bottomDeck * BOTTOM_DECK_CENTER_FRACTION;
		} else {
			actionCenterY = screenBottom - rowOffset - halfKey - edgeMargin;
		}
		actionCenterY = clamp(actionCenterY, minCenterY, maxCenterY);
		float movementCenterY = actionCenterY;
		if (sideErgonomics) {
			movementCenterY = clamp(
					actionCenterY - keySize * SIDE_MOVEMENT_Y_OFFSET_KEYS,
					screenTop + movementRadius + edgeMargin,
					screenBottom - movementRadius - edgeMargin);
		}

		float movementCenterX;
		if (movementUsesLeftGutter) {
			float min = screenLeft + movementRadius + edgeMargin;
			float max = safeGuestLeft - movementRadius - edgeMargin;
			float preferred = (screenLeft + safeGuestLeft) * 0.5f
					- keySize * SIDE_MOVEMENT_X_OFFSET_KEYS;
			movementCenterX = clamp(preferred, min, max);
		} else {
			float preferred = screenLeft + width * PORTRAIT_MOVEMENT_X_FRACTION;
			movementCenterX = clamp(
					preferred,
					screenLeft + movementRadius + edgeMargin,
					screenRight - movementRadius - edgeMargin);
		}

		float actionCenterX;
		if (actionsUseRightGutter) {
			float min = safeGuestRight + clusterHalfWidth + edgeMargin;
			float max = screenRight - clusterHalfWidth - edgeMargin;
			actionCenterX = clamp((safeGuestRight + screenRight) * 0.5f, min, max);
		} else {
			actionCenterX = screenRight - clusterHalfWidth - edgeMargin;
			actionCenterX = clamp(
					actionCenterX,
					screenLeft + clusterHalfWidth + edgeMargin,
					screenRight - clusterHalfWidth - edgeMargin);
		}

		return new StandardVirtualControlsLayout(
				keySize,
				movementCenterX,
				movementCenterY,
				actionCenterX - columnOffset,
				actionCenterX + columnOffset,
				actionCenterX,
				actionCenterY - rowOffset,
				actionCenterY,
				actionCenterY + rowOffset,
				movementUsesLeftGutter,
				actionsUseRightGutter);
	}

	private static float clamp(float value, float min, float max) {
		if (min > max) return (min + max) * 0.5f;
		return Math.max(min, Math.min(max, value));
	}
}
