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

final class TimingMath {
	private static final int PERCENT_DENOMINATOR = 100;

	private TimingMath() {
	}

	/** Scales a non-negative duration while avoiding intermediate multiplication overflow. */
	static long scaleDurationNanos(long durationNanos, int speedPercent) {
		if (durationNanos <= 0L) {
			return 0L;
		}
		long whole = durationNanos / PERCENT_DENOMINATOR;
		int remainder = (int) (durationNanos % PERCENT_DENOMINATOR);
		long scaledWhole = saturatingMultiply(whole, speedPercent);
		long scaledRemainder = ((long) remainder * speedPercent) / PERCENT_DENOMINATOR;
		return saturatingAdd(scaledWhole, scaledRemainder);
	}

	/** Converts a guest duration to a host duration, rounding up to avoid an early wakeup. */
	static long scaleGuestToHostNanos(long guestDurationNanos, int speedPercent) {
		if (guestDurationNanos <= 0L) {
			return 0L;
		}
		long whole = guestDurationNanos / speedPercent;
		long remainder = guestDurationNanos % speedPercent;
		long scaledWhole = saturatingMultiply(whole, PERCENT_DENOMINATOR);
		long scaledRemainder = ceilMultiplyDivide(remainder, PERCENT_DENOMINATOR, speedPercent);
		return saturatingAdd(scaledWhole, scaledRemainder);
	}

	static long millisToNanos(long millis) {
		if (millis <= 0L) {
			return 0L;
		}
		return millis > Long.MAX_VALUE / 1_000_000L
				? Long.MAX_VALUE
				: millis * 1_000_000L;
	}

	private static long saturatingMultiply(long value, long factor) {
		if (value <= 0L || factor <= 0L) {
			return 0L;
		}
		if (value > Long.MAX_VALUE / factor) {
			return Long.MAX_VALUE;
		}
		return value * factor;
	}

	static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}

	private static long ceilMultiplyDivide(long value, long factor, long divisor) {
		if (value <= 0L || factor <= 0L) {
			return 0L;
		}
		long product = value * factor;
		return (product + divisor - 1L) / divisor;
	}
}
