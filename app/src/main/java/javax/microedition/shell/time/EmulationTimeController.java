/*
 * Copyright 2026 H3NB
 *
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

package javax.microedition.shell.time;

import android.util.Log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns one MIDlet session's virtual clock and speed state.
 */
public final class EmulationTimeController {
	private static final String TAG = EmulationTimeController.class.getSimpleName();
	private static final long NANOS_PER_MILLI = 1_000_000L;
	private static final long MAX_HOST_WAIT_MILLIS = 1_000L;
	private static final long MAX_JOIN_HOST_WAIT_MILLIS = 10L;
	private final HostClock hostClock;
	private final AtomicReference<SpeedSnapshot> snapshot;
	private final AtomicLong lastVirtualNanos;
	private final AtomicLong lastWallMillis;
	private final Object waitMonitor = new Object();
	private final CopyOnWriteArrayList<EmulationSpeedListener> listeners =
			new CopyOnWriteArrayList<>();
	private long waitGeneration;

	public EmulationTimeController() {
		this(HostClock.SYSTEM);
	}

	EmulationTimeController(HostClock hostClock) {
		this.hostClock = Objects.requireNonNull(hostClock, "hostClock");
		long hostNanos = hostClock.nanoTime();
		long wallMillis = hostClock.currentTimeMillis();
		snapshot = new AtomicReference<>(new SpeedSnapshot(
				EmulationSpeed.X1, false, hostNanos, 0L, wallMillis, 0L));
		lastVirtualNanos = new AtomicLong(0L);
		lastWallMillis = new AtomicLong(wallMillis);
	}

	public SpeedSnapshot snapshot() {
		return snapshot.get();
	}

	public void addListener(EmulationSpeedListener listener) {
		listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
	}

	public void removeListener(EmulationSpeedListener listener) {
		if (listener != null) {
			listeners.remove(listener);
		}
	}

	public long nanoTime() {
		SpeedSnapshot current = snapshot.get();
		return observeVirtual(current.virtualNanosAt(hostClock.nanoTime()));
	}

	public long currentTimeMillis() {
		SpeedSnapshot current = snapshot.get();
		return observeWall(current.wallMillisAt(hostClock.nanoTime()));
	}

	public SpeedSnapshot setSpeed(EmulationSpeed speed) {
		Objects.requireNonNull(speed, "speed");
		SpeedSnapshot updated;
		synchronized (this) {
			SpeedSnapshot old = snapshot.get();
			if (old.isStopping() || old.speed() == speed) {
				return old;
			}
			long hostNanos = hostClock.nanoTime();
			long virtualNanos = observeVirtual(old.virtualNanosAt(hostNanos));
			long wallMillis = observeWall(old.wallMillisAt(hostNanos));
			updated = new SpeedSnapshot(
					speed, old.isPaused(), hostNanos, virtualNanos, wallMillis,
					old.generation() + 1L);
			snapshot.set(updated);
		}
		signalWaiters();
		notifyListeners(updated);
		return updated;
	}

	public SpeedSnapshot pause() {
		SpeedSnapshot updated;
		synchronized (this) {
			SpeedSnapshot old = snapshot.get();
			if (old.isStopping() || old.isPaused()) {
				return old;
			}
			long hostNanos = hostClock.nanoTime();
			long virtualNanos = observeVirtual(old.virtualNanosAt(hostNanos));
			long wallMillis = observeWall(old.wallMillisAt(hostNanos));
			updated = new SpeedSnapshot(
					old.speed(), true, hostNanos, virtualNanos, wallMillis,
					old.generation() + 1L);
			snapshot.set(updated);
		}
		signalWaiters();
		notifyListeners(updated);
		return updated;
	}

	public SpeedSnapshot resume() {
		SpeedSnapshot updated;
		synchronized (this) {
			SpeedSnapshot old = snapshot.get();
			if (old.isStopping() || !old.isPaused()) {
				return old;
			}
			long hostNanos = hostClock.nanoTime();
			updated = new SpeedSnapshot(
					old.speed(), false, hostNanos, old.virtualAnchorNanos(),
					old.wallAnchorMillis(), old.generation() + 1L);
			snapshot.set(updated);
		}
		signalWaiters();
		notifyListeners(updated);
		return updated;
	}

	public SpeedSnapshot stop() {
		SpeedSnapshot updated;
		synchronized (this) {
			SpeedSnapshot old = snapshot.get();
			if (old.isStopping()) {
				return old;
			}
			long hostNanos = hostClock.nanoTime();
			long virtualNanos = observeVirtual(old.virtualNanosAt(hostNanos));
			long wallMillis = observeWall(old.wallMillisAt(hostNanos));
			updated = new SpeedSnapshot(
					old.speed(), true, hostNanos, virtualNanos, wallMillis,
					old.generation() + 1L, true);
			snapshot.set(updated);
		}
		signalWaiters();
		notifyListeners(updated);
		return updated;
	}

