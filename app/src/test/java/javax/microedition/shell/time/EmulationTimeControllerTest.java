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

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EmulationTimeControllerTest {
	private static final long WALL_START = 1_700_000_000_000L;

	@Test
	public void startsAtTheHostWallClockAndVirtualZero() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);

		assertEquals(0L, controller.nanoTime());
		assertEquals(WALL_START, controller.currentTimeMillis());
		assertEquals(0L, new EmulationClock(controller).getTime());
	}

	@Test
	public void eachSupportedSpeedScalesElapsedTimeExactly() {
		for (EmulationSpeed speed : EmulationSpeed.values()) {
			MutableHostClock host = new MutableHostClock();
			EmulationTimeController controller = new EmulationTimeController(host);
			controller.setSpeed(speed);
			host.advanceMillis(1_000L);

			long expectedNanos = 1_000_000_000L * speed.numerator() / speed.denominator();
			long expectedMillis = 1_000L * speed.numerator() / speed.denominator();
			assertEquals(speed.toString(), expectedNanos, controller.nanoTime());
			assertEquals(speed.toString(), WALL_START + expectedMillis,
					controller.currentTimeMillis());
		}
	}

	@Test
	public void changingSpeedPreservesVirtualTimeContinuity() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		host.advanceMillis(2_000L);
		assertEquals(2_000_000_000L, controller.nanoTime());

		SpeedSnapshot before = controller.snapshot();
		SpeedSnapshot after = controller.setSpeed(EmulationSpeed.X4);
		assertEquals(2_000_000_000L, controller.nanoTime());
		assertEquals(2_000_000_000L, after.virtualAnchorNanos());
		assertEquals(before.generation() + 1L, after.generation());

		host.advanceMillis(500L);
		assertEquals(4_000_000_000L, controller.nanoTime());
	}

	@Test
	public void pauseFreezesTimeAndResumeUsesTheSelectedSpeed() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		host.advanceMillis(1_000L);
		long frozenNanos = controller.nanoTime();
		long frozenMillis = controller.currentTimeMillis();

		SpeedSnapshot paused = controller.pause();
		assertTrue(paused.isPaused());
		host.advanceMillis(5_000L);
		assertEquals(frozenNanos, controller.nanoTime());
		assertEquals(frozenMillis, controller.currentTimeMillis());

		controller.setSpeed(EmulationSpeed.X8);
		assertEquals(frozenNanos, controller.nanoTime());
		controller.resume();
		host.advanceMillis(250L);
		assertEquals(frozenNanos + 2_000_000_000L, controller.nanoTime());
		assertFalse(controller.snapshot().isPaused());
	}

	@Test
	public void repeatedNoOpTransitionsDoNotCreateGenerations() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		SpeedSnapshot initial = controller.snapshot();

		assertSame(initial, controller.setSpeed(EmulationSpeed.X1));
		assertSame(initial, controller.resume());
		SpeedSnapshot paused = controller.pause();
		assertSame(paused, controller.pause());
		assertSame(paused, controller.setSpeed(EmulationSpeed.X1));
		assertNotSame(initial, paused);
		assertEquals(initial.generation() + 1L, paused.generation());
	}

	@Test
	public void stoppingMakesFurtherTransitionsNoOps() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		SpeedSnapshot stopped = controller.stop();

		host.advanceMillis(1_000L);
		assertTrue(stopped.isStopping());
		assertSame(stopped, controller.setSpeed(EmulationSpeed.X16));
		assertSame(stopped, controller.resume());
		assertSame(stopped, controller.pause());
		assertEquals(0L, controller.nanoTime());
	}

	@Test
	public void timedJoinUsesVirtualDeadlineWhileRetainingThreadLiveness() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Thread target = new Thread(() -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "timed-join-target");
		target.start();

		EmulationTimeController controller = new EmulationTimeController(
				new AdvancingHostClock(100_000_000L));
		controller.setSpeed(EmulationSpeed.X16);
		controller.join(target, 10L);

		assertTrue(target.isAlive());
		release.countDown();
		target.join(1_000L);
		assertFalse(target.isAlive());
	}

	@Test
	public void timedMonitorWaitUsesVirtualDeadline() throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		controller.setSpeed(EmulationSpeed.X16);
		Object monitor = new Object();

		long started = System.nanoTime();
		synchronized (monitor) {
			controller.waitOn(monitor, 1_000L);
		}
		long elapsedNanos = System.nanoTime() - started;

		assertTrue("timed monitor wait was not virtualized", elapsedNanos < 800_000_000L);
		assertEquals(0, controller.monitorRegistrySize());
	}

	@Test
	public void untrackedNativeNotificationActivatesCompatibilityFallback() throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		Object monitor = new Object();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch fallbackReported = new CountDownLatch(1);
		AtomicBoolean returned = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		controller.setMonitorFallbackListener(reason -> fallbackReported.countDown());
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				entered.countDown();
				try {
					controller.waitOn(monitor, 5_000L);
					returned.set(true);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}
		}, "timed-monitor-notify-waiter");
		waiter.start();
		try {
			assertTrue(entered.await(1L, TimeUnit.SECONDS));
			waitForTimedWaiting(waiter);
			synchronized (monitor) {
				monitor.notify();
			}
			waiter.join(1_000L);

			assertFalse(waiter.isAlive());
			assertTrue(returned.get());
			assertTrue(fallbackReported.await(1L, TimeUnit.SECONDS));
			assertFalse(controller.isTimedWaitEnabled());
			assertEquals(0, controller.monitorRegistrySize());
			assertNull(failure.get());
		} finally {
			if (waiter.isAlive()) {
				waiter.interrupt();
				waiter.join(1_000L);
			}
		}
	}

	@Test
	public void extremeSpeedStopsAreExplicitAndBounded() {
		assertFalse(EmulationSpeed.X16.isExperimental());
		assertTrue(EmulationSpeed.X32.isExperimental());
		assertTrue(EmulationSpeed.MAX.isExperimental());
		assertEquals(32, EmulationSpeed.X32.numerator());
		assertEquals(128, EmulationSpeed.MAX.numerator());
	}

	@Test
	public void logicalNotifyWakesExactlyOneWaiterAndNotifyAllWakesTheRest()
			throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		Object monitor = new Object();
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch returned = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Runnable waitTask = () -> {
			synchronized (monitor) {
				entered.countDown();
				try {
					controller.waitOn(monitor, 0L);
					returned.countDown();
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
				}
			}
		};
		Thread first = new Thread(waitTask, "logical-monitor-waiter-1");
		Thread second = new Thread(waitTask, "logical-monitor-waiter-2");
		first.start();
		second.start();
		try {
			assertTrue(entered.await(1L, TimeUnit.SECONDS));
			waitForWaiting(first);
			waitForWaiting(second);
			synchronized (monitor) {
				controller.notifyMonitor(monitor);
			}
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
			while (returned.getCount() == 2L && System.nanoTime() < deadline) {
				Thread.yield();
			}
			assertEquals(1L, returned.getCount());

			synchronized (monitor) {
				controller.notifyAllMonitors(monitor);
			}
			assertTrue(returned.await(1L, TimeUnit.SECONDS));
			first.join(1_000L);
			second.join(1_000L);
			assertFalse(first.isAlive());
			assertFalse(second.isAlive());
			assertEquals(0, controller.monitorRegistrySize());
			assertNull(failure.get());
		} finally {
			first.interrupt();
			second.interrupt();
			first.join(1_000L);
			second.join(1_000L);
		}
	}

	@Test
	public void timedMonitorWaitRetainsNativeInterruption() throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		Object monitor = new Object();
		CountDownLatch entered = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				entered.countDown();
				try {
					controller.waitOn(monitor, 5_000L);
				} catch (InterruptedException expected) {
					interrupted.set(true);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}
		}, "timed-monitor-interrupt-waiter");
		waiter.start();
		try {
			assertTrue(entered.await(1L, TimeUnit.SECONDS));
			waitForTimedWaiting(waiter);
			waiter.interrupt();
			waiter.join(1_000L);

			assertFalse(waiter.isAlive());
			assertTrue(interrupted.get());
			assertEquals(0, controller.monitorRegistrySize());
			assertNull(failure.get());
		} finally {
			if (waiter.isAlive()) {
				waiter.interrupt();
				waiter.join(1_000L);
			}
		}
	}

	@Test
	public void timedMonitorWaitRequiresOwnedMonitor() throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		try {
			controller.waitOn(new Object(), 1L);
			fail("wait without monitor ownership returned");
		} catch (IllegalMonitorStateException expected) {
			// Native Object.wait semantics are retained.
		}
	}

	@Test
	public void timedMonitorWaitCanUseNativeCompatibilityMode() throws Exception {
		EmulationTimeController controller = new EmulationTimeController();
		controller.pause();
		controller.setTimedWaitEnabled(false);
		Object monitor = new Object();

		long started = System.nanoTime();
		synchronized (monitor) {
			controller.waitOn(monitor, 50L);
		}
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		assertFalse(controller.isTimedWaitEnabled());
		assertTrue("native compatibility wait did not use host timeout",
				elapsedMillis >= 25L && elapsedMillis < 1_000L);
	}

	@Test
	public void hostClockGoingBackDoesNotMoveVirtualTimeBackwards() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		host.advanceMillis(2_000L);
		long before = controller.nanoTime();
		host.setNanos(500_000_000L);

		assertEquals(before, controller.nanoTime());
	}

	@Test
	public void hostClockGoingBackDoesNotMoveWallTimeBackwards() {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		host.advanceMillis(2_000L);
		long before = controller.currentTimeMillis();
		host.setNanos(500_000_000L);

		assertEquals(before, controller.currentTimeMillis());
	}

	@Test
	public void schedulerSignalCanWakeAnAbsoluteWaitWithoutAdvancingTime()
			throws Exception {
		MutableHostClock host = new MutableHostClock();
		EmulationTimeController controller = new EmulationTimeController(host);
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean wokeBySignal = new AtomicBoolean();
		Thread waiter = new Thread(() -> {
			try {
				long generation = controller.waitGeneration();
				started.countDown();
				long targetMillis = controller.currentTimeMillis() + 10_000L;
				wokeBySignal.set(!controller.awaitWallMillisOrSignal(targetMillis, generation));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		waiter.start();
		assertTrue(started.await(1L, TimeUnit.SECONDS));
		controller.signalWaiters();
		waiter.join(1_000L);

		assertFalse(waiter.isAlive());
		assertTrue(wokeBySignal.get());
	}

	private static void waitForTimedWaiting(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
		while (thread.isAlive() && thread.getState() != Thread.State.TIMED_WAITING
				&& System.nanoTime() < deadline) {
			Thread.yield();
		}
		assertEquals(Thread.State.TIMED_WAITING, thread.getState());
	}

	private static void waitForWaiting(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
		while (thread.isAlive() && thread.getState() != Thread.State.WAITING
				&& System.nanoTime() < deadline) {
			Thread.yield();
		}
		assertEquals(Thread.State.WAITING, thread.getState());
	}

	private static final class MutableHostClock implements HostClock {
		private long nanos;

		@Override
		public long nanoTime() {
			return nanos;
		}

		@Override
		public long currentTimeMillis() {
			return WALL_START + nanos / 1_000_000L;
		}

		void advanceMillis(long millis) {
			nanos += millis * 1_000_000L;
		}

		void setNanos(long nanos) {
			this.nanos = nanos;
		}
	}

	private static final class AdvancingHostClock implements HostClock {
		private final long stepNanos;
		private long nanos;

		AdvancingHostClock(long stepNanos) {
			this.stepNanos = stepNanos;
		}

		@Override
		public long nanoTime() {
			long result = nanos;
			nanos += stepNanos;
			return result;
		}

		@Override
		public long currentTimeMillis() {
			return WALL_START + nanos / 1_000_000L;
		}
	}
}
