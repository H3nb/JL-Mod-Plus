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

/**
 * Immutable mapping from a host monotonic anchor to guest monotonic and wall-clock values.
 * Re-anchoring this state at a speed transition keeps both guest clocks continuous.
 */
public final class TimingClockState {
	private static final long NANOS_PER_MILLISECOND = 1_000_000L;

	private final long hostAnchorNanos;
	private final long guestMonotonicAnchorNanos;
	private final long guestWallAnchorMillis;
	private final int speedPercent;
	private final long generation;

	public TimingClockState(
			long hostAnchorNanos,
			long guestMonotonicAnchorNanos,
			long guestWallAnchorMillis,
			int speedPercent,
			long generation) {
		this.hostAnchorNanos = hostAnchorNanos;
		this.guestMonotonicAnchorNanos = guestMonotonicAnchorNanos;
		this.guestWallAnchorMillis = guestWallAnchorMillis;
		this.speedPercent = EmulationSpeed.requireRuntimePercent(speedPercent);
		this.generation = generation;
	}

	public long hostAnchorNanos() {
		return hostAnchorNanos;
	}

	public long guestMonotonicAnchorNanos() {
		return guestMonotonicAnchorNanos;
	}

	public long guestWallAnchorMillis() {
		return guestWallAnchorMillis;
	}

	public int speedPercent() {
		return speedPercent;
	}

	public long generation() {
		return generation;
	}

	/** Returns guest monotonic time without allowing a regressing host sample to move it backward. */
	public long guestMonotonicNanosAt(long hostNanos) {
		long delta = elapsedNanosSinceAnchor(hostNanos);
		return saturatingAdd(
				guestMonotonicAnchorNanos,
				TimingMath.scaleDurationNanos(delta, speedPercent));
	}

	/** Returns guest wall time derived from the same monotonic mapping as guest deadlines. */
	public long guestWallTimeMillisAt(long hostNanos) {
		long delta = elapsedNanosSinceAnchor(hostNanos);
		long scaledMillis = TimingMath.scaleDurationNanos(delta, speedPercent)
				/ NANOS_PER_MILLISECOND;
		return saturatingAdd(guestWallAnchorMillis, scaledMillis);
	}

	private long elapsedNanosSinceAnchor(long hostNanos) {
		if (hostNanos <= hostAnchorNanos) {
			return 0L;
		}
		return hostNanos - hostAnchorNanos;
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		if (right < 0L && left < Long.MIN_VALUE - right) {
			return Long.MIN_VALUE;
		}
		return left + right;
	}
}
