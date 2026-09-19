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

/** Pure radial preprocessing shared by physical and virtual analog sticks. */
final class AnalogRadialProcessor {
	static final float INNER_DEADZONE = 0.15f;
	static final float OUTER_SATURATION = 0.95f;

	private AnalogRadialProcessor() {
	}

	static float processMagnitude(float rawMagnitude) {
		if (!Float.isFinite(rawMagnitude)) {
			return 0.0f;
		}
		float processed = (rawMagnitude - INNER_DEADZONE)
				/ (OUTER_SATURATION - INNER_DEADZONE);
		return Math.max(0.0f, Math.min(1.0f, processed));
	}
}
