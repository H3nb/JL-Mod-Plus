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

package javax.microedition.lcdui;

/** Normalizes a centered Android motion axis and applies its reported flat region. */
final class AxisNormalizer {
	private AxisNormalizer() {
	}

	static float normalize(float value, float min, float max, float flat) {
		if (!Float.isFinite(value) || !Float.isFinite(min) || !Float.isFinite(max)
				|| !Float.isFinite(flat) || min >= 0.0f || max <= 0.0f || min >= max) {
			return 0.0f;
		}
		float dead = Math.max(0.0f, flat);
		float clamped = Math.max(min, Math.min(max, value));
		if (Math.abs(clamped) <= dead) {
			return 0.0f;
		}
		if (clamped > 0.0f) {
			float span = max - dead;
			return span <= 0.0f ? 0.0f
					: Math.min(1.0f, (clamped - dead) / span);
		}
		float span = -min - dead;
		return span <= 0.0f ? 0.0f
				: Math.max(-1.0f, (clamped + dead) / span);
	}
}
