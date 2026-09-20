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
 * Pure geometry for the built-in D-pad/analog templates.
 *
 * The standard templates first use free space outside the MIDlet viewport: a tall layout prefers
 * the deck below the guest, while a wide layout uses matching side gutters. Only tight/full-screen
 * layouts fall back to an overlay. D-pad Standard and Analog Standard share this geometry.
 */
final class StandardVirtualControlsLayout {
	enum Placement {
		SIDE_GUTTERS,
		BOTTOM_DECK,
		COMPACT_OVERLAY
	}

	private static final float KEY_SIZE_SHORT_SIDE = 0.142f;
	private static final float MOVEMENT_RADIUS_KEYS = 1.42f;
	private static final float SHOULDER_WIDTH_KEYS = 1.64f;
	private static final float SHOULDER_HEIGHT_KEYS = 0.68f;
	private static final float FIRE_SIZE_KEYS = 1.16f;
	private static final float OUTER_MARGIN_KEYS = 0.28f;

	private static final float SIDE_MIN_GUTTER_WIDTH_KEYS = 3.40f;
	private static final float SIDE_MIN_HEIGHT_KEYS = 5.83f;
	private static final float SIDE_OUTWARD_BIAS_KEYS = 0.15f;
	private static final float SIDE_SHOULDER_Y_KEYS = 1.45f;
	private static final float SIDE_CLUSTER_Y_KEYS = 4.13f;
	private static final float SIDE_ACTION_HALF_SPAN_KEYS = 1.08f;
	private static final float SIDE_ACTION_FIRE_OFFSET_KEYS = 0.60f;
	private static final float SIDE_ACTION_BOTTOM_OFFSET_KEYS = 0.48f;

	private static final float BOTTOM_MIN_WIDTH_KEYS = 6.30f;
	private static final float BOTTOM_MIN_HEIGHT_KEYS = 4.96f;
	private static final float BOTTOM_CLUSTER_FRACTION = 0.55f;
	private static final float BOTTOM_MOVEMENT_EDGE_BIAS_KEYS = 0.06f;
	private static final float BOTTOM_ACTION_HALF_SPAN_KEYS = 0.90f;
	private static final float BOTTOM_ACTION_FIRE_OFFSET_KEYS = 0.48f;
	private static final float BOTTOM_ACTION_BOTTOM_OFFSET_KEYS = 0.58f;
	private static final float BOTTOM_SHOULDER_GAP_KEYS = 0.35f;

	private static final float COMPACT_WIDTH_KEYS = 6.40f;
	private static final float COMPACT_HEIGHT_KEYS = 5.90f;

	final Placement placement;
	final float keySize;
	final float movementRadius;
	final float movementCenterX;
	final float movementCenterY;
	final float shoulderLeftX;
	final float shoulderRightX;
	final float shoulderCenterY;
	final float shoulderWidth;
	final float shoulderHeight;
	final float fireSize;
	final float bottomLeftX;
	final float bottomRightX;
	final float actionCenterX;
	final float actionCenterY;
	final float bottomRowY;

	private StandardVirtualControlsLayout(
			Placement placement,
			float keySize,
			float movementRadius,
			float movementCenterX,
			float movementCenterY,
			float shoulderLeftX,
			float shoulderRightX,
			float shoulderCenterY,
			float shoulderWidth,
			float shoulderHeight,
			float fireSize,
			float bottomLeftX,
			float bottomRightX,
			float actionCenterX,
			float actionCenterY,
			float bottomRowY) {
		this.placement = placement;
		this.keySize = keySize;
		this.movementRadius = movementRadius;
		this.movementCenterX = movementCenterX;
		this.movementCenterY = movementCenterY;
		this.shoulderLeftX = shoulderLeftX;
		this.shoulderRightX = shoulderRightX;
		this.shoulderCenterY = shoulderCenterY;
		this.shoulderWidth = shoulderWidth;
		this.shoulderHeight = shoulderHeight;
		this.fireSize = fireSize;
		this.bottomLeftX = bottomLeftX;
		this.bottomRightX = bottomRightX;
		this.actionCenterX = actionCenterX;
		this.actionCenterY = actionCenterY;
		this.bottomRowY = bottomRowY;
	}

