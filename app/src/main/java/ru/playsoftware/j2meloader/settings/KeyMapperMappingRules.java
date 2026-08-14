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

package ru.playsoftware.j2meloader.settings;

import android.util.SparseIntArray;

/** Pure map operations shared by the host boundary and characterization tests. */
public final class KeyMapperMappingRules {
	private KeyMapperMappingRules() {
	}

	public static SparseIntArray assign(
			SparseIntArray current,
			int canvasKey,
			int androidKeyCode) {
		SparseIntArray updated = current == null ? new SparseIntArray() : current.clone();
		for (int i = updated.size() - 1; i >= 0; i--) {
			if (updated.valueAt(i) == canvasKey) {
				updated.removeAt(i);
			}
		}
		updated.put(androidKeyCode, canvasKey);
		return updated;
	}

	public static boolean containsValue(SparseIntArray map, int value) {
		return map != null && map.indexOfValue(value) >= 0;
	}

	public static boolean equalMaps(SparseIntArray first, SparseIntArray second) {
		if (first == second) {
			return true;
		}
		if (first == null || second == null || first.size() != second.size()) {
			return false;
		}
		for (int i = 0; i < first.size(); i++) {
			if (first.keyAt(i) != second.keyAt(i)
					|| first.valueAt(i) != second.valueAt(i)) {
				return false;
			}
		}
		return true;
	}
}
