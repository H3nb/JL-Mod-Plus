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
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Lifecycle-scoped guest clock mapping. All reads and speed transitions are serialized so a
 * transition is linearizable and cannot make guest time move backward.
 */
public final class TimingSession implements AutoCloseable {
	private static final class CloseAwareWait {
		private final Thread thread;
		private volatile boolean closed;

		private CloseAwareWait(Thread thread) {
			this.thread = thread;
		}
	}

	private final Object lock = new Object();
	private final TimingTimeSource timeSource;
	private final long generation;
	private final Map<Thread, Integer> closeAwareThreadRegistrations = new HashMap<>();
	private final Set<CloseAwareWait> closeAwareWaiters =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

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

	/** Registers an emulator-owned thread that must be woken when this session closes. */
	public void registerCloseAwareThread(@NonNull Thread thread) {
		if (thread == null) {
			throw new NullPointerException("thread");
		}
		synchronized (lock) {
			if (closed) {
				LockSupport.unpark(thread);
				return;
			}
			Integer registrations = closeAwareThreadRegistrations.get(thread);
			closeAwareThreadRegistrations.put(
					thread, registrations == null ? 1 : registrations + 1);
		}
	}

	/** Removes one registration for an emulator-owned thread. */
	public void unregisterCloseAwareThread(@NonNull Thread thread) {
		if (thread == null) {
			throw new NullPointerException("thread");
		}
		synchronized (lock) {
			Integer registrations = closeAwareThreadRegistrations.get(thread);
			if (registrations == null || registrations <= 1) {
				closeAwareThreadRegistrations.remove(thread);
			} else {
				closeAwareThreadRegistrations.put(thread, registrations - 1);
			}
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

	/**
	 * Returns a coherent snapshot while the session is open, or {@code null} for a stale caller.
	 * Unlike {@link #snapshot()}, this method is intended for teardown-tolerant bridge and host UI
	 * reads where exposing the session's closed state as an exception would be a lifecycle bug.
	 */
	@Nullable
	public TimingSnapshot snapshotIfOpen() {
		synchronized (lock) {
			return closed ? null : snapshotAt(timeSource.monotonicNanos());
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

	/** Returns the current speed or a safe host/UI fallback if the session has closed. */
	public int speedPercentOr(int fallbackPercent) {
		synchronized (lock) {
			return closed ? fallbackPercent : clockState.speedPercent();
		}
	}

	/** Converts a guest millisecond deadline to a host Handler delay, rounded up safely. */
	public long hostDelayMillis(long guestMillis) {
		if (guestMillis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (guestMillis == 0L) {
			return 0L;
		}
		synchronized (lock) {
			if (closed) {
				return 0L;
			}
			TimingSnapshot start = snapshotAt(timeSource.monotonicNanos());
			long hostDurationNanos = TimingMath.scaleGuestToHostNanos(
					TimingMath.millisToNanos(guestMillis), start.speedPercent());
			long hostMillis = hostDurationNanos / 1_000_000L;
			if (hostDurationNanos % 1_000_000L != 0L && hostMillis < Long.MAX_VALUE) {
				hostMillis++;
			}
			return hostMillis;
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

	/**
	 * Waits on the supplied guest monitor with a speed-scaled timeout sampled at entry. The
	 * monitor wait itself remains a JVM wait, so monitor release, notify, and reacquisition retain
	 * their normal semantics.
	 */
	public void waitOnMonitor(Object monitor, long guestMillis) throws InterruptedException {
		if (monitor == null) {
			throw new NullPointerException("monitor");
		}
		if (guestMillis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		waitOnMonitorNanos(monitor, TimingMath.millisToNanos(guestMillis));
	}

	/** Java-compatible millisecond plus nanosecond timed monitor wait. */
	public void waitOnMonitor(Object monitor, long guestMillis, int guestNanos)
			throws InterruptedException {
		if (monitor == null) {
			throw new NullPointerException("monitor");
		}
		if (guestMillis < 0L || guestNanos < 0 || guestNanos > 999_999) {
			throw new IllegalArgumentException("timeout value is invalid");
		}
		waitOnMonitorNanos(monitor, TimingMath.saturatingAdd(
				TimingMath.millisToNanos(guestMillis), guestNanos));
	}

	private void waitOnMonitorNanos(Object monitor, long guestDurationNanos)
			throws InterruptedException {
		long hostDurationNanos;
		CloseAwareWait waiter = new CloseAwareWait(Thread.currentThread());
		boolean closedAtEntry;
		synchronized (lock) {
			closedAtEntry = closed;
			if (!closedAtEntry) {
				TimingSnapshot start = snapshotAt(timeSource.monotonicNanos());
				hostDurationNanos = TimingMath.scaleGuestToHostNanos(
						guestDurationNanos, start.speedPercent());
				closeAwareWaiters.add(waiter);
			} else {
				hostDurationNanos = 0L;
			}
		}
		if (closedAtEntry) {
			// Object.wait validates monitor ownership before it can return. Preserve that
			// validation even for a stale transformed call, without leaving a closed-session
			// guest thread blocked indefinitely.
			monitor.wait(0L, 1);
			return;
		}
		long hostMillis = hostDurationNanos / 1_000_000L;
		int hostNanos = (int) (hostDurationNanos % 1_000_000L);
		try {
			monitor.wait(hostMillis, hostNanos);
		} catch (InterruptedException e) {
			// A session close is an emulator-owned wakeup, not a guest interrupt. Let teardown
			// finish without exposing a stale InterruptedException to guest code.
			if (!waiter.closed) {
				throw e;
			}
		} finally {
			closeAwareWaiters.remove(waiter);
		}
	}

	private void sleepGuestDuration(long guestDurationNanos) throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		if (guestDurationNanos == 0L) {
			return;
		}

		Thread sleeper = Thread.currentThread();
		registerCloseAwareThread(sleeper);
		try {
			long hostDeadlineNanos;
			synchronized (lock) {
				// Teardown may race a guest sleep call. Returning here lets lifecycle shutdown
				// finish without turning a stale transformed call into a guest crash.
				if (closed) {
					return;
				}
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
				synchronized (lock) {
					if (closed) {
						return;
					}
				}
				long remainingNanos = hostDeadlineNanos - timeSource.monotonicNanos();
				if (remainingNanos <= 0L) {
					return;
				}
				LockSupport.parkNanos(this, remainingNanos);
			}
		} finally {
			unregisterCloseAwareThread(sleeper);
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
			if (closed) {
				return;
			}
			closed = true;
			for (Thread thread : closeAwareThreadRegistrations.keySet()) {
				LockSupport.unpark(thread);
			}
			for (CloseAwareWait waiter : closeAwareWaiters) {
				waiter.closed = true;
				waiter.thread.interrupt();
			}
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
