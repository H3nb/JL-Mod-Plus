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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Lifecycle-scoped guest clock mapping. All reads and speed transitions are serialized so a
 * transition is linearizable and cannot make guest time move backward.
 */
public final class TimingSession implements AutoCloseable {
	/**
	 * Piecewise projection from guest monotonic time to the host wall-clock domain. Keeping the
	 * complete transition history is important for overdue fixed-rate TimerTask callbacks:
	 * projecting an old deadline from the current speed can make scheduledExecutionTime() move
	 * backwards after a speed change.
	 */
	private static final class WallProjectionAnchor {
		private final long guestMonotonicNanos;
		private final long hostWallTimeMillis;
		private final int speedPercent;

		private WallProjectionAnchor(
				long guestMonotonicNanos, long hostWallTimeMillis, int speedPercent) {
			this.guestMonotonicNanos = guestMonotonicNanos;
			this.hostWallTimeMillis = hostWallTimeMillis;
			this.speedPercent = speedPercent;
		}
	}

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
	private final int timingMode;
	private final Map<Thread, Integer> closeAwareThreadRegistrations = new HashMap<>();
	private final Map<Thread, Integer> timingChangeThreadRegistrations = new HashMap<>();
	private final Set<CloseAwareWait> closeAwareWaiters =
			Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final Set<Runnable> timingChangeListeners =
			Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final ArrayList<WallProjectionAnchor> wallProjectionAnchors = new ArrayList<>();
	private final long initialGuestWallTimeMillis;

	private TimingClockState clockState;
	private long lastHostMonotonicNanos;
	private long lastGuestMonotonicNanos;
	private long lastGuestWallTimeMillis;
	private long timingRevision;
	private boolean closed;

	public TimingSession(@NonNull TimingTimeSource timeSource, int speedPercent, long generation) {
		this(timeSource, speedPercent, generation, TimingMode.FULL_GUEST_TIME);
	}

	public TimingSession(
			@NonNull TimingTimeSource timeSource,
			int speedPercent,
			long generation,
			int timingMode) {
		if (timeSource == null) {
			throw new NullPointerException("timeSource");
		}
		this.timeSource = timeSource;
		this.generation = generation;
		this.timingMode = TimingMode.sanitize(timingMode);
		long hostAnchorNanos = timeSource.monotonicNanos();
		long wallAnchorMillis = timeSource.wallTimeMillis();
		initialGuestWallTimeMillis = wallAnchorMillis;
		this.clockState = new TimingClockState(
				hostAnchorNanos,
				0L,
				wallAnchorMillis,
				speedPercent,
				generation);
		lastHostMonotonicNanos = hostAnchorNanos;
		lastGuestMonotonicNanos = 0L;
		lastGuestWallTimeMillis = wallAnchorMillis;
		wallProjectionAnchors.add(
				new WallProjectionAnchor(0L, wallAnchorMillis, clockState.speedPercent()));
	}

	public TimingSession(int speedPercent, long generation) {
		this(SystemTimingTimeSource.INSTANCE, speedPercent, generation);
	}

	public TimingSession(int speedPercent, long generation, int timingMode) {
		this(SystemTimingTimeSource.INSTANCE, speedPercent, generation, timingMode);
	}

	public long generation() {
		return generation;
	}

	public int timingMode() {
		return timingMode;
	}

