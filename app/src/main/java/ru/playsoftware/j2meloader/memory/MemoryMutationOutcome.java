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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies the private native edit summary without exposing it as a public IPC contract. */
final class MemoryMutationOutcome {
	private static final Pattern EDIT_SUMMARY =
			Pattern.compile("^(\\d+) edited, (\\d+) skipped safely$");

	private MemoryMutationOutcome() {
	}

	static int classifyEditResult(int nativeResult, String message) {
		if (nativeResult != MemoryEngineContract.RESULT_OK) return nativeResult;
		if (message == null) return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		Matcher matcher = EDIT_SUMMARY.matcher(message);
		if (!matcher.matches()) return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		try {
			long edited = Long.parseLong(matcher.group(1));
			long skipped = Long.parseLong(matcher.group(2));
			long total = edited + skipped;
			if (edited < 0L || skipped < 0L || total <= 0L || total > MemoryEngineContract.MAX_MULTI_WRITE) {
				return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
			}
			if (skipped == 0L) return MemoryEngineContract.RESULT_OK;
			return edited == 0L
					? MemoryEngineContract.RESULT_IDENTITY_UNSAFE
					: MemoryEngineContract.RESULT_PARTIAL_WRITE;
		} catch (NumberFormatException exception) {
			return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}
	}
}
