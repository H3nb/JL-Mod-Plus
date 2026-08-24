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

package javax.microedition.shell.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Date;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingMode;
import javax.microedition.shell.timing.TimingTimeSource;

public class TimerTest {
	@Test
	public void boundTimerWorkerExitsWhenItsTimingSessionCloses() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test", true);
			Thread worker = workerOf(timer);
			long startupDeadline = System.nanoTime() + 1_000_000_000L;
			while (!worker.isAlive() && System.nanoTime() < startupDeadline) {
				Thread.yield();
			}
			assertTrue(worker.isAlive());
			session.close();
			worker.join(1_000L);
			assertFalse(worker.isAlive());
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void persistentTimerRegistrationSurvivesGuestSleepRegistration() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-idle", true);
			Thread worker = workerOf(timer);
			CountDownLatch ran = new CountDownLatch(1);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					ran.countDown();
				}
			}, 20L);

			assertTrue(ran.await(1L, TimeUnit.SECONDS));
			long idleDeadline = System.nanoTime() + 1_000_000_000L;
			while (worker.getState() != Thread.State.WAITING
					&& System.nanoTime() < idleDeadline) {
				Thread.yield();
			}
			assertTrue(worker.getState() == Thread.State.WAITING);

			session.close();
			worker.join(1_000L);
			assertFalse(worker.isAlive());
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void realWallClockAbsoluteTimerWakesWhenSessionCloses() throws Exception {
		TimingSession session = new TimingSession(
				new HostTimeSource(), 400, 1L, TimingMode.REAL_WALL_CLOCK);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-real-wall", true);
			Thread worker = workerOf(timer);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					throw new AssertionError("Timer task should not run before session close");
				}
			}, new Date(System.currentTimeMillis() + 60_000L));

			Thread.sleep(20L);
			assertTrue(worker.isAlive());
			session.close();
			worker.join(1_000L);
			assertFalse(worker.isAlive());
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void oneTimerCanMixRelativeAndAbsoluteDateDeadlines() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-mixed-domains", true);
			CountDownLatch absoluteRan = new CountDownLatch(1);
			TimerTask relative = new TimerTask() {
				@Override
				public void run() {
					// Keep a recurring relative task ahead of the absolute task in the old raw heap.
				}
			};
			timer.schedule(relative, 1L, 1L);
			long target = GuestTimingBridge.currentTimeMillis() + 60L;
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					absoluteRan.countDown();
					relative.cancel();
				}
			}, new Date(target));

			assertTrue(absoluteRan.await(1L, TimeUnit.SECONDS));
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void scheduledExecutionTimePreservesAbsoluteDateTarget() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-scheduled-time", true);
			long target = GuestTimingBridge.currentTimeMillis() + 60L;
			AtomicLong observed = new AtomicLong(Long.MIN_VALUE);
			CountDownLatch ran = new CountDownLatch(1);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					observed.set(scheduledExecutionTime());
					ran.countDown();
				}
			}, new Date(target));

			assertTrue(ran.await(1L, TimeUnit.SECONDS));
			assertEquals(target, observed.get());
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void absoluteFixedRateRetainsOriginalPastDeadlineAndReadsDateOnce() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-absolute-fixed-rate", true);
			long target = GuestTimingBridge.currentTimeMillis() - 120L;
			AtomicInteger dateReads = new AtomicInteger();
			Date requested = new Date(target) {
				@Override
				public long getTime() {
					dateReads.incrementAndGet();
					return target;
				}
			};
			AtomicLong firstScheduled = new AtomicLong(Long.MIN_VALUE);
			AtomicLong secondScheduled = new AtomicLong(Long.MIN_VALUE);
			CountDownLatch secondRan = new CountDownLatch(1);
			AtomicInteger runs = new AtomicInteger();
			TimerTask task = new TimerTask() {
				@Override
				public void run() {
					if (runs.getAndIncrement() == 0) {
						firstScheduled.set(scheduledExecutionTime());
					} else {
						secondScheduled.set(scheduledExecutionTime());
						cancel();
						secondRan.countDown();
					}
				}
			};
			timer.scheduleAtFixedRate(task, requested, 50L);

			assertTrue(secondRan.await(1L, TimeUnit.SECONDS));
			assertEquals(1, dateReads.get());
			assertEquals(target, firstScheduled.get());
			assertEquals(target + 50L, secondScheduled.get());
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void zeroDelayTaskCanBeCancelledBeforeDispatch() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-zero-delay-cancel", true);
			CountDownLatch blockerStarted = new CountDownLatch(1);
			CountDownLatch releaseBlocker = new CountDownLatch(1);
			TimerTask blocker = new TimerTask() {
				@Override
				public void run() {
					blockerStarted.countDown();
					try {
						releaseBlocker.await(1L, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			};
			timer.schedule(blocker, 0L);
			assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

			CountDownLatch cancelledTaskRan = new CountDownLatch(1);
			TimerTask cancelledTask = new TimerTask() {
				@Override
				public void run() {
					cancelledTaskRan.countDown();
				}
			};
			timer.schedule(cancelledTask, 0L);
			assertTrue(cancelledTask.cancel());
			releaseBlocker.countDown();
			assertFalse(cancelledTaskRan.await(200L, TimeUnit.MILLISECONDS));
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void speedTransitionReevaluatesSleepingRelativeTimerDeadline() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-speed-transition", true);
			CountDownLatch ran = new CountDownLatch(1);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					ran.countDown();
				}
			}, 1_000L);
			Thread.sleep(80L);
			session.updateSpeedPercent(1_600);
			assertTrue(ran.await(500L, TimeUnit.MILLISECONDS));
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void realWallRelativeScheduledExecutionTimeFollowsSpeedTransition() throws Exception {
		TimingSession session = new TimingSession(
				new HostTimeSource(), 100, 1L, TimingMode.REAL_WALL_CLOCK);
		GuestTimingBridge.install(session);
		Timer timer = null;
		CountDownLatch blockerStarted = new CountDownLatch(1);
		CountDownLatch releaseBlocker = new CountDownLatch(1);
		try {
			timer = new Timer("timing-test-real-wall-relative", true);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					blockerStarted.countDown();
					try {
						releaseBlocker.await(1L, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}, 0L);
			assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

			long initialWallTime = System.currentTimeMillis();
			AtomicLong observedScheduledTime = new AtomicLong(Long.MIN_VALUE);
			CountDownLatch targetRan = new CountDownLatch(1);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					observedScheduledTime.set(scheduledExecutionTime());
					targetRan.countDown();
				}
			}, 1_000L);

			session.updateSpeedPercent(400);
			releaseBlocker.countDown();
			assertTrue(targetRan.await(1L, TimeUnit.SECONDS));
			assertTrue("scheduledExecutionTime retained the old speed projection: "
							+ observedScheduledTime.get(),
					observedScheduledTime.get() < initialWallTime + 700L);
		} finally {
			releaseBlocker.countDown();
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void timerTaskRuntimeExceptionTerminatesWorkerAndIsNotSwallowed() throws Exception {
		Timer timer = new Timer("timing-test-runtime-exception", true);
		try {
			Thread worker = workerOf(timer);
			AtomicReference<Throwable> uncaught = new AtomicReference<>();
			CountDownLatch ran = new CountDownLatch(1);
			worker.setUncaughtExceptionHandler((thread, throwable) -> uncaught.set(throwable));
			RuntimeException expected = new RuntimeException("timer failure");
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					ran.countDown();
					throw expected;
				}
			}, 0L);

			assertTrue(ran.await(1L, TimeUnit.SECONDS));
			worker.join(1_000L);
			assertFalse(worker.isAlive());
			assertSame(expected, uncaught.get());
			try {
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
					}
				}, 0L);
				fail("a Timer whose worker terminated must reject new tasks");
			} catch (IllegalStateException expectedState) {
				// Expected.
			}
		} finally {
			timer.cancel();
		}
	}

	@Test
	public void fixedDelaySchedulesFromActualExecutionTime() throws Exception {
		TimingSession session = new TimingSession(new HostTimeSource(), 100, 1L);
		GuestTimingBridge.install(session);
		Timer timer = null;
		try {
			timer = new Timer("timing-test-fixed-delay", true);
			CountDownLatch firstStarted = new CountDownLatch(1);
			CountDownLatch secondRan = new CountDownLatch(1);
			AtomicInteger runs = new AtomicInteger();
			AtomicLong firstScheduled = new AtomicLong();
			AtomicLong secondScheduled = new AtomicLong();
			TimerTask task = new TimerTask() {
				@Override
				public void run() {
					if (runs.getAndIncrement() == 0) {
						firstScheduled.set(scheduledExecutionTime());
						firstStarted.countDown();
						try {
							Thread.sleep(180L);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					} else {
						secondScheduled.set(scheduledExecutionTime());
						secondRan.countDown();
						cancel();
					}
				}
			};
			timer.schedule(task, 1L, 100L);
			assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
			assertTrue(secondRan.await(1L, TimeUnit.SECONDS));
			// A completion-relative implementation would publish roughly 280 ms here
			// (180 ms callback + 100 ms period). CLDC fixed-delay uses the prior dispatch
			// time, so the nominal dates remain one period apart.
			long scheduledDelta = secondScheduled.get() - firstScheduled.get();
			assertTrue("fixed-delay drifted from dispatch time: " + scheduledDelta,
					scheduledDelta >= 70L && scheduledDelta < 220L);
		} finally {
			if (timer != null) {
				timer.cancel();
			}
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void rejectsRelativeDelayWhenDeadlineOverflows() {
		Timer timer = new Timer("timing-test-overflow", true);
		try {
			try {
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
						fail("overflowing task must never run");
					}
				}, Long.MAX_VALUE);
				fail("expected IllegalArgumentException");
			} catch (IllegalArgumentException expected) {
				// Required by the CLDC schedule(delay) contract.
			}
		} finally {
			timer.cancel();
		}
	}

	private static Thread workerOf(Timer timer) throws Exception {
		Field field = Timer.class.getDeclaredField("impl");
		field.setAccessible(true);
		return (Thread) field.get(timer);
	}

	private static final class HostTimeSource implements TimingTimeSource {
		private final long monotonicAnchor = System.nanoTime();
		private final long wallAnchor = System.currentTimeMillis();

		@Override
		public long monotonicNanos() {
			return System.nanoTime() - monotonicAnchor;
		}

		@Override
		public long wallTimeMillis() {
			return wallAnchor + (System.nanoTime() - monotonicAnchor) / 1_000_000L;
		}
	}
}
