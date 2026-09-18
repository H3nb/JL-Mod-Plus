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

/** Tracks which digital input sources currently own each logical guest key. */
final class DigitalKeyOwnership {
	private final LongSparseArray<Integer> sourceTargets = new LongSparseArray<>();

	static long sourceToken(int deviceId, int androidKeyCode) {
		return ((long) deviceId << 32) | (androidKeyCode & 0xffffffffL);
	}

	boolean acquire(long sourceToken, int logicalKey) {
		if (logicalKey == 0 || sourceTargets.indexOfKey(sourceToken) >= 0) {
			return false;
		}
		boolean firstOwner = !hasOwner(logicalKey);
		sourceTargets.put(sourceToken, logicalKey);
		return firstOwner;
	}

	int targetOf(long sourceToken) {
		Integer target = sourceTargets.get(sourceToken);
		return target == null ? 0 : target;
	}

	boolean release(long sourceToken) {
		int index = sourceTargets.indexOfKey(sourceToken);
		if (index < 0) {
			return false;
		}
		int logicalKey = sourceTargets.valueAt(index);
		sourceTargets.removeAt(index);
		return !hasOwner(logicalKey);
	}

	private boolean hasOwner(int logicalKey) {
		for (int i = 0, size = sourceTargets.size(); i < size; i++) {
			if (sourceTargets.valueAt(i) == logicalKey) {
				return true;
			}
		}
		return false;
	}
}
