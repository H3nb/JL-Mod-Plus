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

package javax.microedition.shell.timing;

/** Validated speed-domain constants shared by configuration and runtime timing code. */
public final class EmulationSpeed {
	public static final int MIN_PERCENT = 25;
	public static final int MAX_PERCENT = 1600;
	public static final int NORMAL_PERCENT = 100;

	private static final int[] PRESETS = {
			25, 50, 75, 100, 125, 150, 200, 300, 400, 600, 800, 1200, 1600
	};

	private EmulationSpeed() {
	}

	public static boolean isValidPercent(int percent) {
		return percent >= MIN_PERCENT && percent <= MAX_PERCENT;
	}

	public static int requireValidPercent(int percent) {
		if (!isValidPercent(percent)) {
			throw new IllegalArgumentException("Emulation speed must be between "
					+ MIN_PERCENT + "% and " + MAX_PERCENT + "%: " + percent);
		}
		return percent;
	}

	/** Returns the safe migration fallback for a missing or malformed persisted value. */
	public static int sanitizePercent(int percent) {
		return isValidPercent(percent) ? percent : NORMAL_PERCENT;
	}

	/** Returns a defensive copy so callers cannot mutate the supported picker values. */
	public static int[] presets() {
		return PRESETS.clone();
	}
}
