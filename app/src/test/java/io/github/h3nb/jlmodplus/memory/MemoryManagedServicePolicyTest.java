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

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryManagedServicePolicyTest {
	private static final long TOKEN = 41L;

	@Test
	public void lateCancelDoesNotUndoTargetCommit() {
		assertEquals(MemoryEngineContract.RESULT_OK,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN,
						7L, 7L, true, 10L, 11L, 4L, 5L, 4L));
	}

	@Test
	public void lateObserverMayAlreadyHaveAdoptedTheSameCommittedReply() {
		assertEquals(MemoryEngineContract.RESULT_OK,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN,
						7L, 7L, true, 10L, 11L, 4L, 5L, 5L));
	}

	@Test
	public void explicitClearAfterTargetCommitRejectsLateReply() {
		assertEquals(MemoryEngineContract.RESULT_CANCELLED,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN,
						7L, 8L, true, 10L, 10L, 4L, 5L, 0L));
	}

	@Test
	public void runtimeReplacementAndOldBridgeRejectLateReply() {
		assertEquals(MemoryEngineContract.RESULT_TARGET_LOST,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN + 1L,
						7L, 7L, true, 10L, 10L, 4L, 5L, 4L));
		assertEquals(MemoryEngineContract.RESULT_TARGET_LOST,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN,
						7L, 7L, false, 10L, 10L, 4L, 5L, 4L));
		assertEquals(MemoryEngineContract.RESULT_TARGET_LOST,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN + 1L, TOKEN,
						7L, 7L, true, 10L, 10L, 4L, 5L, 4L));
	}

	@Test
	public void staleThirdRevisionRejectsRefinePublication() {
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryManagedServicePolicy.managedReplyDecision(TOKEN, TOKEN, TOKEN,
						7L, 7L, true, 10L, 10L, 4L, 6L, 5L));
	}

	@Test
	public void watchReplyUsesRuntimeProvenanceWithoutDependingOnSearchClear() {
		assertTrue(MemoryManagedServicePolicy.managedReplyFromCurrentRuntime(
				TOKEN, TOKEN, TOKEN, true));
		assertFalse(MemoryManagedServicePolicy.managedReplyFromCurrentRuntime(
				TOKEN, TOKEN, TOKEN + 1L, true));
		assertFalse(MemoryManagedServicePolicy.managedReplyFromCurrentRuntime(
				TOKEN, TOKEN, TOKEN, false));
	}

	@Test
	public void schedulerStopsWhenManagedFreezeSetIsIdle() {
		assertFalse(MemoryManagedServicePolicy.freezeSchedulerIdle(1));
		assertTrue(MemoryManagedServicePolicy.freezeSchedulerIdle(0));
	}
}
