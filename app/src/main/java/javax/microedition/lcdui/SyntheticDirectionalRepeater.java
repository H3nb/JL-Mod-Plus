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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Schedules one repeat stream per guest key while synthetic directional owners remain. */
final class SyntheticDirectionalRepeater {
	static final long INITIAL_DELAY_MS = 400L;
	static final long REPEAT_INTERVAL_MS = 80L;

	interface Scheduler {
		void postDelayed(Runnable task, long delayMillis);
		void removeCallbacks(Runnable task);
	}

	interface RepeatSink {
		void repeat(int keyCode);
	}

	private final Scheduler scheduler;
	private final RepeatSink repeatSink;
	private final Map<Long, Integer> deviceTargets = new HashMap<>();
	private final Map<Long, Integer> virtualTargets = new HashMap<>();
	private final Map<Integer, RepeatTask> repeatTasks = new HashMap<>();

	SyntheticDirectionalRepeater(Scheduler scheduler, RepeatSink repeatSink) {
		this.scheduler = scheduler;
		this.repeatSink = repeatSink;
	}

	void setDeviceSource(int deviceId, int sourceId, int keyCode) {
		setSource(deviceTargets, pairToken(deviceId, sourceId), keyCode);
	}

	void setVirtualSource(int sourceId, int keyCode) {
		setSource(virtualTargets, sourceId, keyCode);
	}

	void releaseDevice(int deviceId) {
		Set<Integer> affected = new HashSet<>();
		Iterator<Map.Entry<Long, Integer>> iterator = deviceTargets.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Integer> entry = iterator.next();
			if (deviceIdOf(entry.getKey()) == deviceId) {
				affected.add(entry.getValue());
				iterator.remove();
			}
		}
		stopUnused(affected);
	}

	void releaseVirtual() {
		Set<Integer> affected = new HashSet<>(virtualTargets.values());
		virtualTargets.clear();
		stopUnused(affected);
	}

	void releaseAll() {
		deviceTargets.clear();
		virtualTargets.clear();
		for (RepeatTask task : repeatTasks.values()) {
			scheduler.removeCallbacks(task);
		}
		repeatTasks.clear();
	}

	private void setSource(Map<Long, Integer> targets, long source, int keyCode) {
		Integer previousValue = targets.get(source);
		int previous = previousValue == null ? 0 : previousValue;
		if (previous == keyCode) {
			return;
		}

		if (previous != 0) {
			targets.remove(source);
			if (!hasRepeatOwner(previous)) {
				stop(previous);
			}
		}

		if (keyCode != 0) {
			boolean firstRepeatOwner = !hasRepeatOwner(keyCode);
			targets.put(source, keyCode);
			if (firstRepeatOwner) {
				start(keyCode);
			}
		}
	}

	private void start(int keyCode) {
		RepeatTask task = new RepeatTask(keyCode);
		repeatTasks.put(keyCode, task);
		scheduler.postDelayed(task, INITIAL_DELAY_MS);
	}

	private void stop(int keyCode) {
		RepeatTask task = repeatTasks.remove(keyCode);
		if (task != null) {
			scheduler.removeCallbacks(task);
		}
	}

	private void stopUnused(Set<Integer> affected) {
		for (int keyCode : affected) {
			if (!hasRepeatOwner(keyCode)) {
				stop(keyCode);
			}
		}
	}

	private boolean hasRepeatOwner(int keyCode) {
		return containsTarget(deviceTargets, keyCode) || containsTarget(virtualTargets, keyCode);
	}

	private static boolean containsTarget(Map<Long, Integer> targets, int keyCode) {
		for (int target : targets.values()) {
			if (target == keyCode) {
				return true;
			}
		}
		return false;
	}

	private static long pairToken(int first, int second) {
		return ((long) first << 32) | (second & 0xffffffffL);
	}

	private static int deviceIdOf(long source) {
		return (int) (source >> 32);
	}

	private final class RepeatTask implements Runnable {
		private final int keyCode;

		private RepeatTask(int keyCode) {
			this.keyCode = keyCode;
		}

		@Override
		public void run() {
			if (repeatTasks.get(keyCode) != this || !hasRepeatOwner(keyCode)) {
				return;
			}
			repeatSink.repeat(keyCode);
			scheduler.postDelayed(this, REPEAT_INTERVAL_MS);
		}
	}
}
