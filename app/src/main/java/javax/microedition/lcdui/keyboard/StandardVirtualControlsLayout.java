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
 * Pure screen-space geometry for the built-in D-pad/analog templates.
 *
 * The template is intentionally independent from the MIDlet/guest viewport. Letterboxing changes
 * the game underneath the overlay, not the standard controller composition.
 */
final class StandardVirtualControlsLayout {
	private static final float EDGE_MARGIN_KEYS = 0.12f;

	// Measured from the approved standard-template reference composition.
	private static final float MOVEMENT_X = 0.248f;
	private static final float MOVEMENT_Y = 0.552f;
	private static final float SHOULDER_LEFT_X = 0.164f;
	private static final float SHOULDER_RIGHT_X = 0.837f;
	private static final float SHOULDER_Y = 0.122f;
	private static final float ACTION_X = 0.740f;
	private static final float ACTION_Y = 0.509f;
	private static final float STAR_X = 0.592f;
	private static final float ZERO_X = 0.885f;
	private static final float BOTTOM_Y = 0.690f;

	private static final float KEY_SIZE_SHORT_SIDE = 0.178f;
	private static final float SHOULDER_WIDTH_KEYS = 1.66f;
	private static final float SHOULDER_HEIGHT_KEYS = 0.73f;
	private static final float FIRE_SIZE_KEYS = 1.25f;

	final float keySize;
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
			float keySize,
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
		this.keySize = keySize;
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
			float movementRadius) {
		float width = Math.max(1.0f, screenRight - screenLeft);
		float height = Math.max(1.0f, screenBottom - screenTop);
		float shortSide = Math.min(width, height);
		float keySize = shortSide * KEY_SIZE_SHORT_SIDE;
		float edgeMargin = keySize * EDGE_MARGIN_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;
		float fireSize = keySize * FIRE_SIZE_KEYS;

		float movementCenterX = clamp(
				screenLeft + width * MOVEMENT_X,
				screenLeft + movementRadius + edgeMargin,
				screenRight - movementRadius - edgeMargin);
		float movementCenterY = clamp(
				screenTop + height * MOVEMENT_Y,
				screenTop + movementRadius + edgeMargin,
				screenBottom - movementRadius - edgeMargin);

		float shoulderHalfWidth = shoulderWidth * 0.5f;
		float shoulderHalfHeight = shoulderHeight * 0.5f;
		float shoulderLeftX = clamp(
				screenLeft + width * SHOULDER_LEFT_X,
				screenLeft + shoulderHalfWidth + edgeMargin,
				screenRight - shoulderHalfWidth - edgeMargin);
		float shoulderRightX = clamp(
				screenLeft + width * SHOULDER_RIGHT_X,
				screenLeft + shoulderHalfWidth + edgeMargin,
				screenRight - shoulderHalfWidth - edgeMargin);
		float shoulderCenterY = clamp(
				screenTop + height * SHOULDER_Y,
				screenTop + shoulderHalfHeight + edgeMargin,
				screenBottom - shoulderHalfHeight - edgeMargin);

		float fireHalf = fireSize * 0.5f;
		float actionCenterX = clamp(
				screenLeft + width * ACTION_X,
				screenLeft + fireHalf + edgeMargin,
				screenRight - fireHalf - edgeMargin);
		float actionCenterY = clamp(
				screenTop + height * ACTION_Y,
				screenTop + fireHalf + edgeMargin,
				screenBottom - fireHalf - edgeMargin);

		float bottomHalf = keySize * 0.5f;
		float bottomLeftX = clamp(
				screenLeft + width * STAR_X,
				screenLeft + bottomHalf + edgeMargin,
				screenRight - bottomHalf - edgeMargin);
		float bottomRightX = clamp(
				screenLeft + width * ZERO_X,
				screenLeft + bottomHalf + edgeMargin,
				screenRight - bottomHalf - edgeMargin);
		float bottomRowY = clamp(
				screenTop + height * BOTTOM_Y,
				screenTop + bottomHalf + edgeMargin,
				screenBottom - bottomHalf - edgeMargin);

		return new StandardVirtualControlsLayout(
				keySize,
				movementCenterX,
				movementCenterY,
				shoulderLeftX,
				shoulderRightX,
				shoulderCenterY,
				shoulderWidth,
				shoulderHeight,
				fireSize,
				bottomLeftX,
				bottomRightX,
				actionCenterX,
				actionCenterY,
				bottomRowY);
	}

	private static float clamp(float value, float min, float max) {
		if (min > max) return (min + max) * 0.5f;
		return Math.max(min, Math.min(max, value));
	}
}
