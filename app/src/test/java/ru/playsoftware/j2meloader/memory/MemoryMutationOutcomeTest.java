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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemoryMutationOutcomeTest {
	@Test
	public void fullSuccessRemainsOk() {
		assertEquals(MemoryEngineContract.RESULT_OK,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_OK, "3 edited, 0 skipped safely"));
	}

	@Test
	public void mixedEditBecomesPartialWrite() {
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_OK, "2 edited, 1 skipped safely"));
	}

	@Test
	public void allSkippedFailsClosed() {
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_OK, "0 edited, 2 skipped safely"));
	}

	@Test
	public void malformedOrImpossibleSummaryFailsClosed() {
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryMutationOutcome.classifyEditResult(MemoryEngineContract.RESULT_OK, null));
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_OK, "edited successfully"));
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_OK, "33 edited, 0 skipped safely"));
	}

	@Test
	public void existingNativeFailureIsPreserved() {
		assertEquals(MemoryEngineContract.RESULT_CANCELLED,
				MemoryMutationOutcome.classifyEditResult(
						MemoryEngineContract.RESULT_CANCELLED, "1 edited, 0 skipped safely"));
	}
}
