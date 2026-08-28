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

package ru.playsoftware.j2meloader.memory;

/** Stable primitive constants shared by the UI-independent engine IPC and native core. */
public final class MemoryEngineContract {
	public static final int SCOPE_JAVA_FAST = 0;
	public static final int SCOPE_JAVA_THOROUGH = 1;

	public static final int TYPE_AUTO = 0;
	public static final int TYPE_BYTE = 1;
	public static final int TYPE_SHORT = 2;
	public static final int TYPE_CHAR = 3;
	public static final int TYPE_INT = 4;
	public static final int TYPE_LONG = 5;
	public static final int TYPE_FLOAT = 6;
	public static final int TYPE_DOUBLE = 7;

	public static final int PREDICATE_EQUAL = 0;
	public static final int PREDICATE_NOT_EQUAL = 1;
	public static final int PREDICATE_GREATER = 2;
	public static final int PREDICATE_LESS = 3;
	public static final int PREDICATE_GREATER_OR_EQUAL = 4;
	public static final int PREDICATE_LESS_OR_EQUAL = 5;
	public static final int PREDICATE_BETWEEN = 6;
	public static final int PREDICATE_CHANGED = 7;
	public static final int PREDICATE_UNCHANGED = 8;
	public static final int PREDICATE_INCREASED = 9;
	public static final int PREDICATE_DECREASED = 10;
	public static final int PREDICATE_INCREASED_BY = 11;
	public static final int PREDICATE_DECREASED_BY = 12;
	public static final int PREDICATE_CHANGED_BY = 13;
	public static final int PREDICATE_INCREASED_BY_RANGE = 14;
	public static final int PREDICATE_DECREASED_BY_RANGE = 15;

	public static final int COMPARE_PREVIOUS = 0;
	public static final int COMPARE_INITIAL = 1;

	public static final int RESULT_OK = 0;
	public static final int RESULT_CANCELLED = 1;
	public static final int RESULT_INVALID_REQUEST = 2;
	public static final int RESULT_RESOURCE_LIMIT = 3;
	public static final int RESULT_UNSUPPORTED = 4;
	public static final int RESULT_TARGET_LOST = 5;
	public static final int RESULT_NO_SESSION = 6;

	public static final int CANDIDATE_STABLE = 0;
	public static final int CANDIDATE_RELOCATING = 1;
	public static final int CANDIDATE_AMBIGUOUS = 2;
	public static final int CANDIDATE_LOST = 3;

	/** [count, id, address, type, state, initialBits, previousBits, currentBits, ...]. */
	public static final int RESULT_PAGE_STRIDE = 7;
	public static final int MAX_RESULT_PAGE_SIZE = 200;

	public static final String KEY_SUPPORTED = "supported";
	public static final String KEY_RUNTIME_TOKEN = "runtimeToken";
	public static final String KEY_TARGET_PID = "targetPid";
	public static final String KEY_PAGE_SIZE = "pageSize";
	public static final String KEY_MESSAGE = "message";

	private MemoryEngineContract() {
	}

	public static boolean isScope(int scope) {
		return scope == SCOPE_JAVA_FAST || scope == SCOPE_JAVA_THOROUGH;
	}

	public static boolean isValueType(int type) {
		return type >= TYPE_AUTO && type <= TYPE_DOUBLE;
	}

	public static boolean isCandidateType(int type) {
		return type >= TYPE_BYTE && type <= TYPE_DOUBLE;
	}

	static boolean isCompleteRunList(long[] runs) {
		if (runs == null || runs.length < 2 || runs[0] <= 0L || runs[1] != 0L ||
				runs[0] > (runs.length - 2L) / 2L) {
			return false;
		}
		return runs.length == 2 + (int) runs[0] * 2;
	}
}
