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

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private static final long MAX_MONITOR_WAIT_HOST_MILLIS = 10L;
	private static final long MONITOR_WAKE_TOLERANCE_NANOS = 1_000_000L;
	private final HostClock hostClock;
	private final AtomicReference<SpeedSnapshot> snapshot;
	private final AtomicLong lastVirtualNanos;
	private final AtomicLong lastWallMillis;
	private final Object waitMonitor = new Object();
	private final CopyOnWriteArrayList<EmulationSpeedListener> listeners =
			new CopyOnWriteArrayList<>();
	private final Object monitorRegistryLock = new Object();
	private final IdentityHashMap<Object, MonitorState> monitorRegistry =
			new IdentityHashMap<>();
	private final AtomicBoolean monitorFallbackReported = new AtomicBoolean();
	private volatile boolean timedWaitEnabled = true;
	private volatile MonitorFallbackListener monitorFallbackListener;
	private long waitGeneration;

	public interface MonitorFallbackListener {
		void onMonitorFallback(String reason);
	}

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

	/**
	 * Returns whether transformed monitor operations use the virtual monitor
	 * bridge.
	 */
	public boolean isTimedWaitEnabled() {
		return timedWaitEnabled;
	}

	/**
	 * Selects the monitor compatibility mode for the current MIDlet session.
	 *
	 * <p>The native mode is a deliberate per-MIDlet escape hatch for games whose
	 * private monitor protocol is incompatible with tracked virtual waits. It
	 * delegates all monitor operations to the JVM's native implementation.
	 */
	public void setTimedWaitEnabled(boolean enabled) {
		timedWaitEnabled = enabled;
		if (enabled) {
			monitorFallbackReported.set(false);
		}
	}

	public void setMonitorFallbackListener(MonitorFallbackListener listener) {
		monitorFallbackListener = listener;
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
		synchronized (monitorRegistryLock) {
			monitorRegistry.clear();
		}
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

	/**
	 * Waits on a caller-owned intrinsic monitor using a virtual timeout.
	 *
	 * <p>The transformed MIDlet call must already own the monitor, just like
	 * {@link Object#wait(long)}. The actual blocking and monitor reacquisition
	 * remain JVM operations. The identity registry only distinguishes logical
	 * notifications from timeout slices and selects exactly one logical waiter
	 * for {@code notify()}.</p>
	 */
	public void waitOn(Object monitor, long millis) throws InterruptedException {
		waitOn(monitor, millis, 0);
	}

	/**
	 * Variant of {@link #waitOn(Object, long)} with nanosecond precision using
	 * the same rounding rules as {@link Object#wait(long, int)}.
	 */
	public void waitOn(Object monitor, long millis, int nanos) throws InterruptedException {
		Objects.requireNonNull(monitor, "monitor");
		if (millis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		if (nanos < 0 || nanos > 999_999) {
			throw new IllegalArgumentException("nanosecond timeout value out of range");
		}
		if (!timedWaitEnabled) {
			if (nanos == 0) {
				monitor.wait(millis);
			} else {
				monitor.wait(millis, nanos);
			}
			return;
		}
		if (!Thread.holdsLock(monitor)) {
			throw new IllegalMonitorStateException();
		}

		MonitorWaitNode waitNode = registerMonitorWaiter(monitor);
		try {
			if (millis == 0L && nanos == 0) {
				waitIndefinitelyOnMonitor(monitor, waitNode);
				return;
			}
			if (nanos > 0 && millis < Long.MAX_VALUE) {
				millis++;
			}

			long targetNanos = saturatedAdd(
					nanoTime(), saturatedMultiply(millis, NANOS_PER_MILLI));
			while (true) {
				if (waitNode.notified) {
					return;
				}
				SpeedSnapshot current = snapshot.get();
				long nowNanos = observeVirtual(current.virtualNanosAt(hostClock.nanoTime()));
				if (nowNanos >= targetNanos) {
					return;
				}
				if (!timedWaitEnabled) {
					return;
				}

				long virtualRemainingNanos = targetNanos - nowNanos;
				long hostWaitNanos = current.isPaused()
						? MAX_MONITOR_WAIT_HOST_MILLIS * NANOS_PER_MILLI
						: hostDelayNanos(virtualRemainingNanos, current.speed());
				long hostWaitMillis = Math.max(1L, Math.min(MAX_MONITOR_WAIT_HOST_MILLIS,
						ceilDivide(hostWaitNanos, NANOS_PER_MILLI)));
				long signalGeneration = monitorSignalGeneration(waitNode.state);
				long startedHostNanos = System.nanoTime();
				monitor.wait(hostWaitMillis);
				long elapsedHostNanos = System.nanoTime() - startedHostNanos;
				if (waitNode.notified) {
					return;
				}
				if (monitorSignalGeneration(waitNode.state) != signalGeneration) {
					continue;
				}

				SpeedSnapshot afterWait = snapshot.get();
				long afterWaitNanos = observeVirtual(
						afterWait.virtualNanosAt(hostClock.nanoTime()));
				if (afterWaitNanos >= targetNanos) {
					return;
				}

				long requestedHostNanos = hostWaitMillis * NANOS_PER_MILLI;
				if (elapsedHostNanos + MONITOR_WAKE_TOLERANCE_NANOS
						< requestedHostNanos) {
					activateNativeMonitorFallback("untracked timed monitor notification");
					return;
				}
			}
		} finally {
			unregisterMonitorWaiter(monitor, waitNode);
		}
	}

	public void notifyMonitor(Object monitor) {
		Objects.requireNonNull(monitor, "monitor");
		if (!Thread.holdsLock(monitor)) {
			throw new IllegalMonitorStateException();
		}
		if (!timedWaitEnabled) {
			monitor.notify();
			return;
		}
		boolean selected = false;
		synchronized (monitorRegistryLock) {
			MonitorState state = monitorRegistry.get(monitor);
			if (state != null) {
				for (MonitorWaitNode waiter : state.waiters) {
					if (!waiter.notified) {
						waiter.notified = true;
						state.signalGeneration++;
						selected = true;
						break;
					}
				}
			}
		}
		if (selected) {
			monitor.notifyAll();
		} else {
			monitor.notify();
		}
	}

	public void notifyAllMonitors(Object monitor) {
		Objects.requireNonNull(monitor, "monitor");
		if (!Thread.holdsLock(monitor)) {
			throw new IllegalMonitorStateException();
		}
		if (!timedWaitEnabled) {
			monitor.notifyAll();
			return;
		}
		synchronized (monitorRegistryLock) {
			MonitorState state = monitorRegistry.get(monitor);
			if (state != null) {
				state.signalGeneration++;
				for (MonitorWaitNode waiter : state.waiters) {
					waiter.notified = true;
				}
			}
		}
		monitor.notifyAll();
	}

	int monitorRegistrySize() {
		synchronized (monitorRegistryLock) {
			return monitorRegistry.size();
		}
	}

	private void waitIndefinitelyOnMonitor(Object monitor, MonitorWaitNode waitNode)
			throws InterruptedException {
		while (!waitNode.notified) {
			if (!timedWaitEnabled) {
				monitor.wait();
				return;
			}
			long signalGeneration = monitorSignalGeneration(waitNode.state);
			monitor.wait();
			if (waitNode.notified) {
				return;
			}
			if (!timedWaitEnabled) {
				return;
			}
			if (monitorSignalGeneration(waitNode.state) != signalGeneration) {
				continue;
			}
			activateNativeMonitorFallback("untracked untimed monitor notification");
			return;
		}
	}

	private MonitorWaitNode registerMonitorWaiter(Object monitor) {
		synchronized (monitorRegistryLock) {
			MonitorState state = monitorRegistry.get(monitor);
			if (state == null) {
				state = new MonitorState();
				monitorRegistry.put(monitor, state);
			}
			MonitorWaitNode waiter = new MonitorWaitNode(state);
			state.waiters.addLast(waiter);
			return waiter;
		}
	}

	private void unregisterMonitorWaiter(Object monitor, MonitorWaitNode waiter) {
		synchronized (monitorRegistryLock) {
			MonitorState state = monitorRegistry.get(monitor);
			if (state == null) {
				return;
			}
			state.waiters.remove(waiter);
			if (state.waiters.isEmpty()) {
				monitorRegistry.remove(monitor);
			}
		}
	}

	private long monitorSignalGeneration(MonitorState state) {
		synchronized (monitorRegistryLock) {
			return state.signalGeneration;
		}
	}

	private void activateNativeMonitorFallback(String reason) {
		timedWaitEnabled = false;
		if (!monitorFallbackReported.compareAndSet(false, true)) {
			return;
		}
		MonitorFallbackListener listener = monitorFallbackListener;
		if (listener != null) {
			try {
				listener.onMonitorFallback(reason);
			} catch (RuntimeException e) {
				Log.w(TAG, "Monitor fallback listener failed", e);
			}
		}
	}

	private static final class MonitorState {
		final ArrayDeque<MonitorWaitNode> waiters = new ArrayDeque<>();
		long signalGeneration;
	}

	private static final class MonitorWaitNode {
		final MonitorState state;
		volatile boolean notified;

		MonitorWaitNode(MonitorState state) {
			this.state = state;
		}
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
