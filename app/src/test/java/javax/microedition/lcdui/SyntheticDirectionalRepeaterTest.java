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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SyntheticDirectionalRepeaterTest {
	@Test
	public void oneSyntheticOwnerRepeatsAfterDelayAndStopsOnRelease() {
		Fixture fixture = new Fixture();
		fixture.repeater.setVirtualSource(1, Canvas.KEY_UP);

		fixture.scheduler.advanceBy(399);
		assertEquals(0, fixture.repeated.size());
		fixture.scheduler.advanceBy(1);
		assertEquals(List.of(Canvas.KEY_UP), fixture.repeated);
		fixture.scheduler.advanceBy(80);
		assertEquals(List.of(Canvas.KEY_UP, Canvas.KEY_UP), fixture.repeated);

		fixture.repeater.setVirtualSource(1, 0);
		fixture.scheduler.advanceBy(1000);
		assertEquals(2, fixture.repeated.size());
	}

	@Test
	public void twoSyntheticOwnersShareOneRepeatStream() {
		Fixture fixture = new Fixture();
		fixture.repeater.setVirtualSource(1, Canvas.KEY_UP);
		fixture.repeater.setDeviceSource(7, 1, Canvas.KEY_UP);

		fixture.scheduler.advanceBy(400);
		assertEquals(List.of(Canvas.KEY_UP), fixture.repeated);
		assertEquals(1, fixture.scheduler.pendingCount());
	}

	@Test
	public void releasingFirstSyntheticOwnerKeepsSecondOwnerRepeating() {
		Fixture fixture = new Fixture();
		fixture.repeater.setVirtualSource(1, Canvas.KEY_UP);
		fixture.repeater.setDeviceSource(7, 1, Canvas.KEY_UP);
		fixture.scheduler.advanceBy(400);

		fixture.repeater.setVirtualSource(1, 0);
		fixture.scheduler.advanceBy(80);

		assertEquals(List.of(Canvas.KEY_UP, Canvas.KEY_UP), fixture.repeated);
	}

	@Test
	public void lastSyntheticReleaseStopsRepeatWhilePhysicalOwnershipRemains() {
		Fixture fixture = new Fixture();
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		ownership.setPhysicalKey(1, 19, Canvas.KEY_UP);
		ownership.setVirtualDirection(1, Canvas.KEY_UP);
		fixture.repeater.setVirtualSource(1, Canvas.KEY_UP);
		fixture.scheduler.advanceBy(400);
		assertEquals(1, fixture.repeated.size());

		fixture.repeater.releaseVirtual();
		assertEquals(0, ownership.releaseVirtual().length);
		fixture.scheduler.advanceBy(1000);
		assertEquals(1, fixture.repeated.size());

		assertEquals(
				Canvas.KEY_UP,
				ownership.setPhysicalKey(1, 19, 0).releasedKey);
	}

	@Test
	public void directionTransitionPreservesUnchangedKeyAndStartsNewKeyTiming() {
		Fixture fixture = new Fixture();
		fixture.repeater.setVirtualSource(2, Canvas.KEY_UP);
		fixture.scheduler.advanceBy(300);
		fixture.repeater.setVirtualSource(1, Canvas.KEY_RIGHT);

		fixture.scheduler.advanceBy(100);
		assertEquals(List.of(Canvas.KEY_UP), fixture.repeated);
		fixture.scheduler.advanceBy(300);
		assertEquals(List.of(Canvas.KEY_UP, Canvas.KEY_UP, Canvas.KEY_UP,
				Canvas.KEY_UP, Canvas.KEY_RIGHT), fixture.repeated);

		fixture.repeater.setVirtualSource(2, 0);
		fixture.scheduler.advanceBy(80);
		assertEquals(Canvas.KEY_RIGHT,
				(int) fixture.repeated.get(fixture.repeated.size() - 1));
	}

	@Test
	public void numericSyntheticDirectionRepeatsAndReschedulesOnSectorChange() {
		Fixture fixture = new Fixture();
		fixture.repeater.setVirtualSource(5, Canvas.KEY_NUM3);
		fixture.scheduler.advanceBy(400);
		assertEquals(List.of(Canvas.KEY_NUM3), fixture.repeated);

		fixture.repeater.setVirtualSource(5, Canvas.KEY_NUM6);
		fixture.scheduler.advanceBy(399);
		assertEquals(1, fixture.repeated.size());
		fixture.scheduler.advanceBy(1);
		assertEquals(List.of(Canvas.KEY_NUM3, Canvas.KEY_NUM6), fixture.repeated);
	}

	@Test
	public void releaseDeviceAndReleaseAllCancelPendingRepeats() {
		Fixture fixture = new Fixture();
		fixture.repeater.setDeviceSource(7, 1, Canvas.KEY_UP);
		fixture.repeater.setVirtualSource(2, Canvas.KEY_RIGHT);
		fixture.repeater.releaseDevice(7);
		fixture.scheduler.advanceBy(400);
		assertEquals(List.of(Canvas.KEY_RIGHT), fixture.repeated);

		fixture.repeater.releaseAll();
		fixture.scheduler.advanceBy(1000);
		assertEquals(1, fixture.repeated.size());
		assertEquals(0, fixture.scheduler.pendingCount());
	}

	private static final class Fixture {
		final ManualScheduler scheduler = new ManualScheduler();
		final List<Integer> repeated = new ArrayList<>();
		final SyntheticDirectionalRepeater repeater =
				new SyntheticDirectionalRepeater(scheduler, repeated::add);
	}

	private static final class ManualScheduler implements SyntheticDirectionalRepeater.Scheduler {
		private final List<Entry> entries = new ArrayList<>();
		private long now;

		@Override
		public void postDelayed(Runnable task, long delayMillis) {
			entries.add(new Entry(now + delayMillis, task));
		}

		@Override
		public void removeCallbacks(Runnable task) {
			entries.removeIf(entry -> entry.task == task);
		}

		void advanceBy(long millis) {
			long target = now + millis;
			while (true) {
				Entry next = null;
				for (Entry entry : entries) {
					if (entry.when <= target && (next == null || entry.when < next.when)) {
						next = entry;
					}
				}
				if (next == null) {
					break;
				}
				entries.remove(next);
				now = next.when;
				next.task.run();
			}
			now = target;
		}

		int pendingCount() {
			return entries.size();
		}

		private static final class Entry {
			final long when;
			final Runnable task;

			Entry(long when, Runnable task) {
				this.when = when;
				this.task = task;
			}
		}
	}
}
