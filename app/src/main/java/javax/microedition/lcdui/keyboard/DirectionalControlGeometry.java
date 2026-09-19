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

/** Pure geometry shared by the grouped virtual D-pad and analog stick. */
final class DirectionalControlGeometry {
	static final float ANALOG_CAPTURE_RADIUS_SCALE = 1.05f;
	static final float ANALOG_THUMB_RADIUS_SCALE = 0.28f;

	static final class Sample {
		final float x;
		final float y;
		final float thumbX;
		final float thumbY;

		Sample(float x, float y, float thumbX, float thumbY) {
			this.x = x;
			this.y = y;
			this.thumbX = thumbX;
			this.thumbY = thumbY;
		}
	}

	private DirectionalControlGeometry() {
	}

	static Sample sample(
			float centerX,
			float centerY,
			float radius,
			float touchX,
			float touchY) {
		return sampleWithTravel(centerX, centerY, radius, radius, touchX, touchY);
	}

	static Sample analogSample(
			float centerX,
			float centerY,
			float baseRadius,
			float touchX,
			float touchY) {
		return sampleWithTravel(centerX, centerY, baseRadius, baseRadius, touchX, touchY);
	}

	static Sample gestureSample(
			boolean analog,
			boolean movementSample,
			float centerX,
			float centerY,
			float radius,
			float touchX,
			float touchY) {
		if (analog && !movementSample) {
			return sample(centerX, centerY, radius, centerX, centerY);
		}
		return analog
				? analogSample(centerX, centerY, radius, touchX, touchY)
				: sample(centerX, centerY, radius, touchX, touchY);
	}

	static float analogThumbRadius(float baseRadius) {
		if (!(baseRadius > 0.0f) || !Float.isFinite(baseRadius)) {
			return 0.0f;
		}
		return baseRadius * ANALOG_THUMB_RADIUS_SCALE;
	}

	static boolean containsAnalogCapture(
			float centerX,
			float centerY,
			float baseRadius,
			float x,
			float y) {
		return containsRadially(
				centerX, centerY, baseRadius * ANALOG_CAPTURE_RADIUS_SCALE, x, y);
	}

	static boolean containsRadially(
			float centerX,
			float centerY,
			float radius,
			float x,
			float y) {
		if (!(radius > 0.0f) || !Float.isFinite(radius)
				|| !Float.isFinite(x) || !Float.isFinite(y)) {
			return false;
		}
		return Math.hypot(x - centerX, y - centerY) <= radius;
	}

	private static Sample sampleWithTravel(
			float centerX,
			float centerY,
			float inputRadius,
			float thumbTravelRadius,
			float touchX,
			float touchY) {
		if (!(inputRadius > 0.0f) || !Float.isFinite(inputRadius)) {
			return new Sample(0.0f, 0.0f, centerX, centerY);
		}
		float x = Float.isFinite(touchX) ? touchX : centerX;
		float y = Float.isFinite(touchY) ? touchY : centerY;
		float dx = x - centerX;
		float dy = y - centerY;
		float distance = (float) Math.hypot(dx, dy);
		float inputScale = distance > inputRadius && distance > 0.0f
				? inputRadius / distance : 1.0f;
		float normalizedX = dx * inputScale / inputRadius;
		float normalizedY = dy * inputScale / inputRadius;
		float visualTravel = Float.isFinite(thumbTravelRadius)
				? Math.max(0.0f, thumbTravelRadius) : 0.0f;
		return new Sample(
				normalizedX,
				normalizedY,
				centerX + normalizedX * visualTravel,
				centerY + normalizedY * visualTravel);
	}
}