	static StandardVirtualControlsLayout resolve(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float guestLeft,
			float guestTop,
			float guestRight,
			float guestBottom) {
		float width = Math.max(1.0f, screenRight - screenLeft);
		float height = Math.max(1.0f, screenBottom - screenTop);
		float shortest = Math.min(width, height);
		float keySize = Math.max(1.0f, shortest * KEY_SIZE_SHORT_SIDE);

		boolean guestValid = guestRight > guestLeft && guestBottom > guestTop;
		if (!guestValid) {
			guestLeft = screenLeft;
			guestTop = screenTop;
			guestRight = screenRight;
			guestBottom = screenBottom;
		}

		guestLeft = clamp(guestLeft, screenLeft, screenRight);
		guestTop = clamp(guestTop, screenTop, screenBottom);
		guestRight = clamp(guestRight, screenLeft, screenRight);
		guestBottom = clamp(guestBottom, screenTop, screenBottom);

		float leftGutter = Math.max(0.0f, guestLeft - screenLeft);
		float rightGutter = Math.max(0.0f, screenRight - guestRight);
		float bottomDeck = Math.max(0.0f, screenBottom - guestBottom);

		boolean sideFits =
				leftGutter >= SIDE_MIN_GUTTER_WIDTH_KEYS * keySize &&
				rightGutter >= SIDE_MIN_GUTTER_WIDTH_KEYS * keySize &&
				height >= SIDE_MIN_HEIGHT_KEYS * keySize;
		boolean bottomFits =
				bottomDeck >= BOTTOM_MIN_HEIGHT_KEYS * keySize &&
				width >= BOTTOM_MIN_WIDTH_KEYS * keySize;

		// Tall windows usually have the most useful free space below the MIDlet. Wide windows
		// prefer the two side gutters so the game remains unobstructed in the center.
		if (height >= width && bottomFits) {
			return resolveBottomDeck(
					screenLeft, screenTop, screenRight, screenBottom, guestBottom, keySize);
		}
		if (sideFits) {
			return resolveSideGutters(
					screenLeft, screenTop, screenRight, screenBottom,
					guestLeft, guestRight, keySize);
		}
		if (bottomFits) {
			return resolveBottomDeck(
					screenLeft, screenTop, screenRight, screenBottom, guestBottom, keySize);
		}
		return resolveCompactOverlay(
				screenLeft, screenTop, screenRight, screenBottom, keySize);
	}

	private static StandardVirtualControlsLayout resolveSideGutters(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float guestLeft,
			float guestRight,
			float keySize) {
		float movementRadius = keySize * MOVEMENT_RADIUS_KEYS;
		float margin = keySize * OUTER_MARGIN_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;
		float fireSize = keySize * FIRE_SIZE_KEYS;

		float leftCenter = (screenLeft + guestLeft) * 0.5f -
				keySize * SIDE_OUTWARD_BIAS_KEYS;
		float rightCenter = (guestRight + screenRight) * 0.5f +
				keySize * SIDE_OUTWARD_BIAS_KEYS;
		leftCenter = clamp(
				leftCenter,
				screenLeft + movementRadius + margin,
				guestLeft - movementRadius - margin);
		rightCenter = clamp(
				rightCenter,
				guestRight + movementRadius + margin,
				screenRight - movementRadius - margin);

		float shoulderCenterY = clamp(
				screenTop + keySize * SIDE_SHOULDER_Y_KEYS,
				screenTop + margin + shoulderHeight * 0.5f,
				screenBottom - margin - shoulderHeight * 0.5f);
		float clusterCenterY = clamp(
				screenTop + keySize * SIDE_CLUSTER_Y_KEYS,
				screenTop + margin + movementRadius,
				screenBottom - margin - movementRadius);

		float actionCenterY =
				clusterCenterY - keySize * SIDE_ACTION_FIRE_OFFSET_KEYS;
		float bottomRowY =
				clusterCenterY + keySize * SIDE_ACTION_BOTTOM_OFFSET_KEYS;
		float actionHalfSpan = keySize * SIDE_ACTION_HALF_SPAN_KEYS;

		return new StandardVirtualControlsLayout(
				Placement.SIDE_GUTTERS,
				keySize,
				movementRadius,
				leftCenter,
				clusterCenterY,
				leftCenter,
				rightCenter,
				shoulderCenterY,
				shoulderWidth,
				shoulderHeight,
				fireSize,
				rightCenter - actionHalfSpan,
				rightCenter + actionHalfSpan,
				rightCenter,
				actionCenterY,
				bottomRowY);
	}

