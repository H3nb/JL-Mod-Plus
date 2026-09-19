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

import androidx.collection.LongSparseArray;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks guest digital-key ownership across independent physical and virtual source namespaces.
 */
final class DigitalKeyOwnership {
	static final class Transition {
		static final Transition NONE = new Transition(0, 0);

		final int releasedKey;
		final int pressedKey;

		Transition(int releasedKey, int pressedKey) {
			this.releasedKey = releasedKey;
			this.pressedKey = pressedKey;
		}
	}

	private final LongSparseArray<Integer> physicalKeyTargets = new LongSparseArray<>();
	private final LongSparseArray<Integer> deviceDirectionalTargets = new LongSparseArray<>();
	private final LongSparseArray<Integer> virtualKeyTargets = new LongSparseArray<>();
	private final LongSparseArray<Integer> virtualDirectionalTargets = new LongSparseArray<>();

	Transition setPhysicalKey(int deviceId, int androidKeyCode, int logicalKey) {
		return setTarget(physicalKeyTargets, pairToken(deviceId, androidKeyCode), logicalKey);
	}

	int physicalKeyTarget(int deviceId, int androidKeyCode) {
		return targetOf(physicalKeyTargets, pairToken(deviceId, androidKeyCode));
	}

	Transition setDeviceDirection(int deviceId, int sourceId, int logicalKey) {
		return setTarget(deviceDirectionalTargets, pairToken(deviceId, sourceId), logicalKey);
	}

	Transition setVirtualKey(int pointerId, int component, int logicalKey) {
		return setTarget(virtualKeyTargets, pairToken(pointerId, component), logicalKey);
	}

	int virtualKeyTarget(int pointerId, int component) {
		return targetOf(virtualKeyTargets, pairToken(pointerId, component));
	}

	Transition setVirtualDirection(int sourceId, int logicalKey) {
		return setTarget(virtualDirectionalTargets, sourceId, logicalKey);
	}

	int[] releaseDevice(int deviceId) {
		LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
		removeDeviceSources(physicalKeyTargets, deviceId, candidates);
		removeDeviceSources(deviceDirectionalTargets, deviceId, candidates);
		return releasedTargets(candidates);
	}

	int[] releaseVirtual() {
		LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
		collectTargets(virtualKeyTargets, candidates);
		collectTargets(virtualDirectionalTargets, candidates);
		virtualKeyTargets.clear();
		virtualDirectionalTargets.clear();
		return releasedTargets(candidates);
	}

	int[] releaseAll() {
		LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
		collectTargets(physicalKeyTargets, candidates);
		collectTargets(deviceDirectionalTargets, candidates);
		collectTargets(virtualKeyTargets, candidates);
		collectTargets(virtualDirectionalTargets, candidates);
		physicalKeyTargets.clear();
		deviceDirectionalTargets.clear();
		virtualKeyTargets.clear();
		virtualDirectionalTargets.clear();
		int[] released = new int[candidates.size()];
		int index = 0;
		for (int key : candidates) {
			released[index++] = key;
		}
		return released;
	}

	private Transition setTarget(LongSparseArray<Integer> targets, long source, int logicalKey) {
		int oldTarget = targetOf(targets, source);
		if (oldTarget == logicalKey) {
			return Transition.NONE;
		}

		int releasedKey = 0;
		if (oldTarget != 0) {
			targets.remove(source);
			if (!hasOwner(oldTarget)) {
				releasedKey = oldTarget;
			}
		}

		int pressedKey = 0;
		if (logicalKey != 0) {
			boolean firstOwner = !hasOwner(logicalKey);
			targets.put(source, logicalKey);
			if (firstOwner) {
				pressedKey = logicalKey;
			}
		}
		return releasedKey == 0 && pressedKey == 0
				? Transition.NONE : new Transition(releasedKey, pressedKey);
	}

	private int targetOf(LongSparseArray<Integer> targets, long source) {
		Integer target = targets.get(source);
		return target == null ? 0 : target;
	}

	private boolean hasOwner(int logicalKey) {
		return containsTarget(physicalKeyTargets, logicalKey)
				|| containsTarget(deviceDirectionalTargets, logicalKey)
				|| containsTarget(virtualKeyTargets, logicalKey)
				|| containsTarget(virtualDirectionalTargets, logicalKey);
	}

	private static boolean containsTarget(LongSparseArray<Integer> targets, int logicalKey) {
		for (int i = 0, size = targets.size(); i < size; i++) {
			if (targets.valueAt(i) == logicalKey) {
				return true;
			}
		}
		return false;
	}

	private static void collectTargets(
			LongSparseArray<Integer> targets,
			Set<Integer> candidates) {
		for (int i = 0, size = targets.size(); i < size; i++) {
			int target = targets.valueAt(i);
			if (target != 0) {
				candidates.add(target);
			}
		}
	}

	private static void removeDeviceSources(
			LongSparseArray<Integer> targets,
			int deviceId,
			Set<Integer> candidates) {
		for (int i = targets.size() - 1; i >= 0; i--) {
			long source = targets.keyAt(i);
			if ((int) (source >> 32) == deviceId) {
				int target = targets.valueAt(i);
				if (target != 0) {
					candidates.add(target);
				}
				targets.removeAt(i);
			}
		}
	}

	private int[] releasedTargets(Set<Integer> candidates) {
		int count = 0;
		for (int target : candidates) {
			if (!hasOwner(target)) {
				count++;
			}
		}
		int[] released = new int[count];
		int index = 0;
		for (int target : candidates) {
			if (!hasOwner(target)) {
				released[index++] = target;
			}
		}
		return released;
	}

	private static long pairToken(int first, int second) {
		return ((long) first << 32) | (second & 0xffffffffL);
	}
}
