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

/** A coherent read of the active timing session at one host monotonic instant. */
public final class TimingSnapshot {
	private final long generation;
	private final long hostMonotonicNanos;
	private final long guestMonotonicNanos;
	private final long guestWallTimeMillis;
	private final int speedPercent;
	private final int timingMode;

	TimingSnapshot(
			long generation,
			long hostMonotonicNanos,
			long guestMonotonicNanos,
			long guestWallTimeMillis,
			int speedPercent) {
		this(generation, hostMonotonicNanos, guestMonotonicNanos, guestWallTimeMillis,
				speedPercent, TimingMode.FULL_GUEST_TIME);
	}

	TimingSnapshot(
			long generation,
			long hostMonotonicNanos,
			long guestMonotonicNanos,
			long guestWallTimeMillis,
			int speedPercent,
			int timingMode) {
		this.generation = generation;
		this.hostMonotonicNanos = hostMonotonicNanos;
		this.guestMonotonicNanos = guestMonotonicNanos;
		this.guestWallTimeMillis = guestWallTimeMillis;
		this.speedPercent = speedPercent;
		this.timingMode = TimingMode.sanitize(timingMode);
	}

	public long generation() {
		return generation;
	}

	public long hostMonotonicNanos() {
		return hostMonotonicNanos;
	}

	public long guestMonotonicNanos() {
		return guestMonotonicNanos;
	}

	public long guestWallTimeMillis() {
		return guestWallTimeMillis;
	}

	public int speedPercent() {
		return speedPercent;
	}

	public int timingMode() {
		return timingMode;
	}
}
