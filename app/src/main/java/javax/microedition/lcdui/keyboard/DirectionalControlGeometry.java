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
		if (!(radius > 0.0f) || !Float.isFinite(radius)) {
			return new Sample(0.0f, 0.0f, centerX, centerY);
		}
		float x = Float.isFinite(touchX) ? touchX : centerX;
		float y = Float.isFinite(touchY) ? touchY : centerY;
		float dx = x - centerX;
		float dy = y - centerY;
		float distance = (float) Math.hypot(dx, dy);
		float scale = distance > radius && distance > 0.0f ? radius / distance : 1.0f;
		float clampedX = dx * scale;
		float clampedY = dy * scale;
		return new Sample(
				clampedX / radius,
				clampedY / radius,
				centerX + clampedX,
				centerY + clampedY);
	}
}
