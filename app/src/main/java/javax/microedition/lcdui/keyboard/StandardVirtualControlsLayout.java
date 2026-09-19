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

/** Pure responsive geometry for the built-in D-pad/analog templates. */
final class StandardVirtualControlsLayout {
	private static final float EDGE_MARGIN_KEYS = 0.14f;

	private static final float LANDSCAPE_MOVEMENT_X = 0.25f;
	private static final float LANDSCAPE_MOVEMENT_Y = 0.55f;
	private static final float LANDSCAPE_SHOULDER_LEFT_X = 0.165f;
	private static final float LANDSCAPE_SHOULDER_RIGHT_X = 0.835f;
	private static final float LANDSCAPE_SHOULDER_Y = 0.125f;
	private static final float LANDSCAPE_ACTION_X = 0.74f;
	private static final float LANDSCAPE_ACTION_Y = 0.51f;
	private static final float LANDSCAPE_BOTTOM_OFFSET_X = 0.145f;
	private static final float LANDSCAPE_BOTTOM_Y = 0.69f;

	private static final float PORTRAIT_MOVEMENT_X = 0.27f;
	private static final float PORTRAIT_MOVEMENT_Y = 0.69f;
	private static final float PORTRAIT_SHOULDER_LEFT_X = 0.24f;
	private static final float PORTRAIT_SHOULDER_RIGHT_X = 0.76f;
	private static final float PORTRAIT_SHOULDER_Y = 0.14f;
	private static final float PORTRAIT_ACTION_X = 0.72f;
	private static final float PORTRAIT_ACTION_Y = 0.57f;
	private static final float PORTRAIT_BOTTOM_OFFSET_X = 0.13f;
	private static final float PORTRAIT_BOTTOM_Y = 0.76f;

	private static final float SHOULDER_WIDTH_KEYS = 1.65f;
	private static final float SHOULDER_HEIGHT_KEYS = 0.72f;
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
	final boolean landscape;

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
			float bottomRowY,
			boolean landscape) {
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
		this.landscape = landscape;
	}

	static StandardVirtualControlsLayout resolve(
			float screenLeft,
			float screenTop,
			float screenRight,
			float screenBottom,
			float movementRadius) {
		float width = Math.max(1.0f, screenRight - screenLeft);
		float height = Math.max(1.0f, screenBottom - screenTop);
		boolean landscape = width > height;
		float keySize = landscape
				? Math.min(width / 12.0f, height / 6.0f)
				: Math.min(width / 6.0f, height / 12.0f);
		float edgeMargin = keySize * EDGE_MARGIN_KEYS;
		float shoulderWidth = keySize * SHOULDER_WIDTH_KEYS;
		float shoulderHeight = keySize * SHOULDER_HEIGHT_KEYS;
		float fireSize = keySize * FIRE_SIZE_KEYS;

		float movementX = landscape ? LANDSCAPE_MOVEMENT_X : PORTRAIT_MOVEMENT_X;
		float movementY = landscape ? LANDSCAPE_MOVEMENT_Y : PORTRAIT_MOVEMENT_Y;
		float shoulderLeft = landscape ? LANDSCAPE_SHOULDER_LEFT_X : PORTRAIT_SHOULDER_LEFT_X;
		float shoulderRight = landscape ? LANDSCAPE_SHOULDER_RIGHT_X : PORTRAIT_SHOULDER_RIGHT_X;
		float shoulderY = landscape ? LANDSCAPE_SHOULDER_Y : PORTRAIT_SHOULDER_Y;
		float actionX = landscape ? LANDSCAPE_ACTION_X : PORTRAIT_ACTION_X;
		float actionY = landscape ? LANDSCAPE_ACTION_Y : PORTRAIT_ACTION_Y;
		float bottomOffset = width * (landscape ? LANDSCAPE_BOTTOM_OFFSET_X : PORTRAIT_BOTTOM_OFFSET_X);
		float bottomY = landscape ? LANDSCAPE_BOTTOM_Y : PORTRAIT_BOTTOM_Y;

		float movementCenterX = clamp(
				screenLeft + width * movementX,
				screenLeft + movementRadius + edgeMargin,
				screenRight - movementRadius - edgeMargin);
		float movementCenterY = clamp(
				screenTop + height * movementY,
				screenTop + movementRadius + edgeMargin,
				screenBottom - movementRadius - edgeMargin);

		float shoulderHalfWidth = shoulderWidth * 0.5f;
		float shoulderHalfHeight = shoulderHeight * 0.5f;
		float shoulderLeftX = clamp(
				screenLeft + width * shoulderLeft,
				screenLeft + shoulderHalfWidth + edgeMargin,
				screenRight - shoulderHalfWidth - edgeMargin);
		float shoulderRightX = clamp(
				screenLeft + width * shoulderRight,
				screenLeft + shoulderHalfWidth + edgeMargin,
				screenRight - shoulderHalfWidth - edgeMargin);
		float shoulderCenterY = clamp(
				screenTop + height * shoulderY,
				screenTop + shoulderHalfHeight + edgeMargin,
				screenBottom - shoulderHalfHeight - edgeMargin);

		float fireHalf = fireSize * 0.5f;
		float actionCenterX = clamp(
				screenLeft + width * actionX,
				screenLeft + fireHalf + edgeMargin,
				screenRight - fireHalf - edgeMargin);
		float actionCenterY = clamp(
				screenTop + height * actionY,
				screenTop + fireHalf + edgeMargin,
				screenBottom - fireHalf - edgeMargin);

		float bottomHalf = keySize * 0.5f;
		float bottomRowY = clamp(
				screenTop + height * bottomY,
				screenTop + bottomHalf + edgeMargin,
				screenBottom - bottomHalf - edgeMargin);
		float bottomLeftX = clamp(
				actionCenterX - bottomOffset,
				screenLeft + bottomHalf + edgeMargin,
				screenRight - bottomHalf - edgeMargin);
		float bottomRightX = clamp(
				actionCenterX + bottomOffset,
				screenLeft + bottomHalf + edgeMargin,
				screenRight - bottomHalf - edgeMargin);

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
				bottomRowY,
				landscape);
	}

	private static float clamp(float value, float min, float max) {
		if (min > max) return (min + max) * 0.5f;
		return Math.max(min, Math.min(max, value));
	}
}
