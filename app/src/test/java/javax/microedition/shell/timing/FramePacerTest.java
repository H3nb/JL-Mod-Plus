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

import org.junit.Test;

public class FramePacerTest {
	private static final long WALL_START = 1_700_000_000_000L;

	@Test
	public void effectiveIntervalScalesWithGuestSpeed() {
		assertEquals(16_666_667L, FramePacer.intervalNanos(60, 100));
		assertEquals(8_333_334L, FramePacer.intervalNanos(60, 200));
		assertEquals(66_666_667L, FramePacer.intervalNanos(15, 100));
	}

	@Test
	public void firstRequestUsesCreationAnchorAndLaterRequestsWaitForInterval() {
		FakeTimeSource time = new FakeTimeSource();
		RecordingSleeper sleeper = new RecordingSleeper(time);
		TimingSession session = new TimingSession(time, 100, 1L);
		FramePacer pacer = new FramePacer(session, sleeper);

		pacer.pace(60, true);
		assertEquals(16_666_667L, sleeper.lastParkNanos);
		pacer.pace(60, true);
		assertEquals(16_666_667L, sleeper.lastParkNanos);
	}

	@Test
	public void callbackRequestNeverBlocksButPreservesPendingCadence() {
		FakeTimeSource time = new FakeTimeSource();
		RecordingSleeper sleeper = new RecordingSleeper(time);
		TimingSession session = new TimingSession(time, 100, 1L);
		FramePacer pacer = new FramePacer(session, sleeper);

		pacer.pace(60, true);
		pacer.pace(60, false);
		assertEquals(16_666_667L, sleeper.lastParkNanos);
		pacer.pace(60, true);
		assertEquals(33_333_334L, sleeper.lastParkNanos);
	}

	@Test
	public void speedTransitionReanchorsWithoutCarryingOldDeadline() {
		FakeTimeSource time = new FakeTimeSource();
		RecordingSleeper sleeper = new RecordingSleeper(time);
		TimingSession session = new TimingSession(time, 100, 1L);
		FramePacer pacer = new FramePacer(session, sleeper);

		pacer.pace(60, true);
		session.updateSpeedPercent(200);
		pacer.pace(60, true);
		assertEquals(16_666_667L, sleeper.lastParkNanos);
		pacer.pace(60, true);
		assertEquals(8_333_334L, sleeper.lastParkNanos);
	}

	@Test
	public void disablingPacerResetsItsSchedule() {
		FakeTimeSource time = new FakeTimeSource();
		RecordingSleeper sleeper = new RecordingSleeper(time);
		TimingSession session = new TimingSession(time, 100, 1L);
		FramePacer pacer = new FramePacer(session, sleeper);

		pacer.pace(60, true);
		pacer.pace(0, true);
		sleeper.lastParkNanos = 0L;
		pacer.pace(60, true);
		assertEquals(0L, sleeper.lastParkNanos);
	}

	private static final class RecordingSleeper implements FramePacer.HostSleeper {
		private final FakeTimeSource time;
		private long lastParkNanos;

		RecordingSleeper(FakeTimeSource time) {
			this.time = time;
		}

		@Override
		public void parkNanos(Object blocker, long nanos) {
			lastParkNanos = nanos;
			time.advanceNanos(nanos);
		}
	}

	private static final class FakeTimeSource implements TimingTimeSource {
		private long monotonicNanos;

		@Override
		public long monotonicNanos() {
			return monotonicNanos;
		}

		@Override
		public long wallTimeMillis() {
			return WALL_START;
		}

		void advanceNanos(long nanos) {
			monotonicNanos += nanos;
		}
	}
}
