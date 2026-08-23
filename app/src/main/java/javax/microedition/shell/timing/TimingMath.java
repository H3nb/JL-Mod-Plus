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

	private static long saturatingMultiply(long value, long factor) {
		if (value <= 0L || factor <= 0L) {
			return 0L;
		}
		if (value > Long.MAX_VALUE / factor) {
			return Long.MAX_VALUE;
		}
		return value * factor;
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}
}