	private void notifyListeners(SpeedSnapshot updated) {
		for (EmulationSpeedListener listener : listeners) {
			try {
				listener.onEmulationSpeedChanged(updated);
			} catch (RuntimeException e) {
				Log.w(TAG, "Emulation speed listener failed", e);
			}
		}
	}

	/**
	 * Sleeps until the requested duration has elapsed on this controller's
	 * virtual clock.  The host wait is deliberately interruptible and bounded
	 * so a speed or pause transition can take effect without polling a game
	 * thread aggressively.
	 */
	public void sleep(long millis, int nanos) throws InterruptedException {
		if (millis < 0L) {
			throw new IllegalArgumentException("millis < 0");
		}
		if (nanos < 0 || nanos > 999_999) {
			throw new IllegalArgumentException("nanos out of range");
		}
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		long requestedNanos = saturatedAdd(
				saturatedMultiply(millis, NANOS_PER_MILLI), nanos);
		if (requestedNanos == 0L) {
			Thread.yield();
			return;
		}

		long targetNanos = saturatedAdd(nanoTime(), requestedNanos);
		awaitVirtualNanos(targetNanos, false, 0L);
	}

	public void sleep(long millis) throws InterruptedException {
		sleep(millis, 0);
	}

	/**
	 * Waits for a thread using a virtual timeout while retaining the JVM's
	 * native join/termination notification semantics.  Short host slices are
	 * intentional: unlike the controller monitor, a Thread's termination
	 * notification cannot be signalled when the emulation speed changes.
	 */
	public void join(Thread thread, long millis) throws InterruptedException {
		Objects.requireNonNull(thread, "thread");
		if (millis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (millis == 0L) {
			thread.join();
			return;
		}
		joinUntil(thread, millis);
	}

	public void join(Thread thread, long millis, int nanos) throws InterruptedException {
		Objects.requireNonNull(thread, "thread");
		if (millis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (nanos < 0 || nanos > 999_999) {
			throw new IllegalArgumentException("nanosecond timeout value out of range");
		}
		if (nanos > 0 && millis < Long.MAX_VALUE) {
			millis++;
		}
		if (millis == 0L) {
			thread.join();
			return;
		}
		joinUntil(thread, millis);
	}

	private void joinUntil(Thread thread, long millis) throws InterruptedException {
		if (!thread.isAlive()) {
			return;
		}
		long targetNanos = saturatedAdd(
				nanoTime(), saturatedMultiply(millis, NANOS_PER_MILLI));
		while (thread.isAlive()) {
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}
			SpeedSnapshot current = snapshot.get();
			long nowNanos = observeVirtual(current.virtualNanosAt(hostClock.nanoTime()));
			if (nowNanos >= targetNanos) {
				return;
			}
			long virtualRemainingNanos = targetNanos - nowNanos;
			long hostWaitNanos = current.isPaused()
					? MAX_JOIN_HOST_WAIT_MILLIS * NANOS_PER_MILLI
					: hostDelayNanos(virtualRemainingNanos, current.speed());
			long hostWaitMillis = Math.max(1L, Math.min(MAX_JOIN_HOST_WAIT_MILLIS,
					ceilDivide(hostWaitNanos, NANOS_PER_MILLI)));
			thread.join(hostWaitMillis);
		}
	}

	public void awaitVirtualMillis(long millis) throws InterruptedException {
		if (millis < 0L) {
			throw new IllegalArgumentException("millis < 0");
		}
		if (millis == 0L) {
			return;
		}
		awaitVirtualNanos(saturatedAdd(nanoTime(), saturatedMultiply(millis, NANOS_PER_MILLI)),
				false, 0L);
	}

	public void signalWaiters() {
		synchronized (waitMonitor) {
			waitGeneration++;
			waitMonitor.notifyAll();
		}
	}

	public long waitGeneration() {
		synchronized (waitMonitor) {
			return waitGeneration;
		}
	}

	/**
	 * Waits for a virtual duration, returning early when a caller signals the
	 * shared scheduler condition after {@code knownGeneration} was sampled.
	 */
	public boolean awaitVirtualMillisOrSignal(long millis, long knownGeneration)
			throws InterruptedException {
		if (millis < 0L) {
			throw new IllegalArgumentException("millis < 0");
		}
		if (millis == 0L) {
			return true;
		}
		return awaitVirtualNanos(
				saturatedAdd(nanoTime(), saturatedMultiply(millis, NANOS_PER_MILLI)),
				true, knownGeneration);
	}

	/**
	 * Waits until an absolute virtual wall-clock timestamp is reached.  This is
	 * used by absolute timers so a speed change cannot add the elapsed time
	 * between taking a timestamp and entering the wait.
	 */
	public boolean awaitWallMillisOrSignal(long targetMillis, long knownGeneration)
			throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		synchronized (waitMonitor) {
			while (true) {
				if (Thread.interrupted()) {
					throw new InterruptedException();
				}
				if (waitGeneration != knownGeneration) {
					return false;
				}
				SpeedSnapshot current = snapshot.get();
				if (current.isStopping()) {
					return true;
				}
				long nowWallMillis = observeWall(current.wallMillisAt(hostClock.nanoTime()));
				if (nowWallMillis >= targetMillis) {
					return true;
				}
				long remainingMillis = targetMillis - nowWallMillis;
				long virtualRemainingNanos = saturatedMultiply(remainingMillis, NANOS_PER_MILLI);
				long hostWaitNanos = current.isPaused()
						? MAX_HOST_WAIT_MILLIS * NANOS_PER_MILLI
						: hostDelayNanos(virtualRemainingNanos, current.speed());
				waitMonitor.wait(toWaitMillis(hostWaitNanos), toWaitNanos(hostWaitNanos));
			}
		}
	}

