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

/** Pure acceptance rules shared by managed Binder reply publication and the freeze scheduler. */
final class MemoryManagedServicePolicy {
	private MemoryManagedServicePolicy() {
	}

	static boolean managedReplyFromCurrentRuntime(long requestToken, long replyToken,
	                                              long runtimeTokenAfterRpc, boolean sameBridge) {
		return requestToken != 0L && replyToken == requestToken
				&& runtimeTokenAfterRpc == requestToken && sameBridge;
	}

	/**
	 * Decides whether search metadata from a completed target RPC may still be published.
	 *
	 * <p>The cancellation epochs are intentionally not compared once the target already returned a
	 * committed success. A Cancel that arrives after that target commit is too late and is not Undo.
	 * Explicit Clear has its own generation, runtime replacement is guarded by reply provenance,
	 * and refine publication remains tied to its snapshotted revision.</p>
	 */
	static int managedReplyDecision(long requestToken, long replyToken, long runtimeTokenAfterRpc,
	                                long expectedClearGeneration, long currentClearGeneration,
	                                boolean sameBridge, long operationCancelEpoch,
	                                long currentCancelEpoch, long expectedRevision,
	                                long replyRevision, long currentRevision) {
		if (!managedReplyFromCurrentRuntime(requestToken, replyToken, runtimeTokenAfterRpc,
				sameBridge)) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}
		if (expectedClearGeneration != currentClearGeneration) {
			return MemoryEngineContract.RESULT_CANCELLED;
		}
		if (expectedRevision > 0L && currentRevision != expectedRevision
				&& currentRevision != replyRevision) {
			return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}
		// Deliberately ignore operationCancelEpoch/currentCancelEpoch here. Target commit is the
		// cancellation linearization point; only Clear/runtime/revision invalidation can reject it.
		return MemoryEngineContract.RESULT_OK;
	}

	static boolean freezeSchedulerIdle(int rawFreezeCount, int managedFreezeCount) {
		return rawFreezeCount <= 0 && managedFreezeCount <= 0;
	}
}
