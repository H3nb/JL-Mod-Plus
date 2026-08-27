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

/** Validated speed-domain constants shared by manual controls and runtime timing code. */
public final class EmulationSpeed {
	public static final int MIN_PERCENT = 25;
	/** Maximum exposed by the deterministic manual picker; Auto is not capped to this value. */
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

	/**
	 * Validates the timing engine's runtime domain. Auto speed is intentionally not constrained by
	 * the manual picker's maximum; {@link Integer#MAX_VALUE} is only a representation limit.
	 */
	public static int requireRuntimePercent(int percent) {
		if (percent < MIN_PERCENT) {
			throw new IllegalArgumentException("Runtime emulation speed must be at least "
					+ MIN_PERCENT + "%: " + percent);
		}
		return percent;
	}

	/** Returns the safe fallback for a missing or malformed manual value. */
	public static int sanitizePercent(int percent) {
		return isValidPercent(percent) ? percent : NORMAL_PERCENT;
	}

	/** Returns a defensive copy so callers cannot mutate the supported picker values. */
	public static int[] presets() {
		return PRESETS.clone();
	}

	/** Formats a percentage as a locale-independent picker/overlay multiplier. */
	public static String formatMultiplier(int percent) {
		return formatMultiplierValue(sanitizePercent(percent));
	}

	/** Formats a measured diagnostic percentage without clamping it to the picker range. */
	public static String formatMeasuredMultiplier(int percent) {
		if (percent < 0) {
			throw new IllegalArgumentException("Measured speed must not be negative: " + percent);
		}
		return formatMultiplierValue(percent);
	}

	/** Formats any valid runtime speed, including Auto values above the manual picker range. */
	public static String formatRuntimeMultiplier(int percent) {
		return formatMultiplierValue(requireRuntimePercent(percent));
	}

	private static String formatMultiplierValue(int percent) {
		int whole = percent / 100;
		int fraction = percent % 100;
		if (fraction == 0) {
			return whole + "x";
		}
		String fractionText = fraction < 10 ? "0" + fraction : Integer.toString(fraction);
		while (fractionText.endsWith("0")) {
			fractionText = fractionText.substring(0, fractionText.length() - 1);
		}
		return whole + "." + fractionText + "x";
	}
}