	private boolean awaitVirtualNanos(long targetNanos, boolean returnOnSignal,
			long knownGeneration) throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		synchronized (waitMonitor) {
			long observedGeneration = returnOnSignal ? knownGeneration : waitGeneration;
			while (true) {
				if (Thread.interrupted()) {
					throw new InterruptedException();
				}
				if (returnOnSignal && waitGeneration != observedGeneration) {
					return false;
				}
				SpeedSnapshot current = snapshot.get();
				if (current.isStopping()) {
					return true;
				}
				long nowNanos = observeVirtual(current.virtualNanosAt(hostClock.nanoTime()));
				if (nowNanos >= targetNanos) {
					return true;
				}
				long hostWaitNanos = current.isPaused()
						? MAX_HOST_WAIT_MILLIS * NANOS_PER_MILLI
						: hostDelayNanos(targetNanos - nowNanos, current.speed());
				waitMonitor.wait(toWaitMillis(hostWaitNanos), toWaitNanos(hostWaitNanos));
			}
		}
	}

	private static long hostDelayNanos(long virtualRemainingNanos, EmulationSpeed speed) {
		long numerator = speed.numerator();
		long denominator = speed.denominator();
		long quotient = virtualRemainingNanos / numerator;
		long remainder = virtualRemainingNanos % numerator;
		long scaledQuotient = saturatedMultiply(quotient, denominator);
		long scaledRemainder = (remainder * denominator + numerator - 1L) / numerator;
		return saturatedAdd(scaledQuotient, scaledRemainder);
	}

	private static long ceilDivide(long value, long divisor) {
		if (value <= 0L) {
			return 0L;
		}
		return value >= Long.MAX_VALUE - divisor + 1L
				? Long.MAX_VALUE
				: (value + divisor - 1L) / divisor;
	}

	private static long toWaitMillis(long hostWaitNanos) {
		long millis = hostWaitNanos / NANOS_PER_MILLI;
		return Math.max(0L, Math.min(MAX_HOST_WAIT_MILLIS, millis));
	}

	private static int toWaitNanos(long hostWaitNanos) {
		long millis = toWaitMillis(hostWaitNanos);
		long remainder = hostWaitNanos - millis * NANOS_PER_MILLI;
		if (millis == MAX_HOST_WAIT_MILLIS) {
			return 0;
		}
		if (remainder <= 0L) {
			return hostWaitNanos > 0L ? 1 : 0;
		}
		return (int) Math.min(999_999L, remainder);
	}

	private long observeVirtual(long candidate) {
		return observeNonDecreasing(lastVirtualNanos, candidate);
	}

	private long observeWall(long candidate) {
		return observeNonDecreasing(lastWallMillis, candidate);
	}

	private static long observeNonDecreasing(AtomicLong lastValue, long candidate) {
		while (true) {
			long previous = lastValue.get();
			if (candidate <= previous) {
				return previous;
			}
			if (lastValue.compareAndSet(previous, candidate)) {
				return candidate;
			}
		}
	}

	static long scaleElapsed(long elapsedNanos, int numerator, int denominator) {
		long quotient = elapsedNanos / denominator;
		long remainder = elapsedNanos % denominator;
		long scaledQuotient = saturatedMultiply(quotient, numerator);
		long scaledRemainder = (remainder * numerator) / denominator;
		return saturatedAdd(scaledQuotient, scaledRemainder);
	}

	static long saturatedMultiply(long value, long factor) {
		if (value <= 0 || factor <= 0) {
			return 0L;
		}
		if (value > Long.MAX_VALUE / factor) {
			return Long.MAX_VALUE;
		}
		return value * factor;
	}

	static long saturatedAdd(long left, long right) {
		if (right <= 0 || left >= Long.MAX_VALUE - right) {
			return right <= 0 ? left : Long.MAX_VALUE;
		}
		return left + right;
	}
}
