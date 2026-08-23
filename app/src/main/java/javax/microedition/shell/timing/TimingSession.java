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

import androidx.annotation.NonNull;

import java.util.concurrent.locks.LockSupport;

/**
 * Lifecycle-scoped guest clock mapping. All reads and speed transitions are serialized so a
 * transition is linearizable and cannot make guest time move backward.
 */
public final class TimingSession implements AutoCloseable {
	private final Object lock = new Object();
	private final TimingTimeSource timeSource;
	private final long generation;

	private TimingClockState clockState;
	private long lastHostMonotonicNanos;
	private long lastGuestMonotonicNanos;
	private long lastGuestWallTimeMillis;
	private boolean closed;

	public TimingSession(@NonNull TimingTimeSource timeSource, int speedPercent, long generation) {
		if (timeSource == null) {
			throw new NullPointerException("timeSource");
		}
		this.timeSource = timeSource;
		this.generation = generation;
		long hostAnchorNanos = timeSource.monotonicNanos();
		long wallAnchorMillis = timeSource.wallTimeMillis();
		this.clockState = new TimingClockState(
				hostAnchorNanos,
				0L,
				wallAnchorMillis,
				speedPercent,
				generation);
		lastHostMonotonicNanos = hostAnchorNanos;
		lastGuestMonotonicNanos = 0L;
		lastGuestWallTimeMillis = wallAnchorMillis;
	}

	public TimingSession(int speedPercent, long generation) {
		this(SystemTimingTimeSource.INSTANCE, speedPercent, generation);
	}

	public long generation() {
		return generation;
	}

	public boolean isClosed() {
		synchronized (lock) {
			return closed;
		}
	}

	/** Returns one coherent snapshot for bridge calls and diagnostics. */
	@NonNull
	public TimingSnapshot snapshot() {
		synchronized (lock) {
			ensureOpen();
			return snapshotAt(timeSource.monotonicNanos());
		}
	}

	public long guestMonotonicNanos() {
		return snapshot().guestMonotonicNanos();
	}

	public long guestWallTimeMillis() {
		return snapshot().guestWallTimeMillis();
	}

	public int speedPercent() {
		synchronized (lock) {
			ensureOpen();
			return clockState.speedPercent();
		}
	}

	/**
	 * Sleeps for a guest duration using the speed sampled at entry. Parking on a private token
	 * preserves held guest monitors and interruption semantics without polling or guest notify.
	 */
	public void sleep(long guestMillis) throws InterruptedException {
		if (guestMillis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		sleepGuestDuration(TimingMath.millisToNanos(guestMillis));
	}

	/** Java-compatible millisecond plus nanosecond sleep overload for transformed guest code. */
	public void sleep(long guestMillis, int guestNanos) throws InterruptedException {
		if (guestMillis < 0L || guestNanos < 0 || guestNanos > 999_999) {
			throw new IllegalArgumentException("timeout value is invalid");
		}
		sleepGuestDuration(TimingMath.saturatingAdd(
				TimingMath.millisToNanos(guestMillis), guestNanos));
	}

	private void sleepGuestDuration(long guestDurationNanos) throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		if (guestDurationNanos == 0L) {
			return;
		}

		long hostDeadlineNanos;
		synchronized (lock) {
			ensureOpen();
			TimingSnapshot start = snapshotAt(timeSource.monotonicNanos());
			long hostDurationNanos = TimingMath.scaleGuestToHostNanos(
					guestDurationNanos,
					start.speedPercent());
			hostDeadlineNanos = TimingMath.saturatingAdd(
					start.hostMonotonicNanos(), hostDurationNanos);
		}

		while (true) {
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}
			long remainingNanos = hostDeadlineNanos - timeSource.monotonicNanos();
			if (remainingNanos <= 0L) {
				return;
			}
			LockSupport.parkNanos(this, remainingNanos);
		}
	}

	/**
	 * Re-anchors at the current guest values before changing speed. The update is atomic with
	 * respect to snapshots and validates the new value before mutating state.
	 */
	public void updateSpeedPercent(int speedPercent) {
		EmulationSpeed.requireValidPercent(speedPercent);
		synchronized (lock) {
			ensureOpen();
			if (clockState.speedPercent() == speedPercent) {
				return;
			}
			TimingSnapshot current = snapshotAt(timeSource.monotonicNanos());
			clockState = new TimingClockState(
					current.hostMonotonicNanos(),
					current.guestMonotonicNanos(),
					current.guestWallTimeMillis(),
					speedPercent,
					generation);
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			closed = true;
		}
	}

	private TimingSnapshot snapshotAt(long hostNanos) {
		long effectiveHostNanos = Math.max(hostNanos, lastHostMonotonicNanos);
		long guestMonotonicNanos = Math.max(
				lastGuestMonotonicNanos,
				clockState.guestMonotonicNanosAt(effectiveHostNanos));
		long guestWallTimeMillis = Math.max(
				lastGuestWallTimeMillis,
				clockState.guestWallTimeMillisAt(effectiveHostNanos));
		lastHostMonotonicNanos = effectiveHostNanos;
		lastGuestMonotonicNanos = guestMonotonicNanos;
		lastGuestWallTimeMillis = guestWallTimeMillis;
		return new TimingSnapshot(
				generation,
				effectiveHostNanos,
				guestMonotonicNanos,
				guestWallTimeMillis,
				clockState.speedPercent());
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("TimingSession is closed");
		}
	}
}