	public boolean usesGuestWallClock() {
		return timingMode == TimingMode.FULL_GUEST_TIME;
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

	/**
	 * Registers an emulator-owned scheduler that must be reevaluated after a speed transition.
	 * This registry is intentionally separate from guest sleep registrations: changing speed must
	 * wake Timer/host UI schedulers, but must not change the entry-sampled semantics of a guest
	 * {@code Thread.sleep} or {@code Object.wait} call.
	 */
	public void registerTimingChangeThread(@NonNull Thread thread) {
		if (thread == null) {
			throw new NullPointerException("thread");
		}
		synchronized (lock) {
			if (closed) {
				LockSupport.unpark(thread);
				return;
			}
			Integer registrations = timingChangeThreadRegistrations.get(thread);
			timingChangeThreadRegistrations.put(
					thread, registrations == null ? 1 : registrations + 1);
		}
	}

	/** Removes one emulator scheduler registration. */
	public void unregisterTimingChangeThread(@NonNull Thread thread) {
		if (thread == null) {
			throw new NullPointerException("thread");
		}
		synchronized (lock) {
			Integer registrations = timingChangeThreadRegistrations.get(thread);
			if (registrations == null || registrations <= 1) {
				timingChangeThreadRegistrations.remove(thread);
			} else {
				timingChangeThreadRegistrations.put(thread, registrations - 1);
			}
		}
	}

	/** Returns a monotonically increasing revision for speed transitions. */
	public long timingRevision() {
		synchronized (lock) {
			return timingRevision;
		}
	}

	/** Registers a host-owned callback that must recalculate a logical deadline after a speed change. */
	public void registerTimingChangeListener(@NonNull Runnable listener) {
		if (listener == null) {
			throw new NullPointerException("listener");
		}
		synchronized (lock) {
			if (!closed) {
				timingChangeListeners.add(listener);
			}
		}
	}

	/** Removes a previously registered timing-change callback. */
	public void unregisterTimingChangeListener(@NonNull Runnable listener) {
		if (listener == null) {
			throw new NullPointerException("listener");
		}
		timingChangeListeners.remove(listener);
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

	/** Returns the host wall clock for real-wall-clock Timer deadlines and bridge fallbacks. */
	public long hostWallTimeMillis() {
		synchronized (lock) {
			ensureOpen();
			return timeSource.wallTimeMillis();
		}
	}

	/** Returns the current host wall clock without exposing teardown as a guest failure. */
	public long hostWallTimeMillisOr(long fallbackMillis) {
		synchronized (lock) {
			return closed ? fallbackMillis : timeSource.wallTimeMillis();
		}
	}

	/**
	 * Projects a future guest-monotonic deadline into the wall-clock domain used by relative
	 * TimerTask scheduledExecutionTime() values.
	 *
	 * <p>Relative guest deadlines are projected through the piecewise speed history. Future
	 * deadlines therefore use the current segment, while deadlines that are already overdue use
	 * the segment in which they were nominally reached. Absolute Date schedules never use this
	 * method.</p>
	 *
	 * <p>A stale scheduler may race session teardown. Returning the current host wall time in that
	 * case lets the scheduler observe its normal stale-session check and exit without turning
	 * teardown into an IllegalStateException on an emulator-owned thread.</p>
	 */
	public long wallTimeMillisForGuestMonotonicMillis(long guestMonotonicMillis) {
		if (guestMonotonicMillis < 0L) {
			throw new IllegalArgumentException("guest monotonic deadline is negative");
		}
		synchronized (lock) {
			if (closed) {
				return timeSource.wallTimeMillis();
			}
			if (usesGuestWallClock()) {
				return TimingMath.saturatingAdd(initialGuestWallTimeMillis, guestMonotonicMillis);
			}

			// Advance the live state before selecting a historical segment. This preserves the
			// session's monotonicity guarantees for callers racing a host-clock sample, while the
			// actual projection below intentionally remains anchored to the requested deadline.
			snapshotAt(timeSource.monotonicNanos());
			long guestDeadlineNanos = TimingMath.millisToNanos(guestMonotonicMillis);
			WallProjectionAnchor anchor = wallProjectionAnchors.get(0);
			int low = 0;
			int high = wallProjectionAnchors.size() - 1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				WallProjectionAnchor candidate = wallProjectionAnchors.get(middle);
				if (candidate.guestMonotonicNanos <= guestDeadlineNanos) {
					anchor = candidate;
					low = middle + 1;
				} else {
					high = middle - 1;
				}
			}
			long guestDurationNanos = guestDeadlineNanos - anchor.guestMonotonicNanos;
			if (guestDurationNanos < 0L) {
				guestDurationNanos = 0L;
			}
			long hostDurationNanos = TimingMath.scaleGuestToHostNanos(
					guestDurationNanos, anchor.speedPercent);
			long hostDurationMillis = hostDurationNanos / 1_000_000L;
			if (hostDurationNanos % 1_000_000L != 0L
					&& hostDurationMillis < Long.MAX_VALUE) {
				hostDurationMillis++;
			}
			return TimingMath.saturatingAdd(anchor.hostWallTimeMillis, hostDurationMillis);
		}
	}

	/** Returns guest monotonic milliseconds for relative Timer deadlines. */
	public long guestMonotonicMillis() {
		return snapshot().guestMonotonicNanos() / 1_000_000L;
	}

	/** Sleeps in host time without applying the guest speed mapping. */
	public void sleepHostMillis(long hostMillis) throws InterruptedException {
		if (hostMillis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		if (hostMillis == 0L) {
			return;
		}

		Thread sleeper = Thread.currentThread();
		registerCloseAwareThread(sleeper);
		try {
			long hostDeadlineNanos = TimingMath.saturatingAdd(
					System.nanoTime(), TimingMath.millisToNanos(hostMillis));
			while (true) {
				if (Thread.interrupted()) {
					throw new InterruptedException();
				}
				synchronized (lock) {
					if (closed) {
						return;
					}
				}
				long remainingNanos = hostDeadlineNanos - System.nanoTime();
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
	 * Waits for an emulator-owned scheduler duration. A speed transition or session close returns
	 * normally so the scheduler can recalculate its queue; a real guest interrupt still propagates
	 * as {@link InterruptedException}.
	 *
	 * @param durationMillis duration in guest milliseconds when {@code guestDuration} is true,
	 *                       otherwise host milliseconds
	 * @param guestDuration whether the duration is subject to the current guest speed
	 */
	public void awaitSchedulerDuration(long durationMillis, boolean guestDuration)
			throws InterruptedException {
		if (durationMillis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		if (durationMillis == 0L) {
			return;
		}

		Thread scheduler = Thread.currentThread();
		long observedRevision;
		long hostDeadlineNanos;
		synchronized (lock) {
			if (closed) {
				return;
			}
			observedRevision = timingRevision;
			long durationNanos = TimingMath.millisToNanos(durationMillis);
			if (guestDuration) {
				durationNanos = TimingMath.scaleGuestToHostNanos(
						durationNanos, clockState.speedPercent());
			}
			hostDeadlineNanos = TimingMath.saturatingAdd(
					timeSource.monotonicNanos(), durationNanos);
			Integer registrations = timingChangeThreadRegistrations.get(scheduler);
			timingChangeThreadRegistrations.put(
					scheduler,
					registrations == null ? 1 : registrations + 1);
		}
		try {
			while (true) {
				if (Thread.interrupted()) {
					throw new InterruptedException();
				}
				synchronized (lock) {
					if (closed || timingRevision != observedRevision) {
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
			unregisterTimingChangeThread(scheduler);
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
		Set<Runnable> listeners;
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
			timingRevision++;
			if (!usesGuestWallClock()) {
				wallProjectionAnchors.add(new WallProjectionAnchor(
						current.guestMonotonicNanos(),
						timeSource.wallTimeMillis(),
						speedPercent));
			}
			for (Thread thread : timingChangeThreadRegistrations.keySet()) {
				LockSupport.unpark(thread);
			}
			listeners = new HashSet<>(timingChangeListeners);
		}
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException ignored) {
				// A host callback must never make a user-triggered speed change crash the MIDlet.
			}
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
			for (Thread thread : timingChangeThreadRegistrations.keySet()) {
				LockSupport.unpark(thread);
			}
			for (CloseAwareWait waiter : closeAwareWaiters) {
				waiter.closed = true;
				waiter.thread.interrupt();
			}
			timingChangeListeners.clear();
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
				clockState.speedPercent(),
				timingMode);
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("TimingSession is closed");
		}
	}
}
