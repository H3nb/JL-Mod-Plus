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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
