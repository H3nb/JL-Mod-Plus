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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.TimingSession;
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
