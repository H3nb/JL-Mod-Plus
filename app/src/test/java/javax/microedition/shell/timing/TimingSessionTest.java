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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class TimingSessionTest {
	private static final long WALL_START = 1_700_000_000_000L;

	@Test
	public void freshSessionUsesMonotonicDeltaAndWallAnchor() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 100, 7L);

		time.advanceMillis(1_000L);
		TimingSnapshot snapshot = session.snapshot();

		assertEquals(7L, snapshot.generation());
		assertEquals(100, snapshot.speedPercent());
		assertEquals(1_000_000_000L, snapshot.guestMonotonicNanos());
		assertEquals(WALL_START + 1_000L, snapshot.guestWallTimeMillis());
	}

	@Test
	public void speedTransitionIsContinuousAndUsesNewRateAfterAnchor() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 100, 3L);

		time.advanceMillis(1_000L);
		session.updateSpeedPercent(200);
		assertEquals(1_000_000_000L, session.guestMonotonicNanos());
		assertEquals(WALL_START + 1_000L, session.guestWallTimeMillis());

		time.advanceMillis(500L);
		assertEquals(2_000_000_000L, session.guestMonotonicNanos());
		assertEquals(WALL_START + 2_000L, session.guestWallTimeMillis());
	}

	@Test
	public void futureRealWallTimerDeadlineUsesCurrentSpeedProjection() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(
				time, 100, 3L, TimingMode.REAL_WALL_CLOCK);

		assertEquals(
				WALL_START + 1_000L,
				session.wallTimeMillisForGuestMonotonicMillis(1_000L));
		session.updateSpeedPercent(400);
		assertEquals(
				WALL_START + 250L,
				session.wallTimeMillisForGuestMonotonicMillis(1_000L));
	}

	@Test
	public void realWallProjectionRetainsHistoricalSegmentsForOverdueDeadlines() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(
				time, 100, 3L, TimingMode.REAL_WALL_CLOCK);

		time.advanceMillis(1_000L);
		time.wallTimeMillis = WALL_START + 1_000L;
		session.updateSpeedPercent(400);

		// The first deadline belongs to the pre-transition segment even though it is now overdue.
		assertEquals(WALL_START + 1_000L,
				session.wallTimeMillisForGuestMonotonicMillis(1_000L));
		// Future deadlines use the new segment anchored at guest=1000 ms and host=WALL_START+1000.
		assertEquals(WALL_START + 1_250L,
				session.wallTimeMillisForGuestMonotonicMillis(2_000L));
	}

	@Test
	public void speedTransitionWakesOnlyEmulatorSchedulerWaiters() throws Exception {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 100, 3L);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread scheduler = new Thread(() -> {
			started.countDown();
			try {
				session.awaitSchedulerDuration(60_000L, true);
				finished.countDown();
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		});
		scheduler.start();
		assertTrue(started.await(1L, TimeUnit.SECONDS));
		Thread.sleep(20L);
		session.updateSpeedPercent(400);
		assertTrue(finished.await(1L, TimeUnit.SECONDS));
		assertNull(failure.get());
		scheduler.join(1_000L);
		assertFalse(scheduler.isAlive());
		session.close();
	}

	@Test
	public void fractionalAndHighSpeedValuesRemainExactForSmallDurations() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 25, 1L);

		time.advanceNanos(4_000_000L);
		assertEquals(1_000_000L, session.guestMonotonicNanos());
		session.updateSpeedPercent(1600);
		time.advanceNanos(2_000_000L);
		assertEquals(33_000_000L, session.guestMonotonicNanos());
	}

	@Test
	public void hostClockRegressionDoesNotMoveGuestClockBackward() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 400, 1L);

		time.advanceMillis(100L);
		long beforeRegression = session.guestMonotonicNanos();
		time.setMonotonicNanos(50_000_000L);

		assertEquals(beforeRegression, session.guestMonotonicNanos());
	}

	@Test
	public void invalidUpdateDoesNotMutateCurrentSpeed() {
		FakeTimeSource time = new FakeTimeSource(WALL_START);
		TimingSession session = new TimingSession(time, 100, 1L);

		try {
			session.updateSpeedPercent(1601);
			throw new AssertionError("Expected invalid speed to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}

		assertEquals(100, session.speedPercent());
	}

	@Test
	public void timedMonitorWaitValidatesArgumentsBeforeWaiting() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		Object monitor = new Object();

		try {
			session.waitOnMonitor(monitor, -1L);
			throw new AssertionError("Expected negative timeout to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
		try {
			session.waitOnMonitor(monitor, 0L, 1_000_000);
			throw new AssertionError("Expected invalid nanoseconds to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	@Test
	public void closedTimedMonitorWaitStillValidatesMonitorOwnership() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		session.close();

		try {
			session.waitOnMonitor(new Object(), 1L);
			throw new AssertionError("Expected monitor ownership to be validated");
		} catch (IllegalMonitorStateException expected) {
			// Expected.
		}
	}

	@Test
	public void nestedCloseAwareRegistrationsRemainUntilAllAreRemoved() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		CountDownLatch registered = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		Thread worker = new Thread(() -> {
			session.registerCloseAwareThread(Thread.currentThread());
			session.registerCloseAwareThread(Thread.currentThread());
			registered.countDown();
			session.unregisterCloseAwareThread(Thread.currentThread());
			LockSupport.park(session);
			finished.countDown();
		});
		worker.start();
		assertTrue(registered.await(1L, TimeUnit.SECONDS));
		session.close();
		assertTrue(finished.await(1L, TimeUnit.SECONDS));
		worker.join(1_000L);
		assertFalse(worker.isAlive());
	}

	@Test
	public void closeIsIdempotentAndPreventsFurtherReads() {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		session.close();
		session.close();
		assertTrue(session.isClosed());

		try {
			session.snapshot();
			throw new AssertionError("Expected closed session to reject reads");
		} catch (IllegalStateException expected) {
			// Expected.
		}
	}

	@Test
	public void closeUnparksGuestSleepersWithoutInterruptingTheirThread() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		CountDownLatch started = new CountDownLatch(1);
		Thread sleeper = new Thread(() -> {
			started.countDown();
			try {
				session.sleep(60_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		sleeper.start();
		assertTrue(started.await(1L, TimeUnit.SECONDS));
		session.close();
		sleeper.join(1_000L);
		if (sleeper.isAlive()) {
			sleeper.interrupt();
		}
		assertFalse(sleeper.isAlive());
	}

	@Test
	public void closeWakesGuestMonitorWaitersWithoutLeakingInternalInterrupt() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 25, 1L);
		Object monitor = new Object();
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				started.countDown();
				try {
					session.waitOnMonitor(monitor, 60_000L);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}
		});
		waiter.start();
		assertTrue(started.await(1L, TimeUnit.SECONDS));

		session.close();
		waiter.join(1_000L);
		if (waiter.isAlive()) {
			waiter.interrupt();
		}
		assertFalse(waiter.isAlive());
		assertTrue(failure.get() == null);
		assertFalse(waiter.isInterrupted());
	}

	@Test
	public void guestMonitorInterruptStillPropagates() throws Exception {
		TimingSession session = new TimingSession(new FakeTimeSource(WALL_START), 100, 1L);
		Object monitor = new Object();
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				started.countDown();
				try {
					session.waitOnMonitor(monitor, 60_000L);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}
		});
		waiter.start();
		assertTrue(started.await(1L, TimeUnit.SECONDS));

		waiter.interrupt();
		waiter.join(1_000L);
		assertFalse(waiter.isAlive());
		assertTrue(failure.get() instanceof InterruptedException);
		assertFalse(waiter.isInterrupted());
		session.close();
	}

	@Test
	public void stateArithmeticSaturatesInsteadOfWrapping() {
		TimingClockState state = new TimingClockState(
				0L, Long.MAX_VALUE - 1L, Long.MAX_VALUE - 1L, 1600, 1L);

		assertEquals(Long.MAX_VALUE, state.guestMonotonicNanosAt(1_000_000_000L));
		assertEquals(Long.MAX_VALUE, state.guestWallTimeMillisAt(1_000_000_000L));
	}

	private static final class FakeTimeSource implements TimingTimeSource {
		private long monotonicNanos;
		private long wallTimeMillis;

		FakeTimeSource(long wallTimeMillis) {
			this.wallTimeMillis = wallTimeMillis;
		}

		@Override
		public long monotonicNanos() {
			return monotonicNanos;
		}

		@Override
		public long wallTimeMillis() {
			return wallTimeMillis;
		}

		void advanceMillis(long millis) {
			advanceNanos(millis * 1_000_000L);
		}

		void advanceNanos(long nanos) {
			monotonicNanos += nanos;
		}

		void setMonotonicNanos(long nanos) {
			monotonicNanos = nanos;
		}
	}
}