	private static StandardVirtualControlsLayout resolveBottomDeck(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float guestBottom,
			float keySize) {
		float movementRadius = keySize * MOVEMENT_RADIUS_KEYS;
		float margin = keySize * OUTER_MARGIN_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;
		float fireSize = keySize * FIRE_SIZE_KEYS;
		float deckTop = clamp(guestBottom, screenTop, screenBottom);
		float deckHeight = Math.max(1.0f, screenBottom - deckTop);

		float shoulderLeftX = screenLeft + margin + shoulderWidth * 0.5f;
		float shoulderRightX = screenRight - margin - shoulderWidth * 0.5f;
		float shoulderCenterY = deckTop + margin + shoulderHeight * 0.5f;

		float movementCenterX =
				screenLeft + margin + movementRadius +
				keySize * BOTTOM_MOVEMENT_EDGE_BIAS_KEYS;
		float actionHalfSpan = keySize * BOTTOM_ACTION_HALF_SPAN_KEYS;
		float actionCenterX =
				screenRight - margin - actionHalfSpan - keySize * 0.5f;

		float minimumClusterY =
				shoulderCenterY + shoulderHeight * 0.5f +
				keySize * BOTTOM_SHOULDER_GAP_KEYS + movementRadius;
		float maximumClusterY = screenBottom - margin - movementRadius;
		float clusterCenterY = clamp(
				deckTop + deckHeight * BOTTOM_CLUSTER_FRACTION,
				minimumClusterY,
				maximumClusterY);

		float actionCenterY =
				clusterCenterY - keySize * BOTTOM_ACTION_FIRE_OFFSET_KEYS;
		float bottomRowY =
				clusterCenterY + keySize * BOTTOM_ACTION_BOTTOM_OFFSET_KEYS;

		return new StandardVirtualControlsLayout(
				Placement.BOTTOM_DECK,
				keySize,
				movementRadius,
				movementCenterX,
				clusterCenterY,
				shoulderLeftX,
				shoulderRightX,
				shoulderCenterY,
				shoulderWidth,
				shoulderHeight,
				fireSize,
				actionCenterX - actionHalfSpan,
				actionCenterX + actionHalfSpan,
				actionCenterX,
				actionCenterY,
				bottomRowY);
	}

	private static StandardVirtualControlsLayout resolveCompactOverlay(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float nominalKeySize) {
		float width = Math.max(1.0f, screenRight - screenLeft);
		float height = Math.max(1.0f, screenBottom - screenTop);
		float keySize = Math.max(
				1.0f,
				Math.min(
						nominalKeySize,
						Math.min(width / COMPACT_WIDTH_KEYS, height / COMPACT_HEIGHT_KEYS)));
		float movementRadius = keySize * MOVEMENT_RADIUS_KEYS;
		float margin = keySize * OUTER_MARGIN_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;
		float fireSize = keySize * FIRE_SIZE_KEYS;

		float movementCenterX = screenLeft + margin + movementRadius;
		float actionHalfSpan = keySize * BOTTOM_ACTION_HALF_SPAN_KEYS;
		float actionCenterX =
				screenRight - margin - actionHalfSpan - keySize * 0.5f;
		float shoulderCenterY = screenTop + margin + shoulderHeight * 0.5f;
		float clusterCenterY = screenBottom - margin - movementRadius;

		return new StandardVirtualControlsLayout(
				Placement.COMPACT_OVERLAY,
				keySize,
				movementRadius,
				movementCenterX,
				clusterCenterY,
				screenLeft + margin + shoulderWidth * 0.5f,
				screenRight - margin - shoulderWidth * 0.5f,
				shoulderCenterY,
				shoulderWidth,
				shoulderHeight,
				fireSize,
				actionCenterX - actionHalfSpan,
				actionCenterX + actionHalfSpan,
				actionCenterX,
				clusterCenterY - keySize * BOTTOM_ACTION_FIRE_OFFSET_KEYS,
				clusterCenterY + keySize * BOTTOM_ACTION_BOTTOM_OFFSET_KEYS);
	}

	private static float clamp(float value, float min, float max) {
		if (min > max) return (min + max) * 0.5f;
		return Math.max(min, Math.min(max, value));
	}
}
