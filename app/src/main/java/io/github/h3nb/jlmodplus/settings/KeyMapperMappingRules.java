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

package io.github.h3nb.jlmodplus.settings;

import android.util.SparseIntArray;

/** Pure map operations shared by the host boundary and characterization tests. */
public final class KeyMapperMappingRules {
	private KeyMapperMappingRules() {
	}

	public static SparseIntArray assign(
			SparseIntArray current,
			int canvasKey,
			int androidKeyCode) {
		return addBinding(current, androidKeyCode, canvasKey);
	}

	/** Adds or replaces one physical-key binding without removing other inputs for the target. */
	public static SparseIntArray addBinding(
			SparseIntArray current,
			int androidKeyCode,
			int canvasKey) {
		SparseIntArray updated = current == null ? new SparseIntArray() : current.clone();
		updated.put(androidKeyCode, canvasKey);
		return updated;
	}

	/** Resolves persisted overrides on top of defaults. A zero value is an explicit tombstone. */
	public static SparseIntArray resolve(
			SparseIntArray defaults,
			SparseIntArray overrides) {
		SparseIntArray resolved = defaults == null ? new SparseIntArray() : defaults.clone();
		if (overrides == null) return resolved;
		for (int i = 0; i < overrides.size(); i++) {
			int key = overrides.keyAt(i);
			int value = overrides.valueAt(i);
			if (value == 0) resolved.delete(key);
			else resolved.put(key, value);
		}
		return resolved;
	}

	/**
	 * Stores only differences from defaults. Missing keys inherit defaults; value zero explicitly
	 * removes a default binding.
	 */
	public static SparseIntArray diff(
			SparseIntArray defaults,
			SparseIntArray effective) {
		SparseIntArray result = new SparseIntArray();
		SparseIntArray base = defaults == null ? new SparseIntArray() : defaults;
		SparseIntArray current = effective == null ? new SparseIntArray() : effective;
		for (int i = 0; i < base.size(); i++) {
			int key = base.keyAt(i);
			int defaultValue = base.valueAt(i);
			int index = current.indexOfKey(key);
			if (index < 0) result.put(key, 0);
			else if (current.valueAt(index) != defaultValue) result.put(key, current.valueAt(index));
		}
		for (int i = 0; i < current.size(); i++) {
			int key = current.keyAt(i);
			if (base.indexOfKey(key) < 0) result.put(key, current.valueAt(i));
		}
		return result;
	}

	/** Removes exactly one physical-key binding. */
	public static SparseIntArray removeBinding(
			SparseIntArray current,
			int androidKeyCode) {
		SparseIntArray updated = current == null ? new SparseIntArray() : current.clone();
		updated.delete(androidKeyCode);
		return updated;
	}

	/** Removes all bindings for a target only when the caller explicitly requests it. */
	public static SparseIntArray removeBindingsForTarget(
			SparseIntArray current,
			int canvasKey) {
		SparseIntArray updated = current == null ? new SparseIntArray() : current.clone();
		for (int i = updated.size() - 1; i >= 0; i--) {
			if (updated.valueAt(i) == canvasKey) updated.removeAt(i);
		}
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
