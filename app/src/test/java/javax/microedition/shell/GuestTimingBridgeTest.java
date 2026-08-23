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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingTimeSource;

public class GuestTimingBridgeTest {
	@Test
	public void bridgeUsesActiveSessionWallClockAndGeneration() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(time, 200, 12L);
		GuestTimingBridge.install(session);
		try {
			time.monotonicNanos = 500_000_000L;
			assertEquals(1_700_000_001_000L, GuestTimingBridge.currentTimeMillis());
			assertSame(session, GuestTimingBridge.activeSession());
			assertEquals(12L, GuestTimingBridge.activeSession().generation());
		} finally {
			GuestTimingBridge.clear(session);
		}
		assertNull(GuestTimingBridge.activeSession());
	}

	@Test
	public void staleClearCannotRemoveReplacementSession() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession first = new TimingSession(time, 100, 1L);
		TimingSession second = new TimingSession(time, 100, 2L);
		GuestTimingBridge.install(first);
		GuestTimingBridge.clear(first);
		GuestTimingBridge.install(second);
		try {
			GuestTimingBridge.clear(first);
			assertSame(second, GuestTimingBridge.activeSession());
		} finally {
			GuestTimingBridge.clear(second);
		}
	}

	private static final class FakeTimeSource implements TimingTimeSource {
		private long monotonicNanos;
		private final long wallTimeMillis;

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
	}
}
