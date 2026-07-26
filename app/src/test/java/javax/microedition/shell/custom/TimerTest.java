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

package javax.microedition.shell.custom;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.shell.time.EmulationSpeed;
import javax.microedition.shell.time.EmulationTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimerTest {
	@Test
	public void oneShotTaskUsesVirtualWallDeadline() throws Exception {
		Timer timer = new Timer(true);
		CountDownLatch fired = new CountDownLatch(1);
		AtomicLong scheduledTime = new AtomicLong();
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				scheduledTime.set(scheduledExecutionTime());
				fired.countDown();
			}
		};

		try {
			EmulationTime.setSpeed(EmulationSpeed.X16);
			long start = EmulationTime.currentTimeMillis();
			timer.schedule(task, 1_000L);

			assertTrue("one-shot TimerTask did not fire", fired.await(1L, TimeUnit.SECONDS));
			long elapsedVirtualMillis = scheduledTime.get() - start;
			assertTrue("TimerTask ran before its virtual deadline", elapsedVirtualMillis >= 1_000L);
			assertTrue("TimerTask did not use the virtual deadline", elapsedVirtualMillis < 2_000L);
		} finally {
			timer.cancel();
			EmulationTime.setSpeed(EmulationSpeed.X1);
		}
	}

	@Test
	public void fixedRateTaskCatchesUpOnVirtualSchedule() throws Exception {
		Timer timer = new Timer(true);
		CountDownLatch runs = new CountDownLatch(3);
		AtomicInteger count = new AtomicInteger();
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				count.incrementAndGet();
				runs.countDown();
			}
		};

		try {
			EmulationTime.setSpeed(EmulationSpeed.X16);
			timer.scheduleAtFixedRate(task, 0L, 200L);
			assertTrue("fixed-rate TimerTask did not run three times",
					runs.await(1L, TimeUnit.SECONDS));
			assertTrue(count.get() >= 3);
		} finally {
			timer.cancel();
			EmulationTime.setSpeed(EmulationSpeed.X1);
		}
	}

	@Test
	public void cancellingTaskPreventsItsFutureExecution() throws Exception {
		Timer timer = new Timer(true);
		CountDownLatch fired = new CountDownLatch(1);
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				fired.countDown();
			}
		};

		try {
			EmulationTime.setSpeed(EmulationSpeed.X1);
			timer.schedule(task, 200L);
			assertTrue(task.cancel());
			assertFalse("cancelled TimerTask still executed", fired.await(350L, TimeUnit.MILLISECONDS));
		} finally {
			timer.cancel();
		}
	}
}
