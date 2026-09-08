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

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;

/** Owns all scan state in a dedicated app process and exposes only logical candidate IDs. */
public final class MemoryEngineService extends Service {
	public interface LocalRuntimeListener {
		void onTargetUnavailable();
	}

	private static final Set<LocalRuntimeListener> LOCAL_RUNTIME_LISTENERS =
			Collections.newSetFromMap(new ConcurrentHashMap<LocalRuntimeListener, Boolean>());
	private static final long PROGRESS_UPDATE_PERIOD_MS = 200L;
	// Native cancellation uses this generation to distinguish a newly started operation from a
	// cancellation delivered by Binder immediately before its native entry point.
	private static final AtomicLong cancelEpoch = new AtomicLong();

	private final AtomicLong nextOperationId = new AtomicLong(1L);
	private final RemoteCallbackList<IMemoryEngineCallback> callbacks = new RemoteCallbackList<>();
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MemoryEditorEngine");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final ScheduledExecutorService progressNotifier =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "MemoryEditorProgress");
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
	private volatile IMemoryTargetBridge target;
	private volatile boolean targetBound;
	private volatile long configuredToken;
	private volatile long observedRuntimeToken;
	private volatile int configuredScope = MemoryEngineContract.SCOPE_JAVA_FAST;
	private volatile int lastRawScope = MemoryEngineContract.SCOPE_JAVA_FAST;
	private volatile int searchBackend = MemoryEngineContract.BACKEND_RAW;
	private volatile boolean managedSupported;
	private volatile boolean managedWriteSupported;
	private volatile long managedStateToken;
	private volatile long managedRevision;
	private volatile long managedResultCount;
	private volatile long managedBaselineCount;
	private volatile int managedWatchCount;
	private volatile int managedFreezeCount;
	private volatile String managedLastMessage;
	private volatile boolean nativeSearchClearPending;
	private final AtomicLong nativeSearchClearGeneration = new AtomicLong();
	private final Map<Long, String> watchLabels = new ConcurrentHashMap<>();
	private final Map<Long, FreezeRecord> freezeRecords = new ConcurrentHashMap<>();
	private final MemoryGcBindingTracker gcBindings = new MemoryGcBindingTracker();
	private final MemoryCandidateBindingCache bindingCache = new MemoryCandidateBindingCache();
	private volatile ScheduledFuture<?> freezeTask;
	/** Serializes only the tiny local backend-publication boundary. */
	private final Object searchCommitLock = new Object();
	private final Object searchSessionLock = new Object();
	private final ArrayDeque<Integer> searchStageHistory = new ArrayDeque<>();
	private final ArrayDeque<Long> searchGcHistory = new ArrayDeque<>();
	private int searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
	private int searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
	private int searchRequestedType = MemoryEngineContract.TYPE_AUTO;
	private int searchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
	private final IMemoryTargetCallback targetCallback = new IMemoryTargetCallback.Stub() {
		@Override
		public void onRuntimeEnded(long runtimeToken) {
			if (runtimeToken != 0L &&
					(runtimeToken == configuredToken || runtimeToken == observedRuntimeToken)) {
				invalidateTarget();
			}
		}
	};

	private final ServiceConnection targetConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			IMemoryTargetBridge bridge = IMemoryTargetBridge.Stub.asInterface(service);
			target = bridge;
			try {
				bridge.registerTargetCallback(targetCallback);
			} catch (RemoteException exception) {
				target = null;
				invalidateTarget();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			target = null;
			invalidateTarget();
		}

		@Override
		public void onBindingDied(ComponentName name) {
			target = null;
			invalidateTarget();
		}
	};

	private final IMemoryEngineService.Stub binder = new IMemoryEngineService.Stub() {
		@Override
		public Bundle getCapabilities() {
			Bundle result = new Bundle();
			IMemoryTargetBridge bridge = target;
			if (bridge == null) {
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, false);
				result.putInt(MemoryEngineContract.KEY_SEARCH_BACKEND,
						MemoryEngineContract.BACKEND_RAW);
				result.putLong(MemoryEngineContract.KEY_GC_COUNT,
						MemoryEngineContract.GC_COUNT_UNKNOWN);
				result.putString(MemoryEngineContract.KEY_MESSAGE, "MIDlet runtime is not connected");
				return result;
			}
			try {
				long token = bridge.getRuntimeToken();
				observedRuntimeToken = token;
				int pid = bridge.getTargetPid();
				int pageSize = bridge.getPageSize();
				long gcCount = token == 0L
						? MemoryEngineContract.GC_COUNT_UNKNOWN : bridge.getGcCount(token);
				long[] probe = token == 0L ? null : bridge.getReadProbe(token);
				Bundle managed = token == 0L ? null : bridge.getManagedCapabilities(token);
				if (managed != null && acceptManagedRuntimeState(token, bridge, managed)) {
					updateManagedState(token, managed);
				} else {
					managed = null;
				}
				boolean managedAvailable = token != 0L && managedSupported;
				boolean managedWriteAvailable = token != 0L && managedWriteSupported;
				if (token != 0L && configuredToken == 0L && managedAvailable
						&& (managedRevision > 0L || managedWatchCount > 0)) {
					// A freshly reconnected :memory_engine process may have no local session metadata.
					// Adopt only target-owned managed state; raw state still requires explicit native
					// configuration, and a watch-only target remains a raw/empty search session.
					configuredToken = token;
					configuredScope = MemoryEngineContract.SCOPE_MANAGED_JAVA;
					if (managedRevision > 0L) searchBackend = MemoryEngineContract.BACKEND_MANAGED;
				}
				boolean rawSupported = token != 0L && pid > 0 && pageSize > 0 &&
						canReadProbe(pid, probe);
				boolean rawWriteSupported = rawSupported && canWriteProbe(pid, probe);
				boolean supported = rawSupported || managedAvailable;
				boolean writeSupported = rawWriteSupported || managedWriteAvailable;
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, supported);
				result.putBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, writeSupported);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, managedAvailable);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED,
						managedWriteAvailable);
				long visibleManagedRevision = token != 0L && configuredToken == token
						&& searchBackend == MemoryEngineContract.BACKEND_MANAGED
						? managedRevision : 0L;
				long visibleManagedResultCount = token != 0L && configuredToken == token
						&& searchBackend == MemoryEngineContract.BACKEND_MANAGED
						? managedResultCount : 0L;
				result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, visibleManagedRevision);
				result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT,
						visibleManagedResultCount);
				result.putLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT,
						token != 0L && configuredToken == token
								&& searchBackend == MemoryEngineContract.BACKEND_MANAGED
								? managedBaselineCount : 0L);
				result.putInt(MemoryEngineContract.KEY_SEARCH_BACKEND, searchBackend);
				result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, token);
				result.putInt(MemoryEngineContract.KEY_TARGET_PID, pid);
				result.putInt(MemoryEngineContract.KEY_PAGE_SIZE, pageSize);
				result.putLong(MemoryEngineContract.KEY_GC_COUNT, gcCount);
				if (!supported) {
					result.putString(MemoryEngineContract.KEY_MESSAGE, token == 0L
							? "No active MIDlet runtime"
							: "Cross-process memory reads are not supported by this device/runtime");
				}
			} catch (RemoteException exception) {
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, false);
				result.putLong(MemoryEngineContract.KEY_GC_COUNT,
						MemoryEngineContract.GC_COUNT_UNKNOWN);
				result.putString(MemoryEngineContract.KEY_MESSAGE, "MIDlet runtime connection was lost");
			}
			return result;
		}

		@Override
		public void registerCallback(IMemoryEngineCallback callback) {
			if (callback != null) {
				callbacks.register(callback);
			}
		}

		@Override
		public void unregisterCallback(IMemoryEngineCallback callback) {
			if (callback != null) {
				callbacks.unregister(callback);
			}
		}

		@Override
		public long startKnownSearch(long token, int scope, int type, int predicate,
		                             String first, String second) {
			if (scope == MemoryEngineContract.SCOPE_MANAGED_JAVA) {
				return enqueueManagedSearch(token, () -> managedStartExact(token, type, predicate,
						first, second));
			}
			return enqueueSearch(token, true, scope, () -> runNewSearchWithGcGuard(
					token,
					() -> NativeMemoryEngine.startKnown(type, predicate, first, second),
					MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
					MemoryEngineContract.SEARCH_MODE_KNOWN,
					type,
					scope));
		}

		@Override
		public long startUnknownSearch(long token, int scope, int type) {
			if (scope == MemoryEngineContract.SCOPE_MANAGED_JAVA) {
				return enqueueManagedSearch(token, () -> managedStartUnknown(token, type));
			}
			return enqueueSearch(token, true, scope, () -> runNewSearchWithGcGuard(
					token,
					() -> NativeMemoryEngine.startUnknown(type),
					MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
					MemoryEngineContract.SEARCH_MODE_UNKNOWN,
					type,
					scope));
		}

		@Override
		public long startGroupSearch(long token, int scope, int[] types,
		                             String[] values, int maxDistance) {
			if (scope == MemoryEngineContract.SCOPE_MANAGED_JAVA) {
				return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
			}
			return enqueueSearch(token, true, scope, () -> runNewSearchWithGcGuard(
					token,
					() -> NativeMemoryEngine.startGroup(types, values, maxDistance),
					MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
					MemoryEngineContract.SEARCH_MODE_GROUP,
					MemoryEngineContract.TYPE_AUTO,
					scope));
		}

		@Override
		public long startNearbySearch(long token, long anchorCandidateId, int radius,
		                              int type, int predicate, String first, String second) {
			if (ManagedJavaMemoryIds.isManaged(anchorCandidateId)) {
				return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
			}
			return enqueueSearch(token, false, 0, () -> {
				if (anchorCandidateId <= 0L || !MemoryEngineContract.isNearbyRadius(radius)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				long[] anchorIds = new long[]{anchorCandidateId};
				int ready = refreshWithRecovery(token, anchorIds);
				if (ready != MemoryEngineContract.RESULT_OK) {
					return ready;
				}
				refreshCachedBindings(anchorIds);
				return runNewSearchWithGcGuard(
						token,
						() -> NativeMemoryEngine.startNearby(anchorCandidateId, radius, type, predicate,
								first, second),
						MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
						MemoryEngineContract.SEARCH_MODE_KNOWN,
						type,
						configuredScope);
			});
		}

		@Override
		public long refineKnown(long token, int valueType, int predicate, String first, String second) {
			if (isManagedCurrent(token)) {
				return enqueueManagedSearch(token, () -> managedRefine(token, managedRevision,
						valueType, predicate, MemoryEngineContract.COMPARE_PREVIOUS, first, second));
			}
			return enqueueSearch(token, false, 0,
					() -> refineKnownAddressSet(token, valueType, predicate, first, second));
		}

		@Override
		public long refineRelative(long token, int valueType, int predicate, int compareTarget,
		                           String first, String second) {
			if (isManagedCurrent(token)) {
				return enqueueManagedSearch(token, () -> managedRefine(token, managedRevision,
						valueType, predicate, compareTarget, first, second));
			}
			return enqueueSearch(token, false, 0,
					() -> refineRelativeGcAware(token, valueType, predicate, compareTarget, first, second));
		}

		@Override
		public long refineManagedInt(long token, long expectedRevision, int predicate,
		                             int compareTarget, String value) {
			return enqueueManagedSearch(token, () -> managedRefine(token, expectedRevision,
					MemoryEngineContract.TYPE_INT, predicate,
					compareTarget, value, ""));
		}

		@Override
		public long undoSearch(long token) {
			if (isManagedCurrent(token)) {
				return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
			}
			return enqueue(token, false, 0, () -> {
				synchronizeSearchHistoryDepth(NativeMemoryEngine.historyDepth());
				int result = NativeMemoryEngine.undo();
				if (result == MemoryEngineContract.RESULT_OK) undoSearchSession();
				return result;
			});
		}

		@Override
		public long refreshCandidates(long token, long[] candidateIds, boolean passiveRefresh) {
			long[] ids = candidateIds == null ? new long[0] : candidateIds;
			if (containsManagedIds(ids)) {
				if (!allManagedIds(ids)) return enqueueMixed(token, passiveRefresh,
						() -> refreshMixed(token, ids, passiveRefresh));
				return enqueueManaged(token, false, () -> managedRefresh(token, ids, passiveRefresh));
			}
			return enqueue(token, false, 0, passiveRefresh,
					() -> passiveRefresh
							? refreshVisibleCandidates(ids)
							: refreshForRead(token, ids));
		}

		@Override
		public long removeCandidates(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false,
						() -> managedFilterResultGroups(token, managedRevision, candidateIds, false));
			}
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.filter(candidateIds, false);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							gcBindings.searchEpoch());
				}
				return result;
			});
		}

		@Override
		public long keepCandidates(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false,
						() -> managedFilterResultGroups(token, managedRevision, candidateIds, true));
			}
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.filter(candidateIds, true);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							gcBindings.searchEpoch());
				}
				return result;
			});
		}

		@Override
		public long editCandidates(long token, long[] candidateIds, int valueType,
		                           String replacementValue) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueMixed(token, false,
						() -> editMixed(token, candidateIds, valueType, replacementValue));
				return enqueueManaged(token, false, () -> managedEdit(token,
						isManagedCurrent(token) ? managedRevision : 0L, candidateIds,
						valueType, replacementValue, true));
			}
			return enqueue(token, false, 0, () -> {
				if (candidateIds == null || candidateIds.length == 0 ||
						candidateIds.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
					return MemoryEngineContract.RESULT_SAFETY_LIMIT;
				}
				if (!MemoryEngineContract.isCandidateType(valueType)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				int typeCheck = validateRawWatchTypes(candidateIds, valueType);
				if (typeCheck != MemoryEngineContract.RESULT_OK) return typeCheck;
				return editRawAuxiliary(token, candidateIds, replacementValue);
			});
		}

		@Override
		public long getResultCount(long token) {
			if (isManagedCurrent(token)) return managedResultCount;
			return isCurrentToken(token) && !nativeSearchClearPending
					? NativeMemoryEngine.resultCount() : 0L;
		}

		@Override
		public Bundle getSearchSessionInfo(long token) {
			return searchSessionInfo(token);
		}

		@Override
		public Bundle getResultPage(long token, int offset, int limit) {
			if (!isCurrentToken(token) || offset < 0 || limit <= 0 ||
					limit > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
				return emptyResultPage();
			}
			if (nativeSearchClearPending) return emptyResultPage();
			if (isManagedCurrent(token)) {
				return managedResultPage(token, managedRevision, offset, limit);
			}
			long[] rows = NativeMemoryEngine.resultPage(offset, limit);
			bindingCache.recordPage(rows, false, offset);
			return formatResultPage(rows);
		}

		@Override
		public long filterResultGroups(long token, long expectedRevision, long[] resultIds, boolean keep) {
			if (resultIds == null || resultIds.length == 0
					|| resultIds.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
				return enqueue(token, false, 0, () -> MemoryEngineContract.RESULT_SAFETY_LIMIT);
			}
			if (containsManagedIds(resultIds)) {
				if (!allManagedIds(resultIds)) {
					return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				}
				return enqueueManaged(token, false,
						() -> managedFilterResultGroups(token, expectedRevision, resultIds, keep));
			}
			return enqueue(token, false, 0, () -> {
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds,
						MemoryEngineContract.TYPE_AUTO);
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				int result = NativeMemoryEngine.filter(candidateIds, keep);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							gcBindings.searchEpoch());
				}
				return result;
			});
		}

		@Override
		public long editResultGroups(long token, long expectedRevision, long[] resultIds, int valueType,
		                             String replacementValue) {
			if (containsManagedIds(resultIds)) {
				if (!MemoryEngineContract.isCandidateType(valueType)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_UNSUPPORTED);
				if (!allManagedIds(resultIds)) return enqueueMixed(token, false,
						() -> editResultGroupsMixed(token, expectedRevision, resultIds, valueType, replacementValue));
				return enqueueManaged(token, false, () -> managedEdit(token, expectedRevision,
						resultIds, valueType, replacementValue, false));
			}
			return enqueue(token, false, 0, () -> {
				if (!MemoryEngineContract.isCandidateType(valueType)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds, valueType);
				if (candidateIds == null || candidateIds.length == 0 ||
						candidateIds.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
					return MemoryEngineContract.RESULT_SAFETY_LIMIT;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				return editRawAuxiliary(token, candidateIds, replacementValue);
			});
		}

		@Override
		public long editManagedResultGroups(long token, long expectedRevision, long[] resultIds,
		                                    int valueType, String replacementValue) {
			return enqueueManaged(token, false, () -> managedEdit(token, expectedRevision,
					resultIds, valueType, replacementValue, false));
		}

		@Override
		public long addWatchResultGroups(long token, long[] resultIds, int valueType) {
			if (containsManagedIds(resultIds)) {
				if (!MemoryEngineContract.isCandidateType(valueType)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_UNSUPPORTED);
				if (!allManagedIds(resultIds)) return enqueueMixed(token, false,
						() -> addWatchResultGroupsMixed(token, resultIds, valueType));
				return enqueueManaged(token, false, () -> managedAddWatch(token, managedRevision,
						resultIds));
			}
			return enqueue(token, false, 0, () -> {
				if (!MemoryEngineContract.isCandidateType(valueType)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds, valueType);
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (!globalWatchCapacityAvailable(token, candidateIds)) {
					return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
				}
				int ready = refreshWithRecovery(token, candidateIds);
				if (ready != MemoryEngineContract.RESULT_OK) return ready;
				refreshCachedBindings(candidateIds);
				int result = NativeMemoryEngine.pin(candidateIds, true);
				if (result == MemoryEngineContract.RESULT_OK) {
					gcBindings.markCandidatesValidated(candidateIds, currentGcCount(token));
				}
				return result;
			});
		}

		@Override
		public long addManagedWatchResultGroups(long token, long expectedRevision, long[] resultIds) {
			return enqueueManaged(token, false, () -> managedAddWatch(token, expectedRevision,
					resultIds));
		}

		@Override
		public long setFreezeResultGroups(long token, long[] resultIds, int valueType, int mode,
				String firstValue, String secondValue) {
			if (containsManagedIds(resultIds)) {
				if (!MemoryEngineContract.isCandidateType(valueType)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_UNSUPPORTED);
				if (!allManagedIds(resultIds)) return enqueueMixed(token, false,
						() -> setFreezeResultGroupsMixed(token, resultIds, valueType, mode,
								firstValue, secondValue));
				return enqueueManaged(token, false, () -> managedFreeze(token, managedRevision,
						resultIds, mode, firstValue, secondValue, false));
			}
			return enqueue(token, false, 0, () -> {
				if (!MemoryEngineContract.isCandidateType(valueType)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds, valueType);
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				return setFreezeRecords(token, candidateIds, mode, firstValue, secondValue);
			});
		}

		@Override
		public long setManagedFreezeResultGroups(long token, long expectedRevision, long[] resultIds,
		                                        int mode, String firstValue, String secondValue) {
			return enqueueManaged(token, false, () -> managedFreeze(token, expectedRevision,
					resultIds, mode, firstValue, secondValue, false));
		}

		@Override
		public long editInspectorValue(long token, long anchorCandidateId, int relativeOffset,
		                               int valueType, long expectedBits,
		                               String replacementValue, boolean watchAnchor) {
			if (ManagedJavaMemoryIds.isManaged(anchorCandidateId)) {
				return enqueueManaged(token, false, () -> managedEditInspector(token,
						anchorCandidateId, watchAnchor, relativeOffset, valueType, expectedBits,
						replacementValue));
			}
			return enqueue(token, false, 0, () -> {
				if (anchorCandidateId <= 0L || !MemoryEngineContract.isCandidateType(valueType) ||
						Math.abs((long) relativeOffset) > MemoryEngineContract.MAX_INSPECT_RADIUS) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				long[] anchorIds = new long[]{anchorCandidateId};
				int result = performGuardedMutation(token, anchorIds,
						() -> NativeMemoryEngine.editInspectorValue(
								anchorCandidateId, relativeOffset, valueType, expectedBits,
								replacementValue));
				if (result == MemoryEngineContract.RESULT_OK) {
					NativeMemoryEngine.refresh(anchorIds, false);
				}
				return result;
			});
		}

		@Override
		public Bundle inspectCandidate(long token, long candidateId, int radius, boolean watchAnchor) {
			if (ManagedJavaMemoryIds.isManaged(candidateId)) {
				return inspectManagedCandidate(token, candidateId, radius, watchAnchor);
			}
			if (candidateId <= 0L || !MemoryEngineContract.isInspectRadius(radius)) {
				return inspectionFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Inspector requires a valid CandidateId and bounded radius");
			}
			long operationEpoch = cancelEpoch.get();
			try {
				return worker.submit(() -> {
					if (!NativeMemoryEngine.prepareOperation(operationEpoch)) {
						return inspectionFailure(MemoryEngineContract.RESULT_CANCELLED,
								"Inspector request was cancelled before it started");
					}
					return inspectCandidateOnWorker(token, candidateId, radius);
				}).get();
			} catch (RejectedExecutionException exception) {
				return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
						"Memory engine is shutting down");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return inspectionFailure(MemoryEngineContract.RESULT_CANCELLED,
						"Inspector request was interrupted");
			} catch (ExecutionException exception) {
				return inspectionFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Inspector request failed safely");
			}
		}

		@Override
		public Bundle getWatchPage(long token) {
			if (!isCurrentToken(token)) {
				return emptyWatchPage();
			}
			long[] rows = NativeMemoryEngine.watchPage();
			bindingCache.recordPage(rows, true, 0);
			Bundle raw = formatWatchPage(rows);
			return mergeWatchPages(raw, managedWatchPage(token));
		}

		@Override
		public long addWatch(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueMixed(token, false,
						() -> addWatchMixed(token, candidateIds));
				return enqueueManaged(token, false, () -> managedAddWatch(token, managedRevision,
						candidateIds));
			}
			return enqueue(token, false, 0, () -> {
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (!globalWatchCapacityAvailable(token, candidateIds)) {
					return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
				}
				int ready = refreshWithRecovery(token, candidateIds);
				if (ready != MemoryEngineContract.RESULT_OK) return ready;
				refreshCachedBindings(candidateIds);
				int result = NativeMemoryEngine.pin(candidateIds, true);
				if (result == MemoryEngineContract.RESULT_OK) {
					gcBindings.markCandidatesValidated(candidateIds, currentGcCount(token));
				}
				return result;
			});
		}

		@Override
		public long removeWatch(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueMixed(token, false,
						() -> removeWatchMixed(token, candidateIds));
				return enqueueManaged(token, false, () -> managedRemoveWatch(token, candidateIds));
			}
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.pin(candidateIds, false);
				if (result == MemoryEngineContract.RESULT_OK && candidateIds != null) {
					for (long id : candidateIds) {
						watchLabels.remove(id);
						freezeRecords.remove(id);
						gcBindings.forgetCandidate(id);
					}
				}
				stopFreezeTaskIfIdle();
				return result;
			});
		}

		@Override
		public long setWatchLabel(long token, long candidateId, String label) {
			if (ManagedJavaMemoryIds.isManaged(candidateId)) {
				return enqueueManaged(token, false, () -> managedSetWatchLabel(token, candidateId, label));
			}
			return enqueue(token, false, 0, () -> {
				if (candidateId <= 0L || label == null || label.length() > 64 ||
						!isWatchedCandidate(candidateId)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (label.isBlank()) {
					watchLabels.remove(candidateId);
				} else {
					watchLabels.put(candidateId, label.trim());
				}
				return MemoryEngineContract.RESULT_OK;
			});
		}

		@Override
		public long setFreeze(long token, long[] candidateIds, int mode,
		                      String firstValue, String secondValue) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueMixed(token, false,
						() -> setFreezeMixed(token, candidateIds, mode, firstValue, secondValue));
				return enqueueManaged(token, false, () -> managedFreeze(token,
						isManagedCurrent(token) ? managedRevision : 0L, candidateIds, mode,
						firstValue, secondValue, true));
			}
			return enqueue(token, false, 0, () -> setFreezeRecords(
					token, candidateIds, mode, firstValue, secondValue));
		}

		@Override
		public long clearFreeze(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueMixed(token, false,
						() -> clearFreezeMixed(token, candidateIds));
				return enqueueManaged(token, false, () -> managedClearFreeze(token, candidateIds));
			}
			return enqueue(token, false, 0, () -> {
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				for (long id : candidateIds) {
					if (!freezeRecords.containsKey(id)) {
						return MemoryEngineContract.RESULT_INVALID_REQUEST;
					}
				}
				for (long id : candidateIds) {
					freezeRecords.remove(id);
				}
				stopFreezeTaskIfIdle();
				return MemoryEngineContract.RESULT_OK;
			});
		}

		@Override
		public void clearSearch(long token) {
			if (token == 0L || !isTargetToken(token)) return;
			long epoch;
			long clearGeneration;
			IMemoryTargetBridge bridge;
			synchronized (searchCommitLock) {
				clearSearchSession();
				epoch = cancelEpoch.incrementAndGet();
				clearGeneration = nativeSearchClearGeneration.incrementAndGet();
				searchBackend = MemoryEngineContract.BACKEND_RAW;
					managedRevision = 0L;
					managedResultCount = 0L;
					managedBaselineCount = 0L;
				nativeSearchClearPending = true;
				bridge = target;
			}
			NativeMemoryEngine.cancel(epoch);
			if (bridge != null) {
				try {
					bridge.managedClearSearch(token, 0L, epoch);
				} catch (RemoteException ignored) {
					// Runtime teardown will clear target state.
				}
			}
			try {
				worker.execute(() -> {
					try {
						NativeMemoryEngine.clearSearch();
					} finally {
						if (nativeSearchClearGeneration.get() == clearGeneration) {
							nativeSearchClearPending = false;
						}
					}
				});
			} catch (RejectedExecutionException ignored) {
				if (nativeSearchClearGeneration.get() == clearGeneration) {
					nativeSearchClearPending = false;
				}
			}
		}

		@Override
		public void cancelOperation(long token) {
			if (token == 0L || (token != configuredToken && !isTargetToken(token))) return;
			synchronizeManagedCancelEpoch(token);
			long epoch = cancelEpoch.incrementAndGet();
			IMemoryTargetBridge bridge = target;
			if (bridge != null) {
				try {
					bridge.cancelManaged(token, epoch);
				} catch (RemoteException ignored) {
					// Runtime teardown handles cancellation.
				}
			}
			if (!(searchBackend == MemoryEngineContract.BACKEND_MANAGED && configuredToken == token)) {
				NativeMemoryEngine.cancel(epoch);
			}
		}
	};

	static void addLocalRuntimeListener(LocalRuntimeListener listener) {
		if (listener != null) LOCAL_RUNTIME_LISTENERS.add(listener);
	}

	static void removeLocalRuntimeListener(LocalRuntimeListener listener) {
		if (listener != null) LOCAL_RUNTIME_LISTENERS.remove(listener);
	}

	private static void notifyLocalRuntimeUnavailable() {
		for (LocalRuntimeListener listener : LOCAL_RUNTIME_LISTENERS) {
			try {
				listener.onTargetUnavailable();
			} catch (RuntimeException ignored) {
				// Activity teardown must not interrupt engine cleanup.
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Intent intent = new Intent(this, MemoryTargetBridgeService.class);
		targetBound = bindService(intent, targetConnection, Context.BIND_AUTO_CREATE);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		ScheduledFuture<?> activeFreezeTask = freezeTask;
		if (activeFreezeTask != null) {
			activeFreezeTask.cancel(false);
		}
		IMemoryTargetBridge bridge = target;
		if (bridge != null) {
			try {
				bridge.unregisterTargetCallback(targetCallback);
			} catch (RemoteException ignored) {
				// The target may already be gone.
			}
		}
		NativeMemoryEngine.cancel(cancelEpoch.incrementAndGet());
		worker.shutdownNow();
		progressNotifier.shutdownNow();
		callbacks.kill();
		LOCAL_RUNTIME_LISTENERS.clear();
		if (targetBound) {
			unbindService(targetConnection);
			targetBound = false;
		}
		NativeMemoryEngine.clearTarget();
		super.onDestroy();
	}

	private long enqueue(long token, boolean configure, int scope, NativeOperation operation) {
		return enqueue(token, configure, scope, false, false, operation);
	}

	private long enqueue(long token, boolean configure, int scope, boolean passiveRefresh,
	                     NativeOperation operation) {
		return enqueue(token, configure, scope, passiveRefresh, false, operation);
	}

	private long enqueueSearch(long token, boolean configure, int scope, NativeOperation operation) {
		return enqueue(token, configure, scope, false, true, operation);
	}

	private long enqueueManagedSearch(long token, NativeOperation operation) {
		return enqueueManaged(token, true, operation);
	}

	private long enqueueManaged(long token, boolean searchOperation, NativeOperation operation) {
		return enqueue(token, false, MemoryEngineContract.SCOPE_MANAGED_JAVA, false,
				searchOperation, true, operation);
	}

	private long enqueueMixed(long token, boolean passiveRefresh, NativeOperation operation) {
		return enqueue(token, false, MemoryEngineContract.SCOPE_MANAGED_JAVA, passiveRefresh,
				false, true, operation);
	}

	private long enqueue(long token, boolean configure, int scope, boolean passiveRefresh,
	                     boolean searchOperation, NativeOperation operation) {
		return enqueue(token, configure, scope, passiveRefresh, searchOperation, false, operation);
	}

	private long enqueue(long token, boolean configure, int scope, boolean passiveRefresh,
	                     boolean searchOperation, boolean managedOperation,
	                     NativeOperation operation) {
		long operationId = nextOperationId.getAndIncrement();
		long enqueueEpoch = cancelEpoch.get();
		long enqueueClearGeneration = nativeSearchClearGeneration.get();
		worker.execute(() -> {
			// Presentation-only refreshes are deliberately tiny and never report scan progress. Avoid
			// creating a second scheduled task four times per second just to discover there is no scan.
			long[] progressBaseline = passiveRefresh || managedOperation ? null
					: NativeMemoryEngine.scanProgress();
			ScheduledFuture<?> progressUpdates = passiveRefresh || managedOperation ? null
					: progressNotifier.scheduleWithFixedDelay(
							() -> notifyProgress(operationId, progressBaseline, searchOperation),
							PROGRESS_UPDATE_PERIOD_MS,
							PROGRESS_UPDATE_PERIOD_MS,
							TimeUnit.MILLISECONDS);
			int result;
			String serviceMessage = null;
			try {
				if (enqueueEpoch != cancelEpoch.get()) {
					result = MemoryEngineContract.RESULT_CANCELLED;
					serviceMessage = "Operation cancelled before it started";
				} else if (token == 0L) {
					result = MemoryEngineContract.RESULT_NO_SESSION;
					serviceMessage = "No active MIDlet runtime";
				} else if (managedOperation && !prepareManagedOperation(token)) {
					result = MemoryEngineContract.RESULT_TARGET_LOST;
					serviceMessage = "MIDlet runtime changed or ended";
				} else if (!managedOperation && !NativeMemoryEngine.prepareOperation(enqueueEpoch)) {
					result = MemoryEngineContract.RESULT_CANCELLED;
					serviceMessage = "Operation cancelled before it started";
				} else if (!managedOperation && configure) {
					result = configureTarget(token, scope);
					if (result == MemoryEngineContract.RESULT_OK) {
						if (nativeSearchClearGeneration.get() != enqueueClearGeneration) {
							result = MemoryEngineContract.RESULT_CANCELLED;
							serviceMessage = "Operation cancelled before it started";
						} else {
							result = operation.run();
							if (result == MemoryEngineContract.RESULT_OK) {
								// The native operation owns its own cancellation/commit boundary. Once it
								// returns OK, retire the previous managed search and publish the raw backend.
								result = commitRawSearch(token, scope, enqueueClearGeneration);
							}
						}
					} else {
						serviceMessage = configurationFailureMessage(result);
					}
				} else if ((!managedOperation && !isCurrentToken(token))
						|| (managedOperation && !isTargetToken(token))) {
					result = MemoryEngineContract.RESULT_TARGET_LOST;
					serviceMessage = "MIDlet runtime changed or ended";
				} else {
					result = operation.run();
				}

				if (result == MemoryEngineContract.RESULT_NO_SESSION &&
						enqueueEpoch != cancelEpoch.get()) {
					result = MemoryEngineContract.RESULT_CANCELLED;
					serviceMessage = "Operation cancelled before it started";
				}

				if (result == MemoryEngineContract.RESULT_OK
						&& ((managedOperation && !isTargetToken(token))
						|| (!managedOperation && !isCurrentToken(token)))) {
					NativeMemoryEngine.clearTarget();
					configuredToken = 0L;
					result = MemoryEngineContract.RESULT_TARGET_LOST;
					serviceMessage = "MIDlet runtime changed during the operation";
				}
				if (serviceMessage == null) {
					serviceMessage = gcSafetyMessage(result);
				}
			} finally {
				if (progressUpdates != null) progressUpdates.cancel(false);
			}
			notifyFinished(operationId, token, result, serviceMessage, passiveRefresh,
					searchOperation);
		});
		return operationId;
	}

	private int configureTarget(long token, int scope) {
		if (!MemoryEngineContract.isRawScope(scope)) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}
		try {
			if (bridge.getRuntimeToken() != token) {
				return MemoryEngineContract.RESULT_TARGET_LOST;
			}
			int pid = bridge.getTargetPid();
			int pageSize = bridge.getPageSize();
			if (!canReadProbe(pid, bridge.getReadProbe(token))) {
				return MemoryEngineContract.RESULT_UNSUPPORTED;
			}
			long[] runs = bridge.getResidentRuns(token, scope,
					MemoryEngineContract.MAX_RESIDENT_RUNS);
			if (!MemoryEngineContract.isCompleteRunList(runs)) {
				if (bridge.getRuntimeToken() != token) {
					return MemoryEngineContract.RESULT_TARGET_LOST;
				}
				return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
			}
			int result = NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
			return result;
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}
	}

	private boolean prepareManagedOperation(long token) {
		if (!isTargetToken(token)) return false;
		if (!synchronizeManagedCancelEpoch(token)) return false;
		return true;
	}

	/** Publishes a raw search only after its target configuration and operation both succeeded. */
	private int commitRawSearch(long token, int scope, long expectedClearGeneration) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return MemoryEngineContract.RESULT_TARGET_LOST;
		try {
			if (bridge.getRuntimeToken() != token) return MemoryEngineContract.RESULT_TARGET_LOST;
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}

		boolean retireManaged;
		long retiredRevision;
		synchronized (searchCommitLock) {
			if (target != bridge) return MemoryEngineContract.RESULT_TARGET_LOST;
			if (nativeSearchClearGeneration.get() != expectedClearGeneration) {
				// Clear is allowed to win after the native operation committed but before this
				// service could publish its metadata. The queued native clear will retire the
				// address result; restore the clear-side session snapshot so the late operation
				// cannot resurrect a raw search in the meantime.
				clearSearchSession();
				searchBackend = MemoryEngineContract.BACKEND_RAW;
				managedRevision = 0L;
				managedResultCount = 0L;
				managedBaselineCount = 0L;
				return MemoryEngineContract.RESULT_CANCELLED;
			}
			retireManaged = searchBackend == MemoryEngineContract.BACKEND_MANAGED
					&& configuredToken == token;
			retiredRevision = managedRevision;
		}

		int retirementResult = MemoryEngineContract.RESULT_OK;
		if (retireManaged) {
			try {
				long clearEpoch = cancelEpoch.incrementAndGet();
				retirementResult = consumeManagedResult(token, bridge,
						bridge.managedClearSearch(token, retiredRevision, clearEpoch));
			} catch (RemoteException exception) {
				retirementResult = MemoryEngineContract.RESULT_TARGET_LOST;
			}
		}
		try {
			if (bridge.getRuntimeToken() != token) return MemoryEngineContract.RESULT_TARGET_LOST;
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}

		synchronized (searchCommitLock) {
			if (target != bridge) return MemoryEngineContract.RESULT_TARGET_LOST;
			if (nativeSearchClearGeneration.get() != expectedClearGeneration) {
				clearSearchSession();
				searchBackend = MemoryEngineContract.BACKEND_RAW;
				managedRevision = 0L;
				managedResultCount = 0L;
				managedBaselineCount = 0L;
				return MemoryEngineContract.RESULT_CANCELLED;
			}
			configuredToken = token;
			configuredScope = scope;
			lastRawScope = scope;
			searchBackend = MemoryEngineContract.BACKEND_RAW;
			if (retireManaged) {
				managedRevision = 0L;
				managedResultCount = 0L;
				managedBaselineCount = 0L;
			}
			if (retirementResult != MemoryEngineContract.RESULT_OK) {
				// The raw operation already committed. Do not report it as a rollback when a
				// revision-specific retirement RPC races a newer target-side state; the raw backend
				// is authoritative now and the managed metadata is hidden until it is reconciled.
				managedLastMessage = "Previous managed search cleanup was not confirmed";
			}
			return MemoryEngineContract.RESULT_OK;
		}
	}

	private int configureRawAuxiliary(long token) {
		if (!MemoryEngineContract.isRawScope(lastRawScope)) {
			lastRawScope = MemoryEngineContract.SCOPE_JAVA_FAST;
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return MemoryEngineContract.RESULT_TARGET_LOST;
		try {
			if (bridge.getRuntimeToken() != token) return MemoryEngineContract.RESULT_TARGET_LOST;
			int pid = bridge.getTargetPid();
			int pageSize = bridge.getPageSize();
			if (!canReadProbe(pid, bridge.getReadProbe(token))) {
				return MemoryEngineContract.RESULT_UNSUPPORTED;
			}
			long[] runs = bridge.getResidentRuns(token, lastRawScope,
					MemoryEngineContract.MAX_RESIDENT_RUNS);
			if (!MemoryEngineContract.isCompleteRunList(runs)) {
				return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
			}
			return NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}
	}

	private int refreshRawAuxiliary(long token, long[] ids, boolean passiveRefresh) {
		int configured = configureRawAuxiliary(token);
		if (configured != MemoryEngineContract.RESULT_OK) return configured;
		return NativeMemoryEngine.refresh(ids, !passiveRefresh);
	}

	private static long[] idsForBackend(@Nullable long[] ids, boolean managed) {
		if (ids == null || ids.length == 0) return new long[0];
		ArrayList<Long> selected = new ArrayList<>();
		for (long id : ids) {
			if (ManagedJavaMemoryIds.isManaged(id) == managed) selected.add(id);
		}
		long[] result = new long[selected.size()];
		for (int index = 0; index < selected.size(); index++) result[index] = selected.get(index);
		return result;
	}

	private static long[] concatIds(long[] first, long[] second) {
		if (first.length > Integer.MAX_VALUE - second.length) return null;
		long[] result = new long[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private int editMixed(long token, long[] ids, int valueType, String replacementValue) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		if (!MemoryEngineContract.isCandidateType(valueType)) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		if (raw.length > 0) {
			int typeCheck = validateRawWatchTypes(raw, valueType);
			if (typeCheck != MemoryEngineContract.RESULT_OK) return typeCheck;
		}
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedEdit(token, isManagedCurrent(token) ? managedRevision : 0L,
						managed, valueType, replacementValue, true);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: editRawAuxiliary(token, raw, replacementValue);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int validateRawWatchTypes(long[] ids, int declaredType) {
		Bundle page = formatWatchPage(NativeMemoryEngine.watchPage());
		long[] watchIds = page.getLongArray(MemoryEngineContract.KEY_WATCH_IDS);
		int[] watchTypes = page.getIntArray(MemoryEngineContract.KEY_WATCH_TYPES);
		if (watchIds == null || watchTypes == null || watchIds.length != watchTypes.length) {
			return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}
		for (long id : ids) {
			boolean found = false;
			for (int index = 0; index < watchIds.length; index++) {
				if (watchIds[index] != id) continue;
				found = true;
				if (watchTypes[index] != declaredType) {
					return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
				}
				break;
			}
			if (!found) return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}
		return MemoryEngineContract.RESULT_OK;
	}

	private int editResultGroupsMixed(long token, long expectedRevision, long[] resultIds, int valueType,
	                                 String replacementValue) {
		if (!MemoryEngineContract.isCandidateType(valueType)
				|| resultIds == null || resultIds.length == 0
				|| resultIds.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		long[] managed = idsForBackend(resultIds, true);
		long[] rawGroups = idsForBackend(resultIds, false);
		long[] raw = rawGroups.length == 0 ? new long[0]
				: NativeMemoryEngine.expandResultGroups(rawGroups, valueType);
		if (raw == null || raw.length == 0 && managed.length == 0) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		if (raw.length > MemoryEngineContract.MAX_REQUEST_TARGETS
				|| managed.length > MemoryEngineContract.MAX_REQUEST_TARGETS
				|| raw.length > MemoryEngineContract.MAX_REQUEST_TARGETS - managed.length) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedEdit(token, expectedRevision,
						managed, valueType, replacementValue, false);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: editRawAuxiliary(token, raw, replacementValue);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int editRawAuxiliary(long token, long[] ids, String replacementValue) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		if (!isWriteSupported(token)) return MemoryEngineContract.RESULT_UNSUPPORTED;
		long operationEpoch = cancelEpoch.get();
		int completed = 0;
		for (int offset = 0; offset < ids.length; offset += MemoryEngineContract.MAX_MULTI_WRITE) {
			if (cancelEpoch.get() != operationEpoch) {
				return completed > 0 ? MemoryEngineContract.RESULT_PARTIAL_WRITE
						: MemoryEngineContract.RESULT_CANCELLED;
			}
			int end = Math.min(ids.length, offset + MemoryEngineContract.MAX_MULTI_WRITE);
			long[] chunk = Arrays.copyOfRange(ids, offset, end);
			int result = performGuardedRawMutation(token, chunk,
					() -> NativeMemoryEngine.edit(chunk, replacementValue));
			if (result != MemoryEngineContract.RESULT_OK) {
				return completed > 0 ? MemoryEngineContract.RESULT_PARTIAL_WRITE : result;
			}
			completed += end - offset;
		}
		return MemoryEngineContract.RESULT_OK;
	}

	private int addWatchResultGroupsMixed(long token, long[] resultIds, int valueType) {
		if (!MemoryEngineContract.isCandidateType(valueType)
				|| resultIds == null || resultIds.length == 0
				|| resultIds.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		long[] managed = idsForBackend(resultIds, true);
		long[] rawGroups = idsForBackend(resultIds, false);
		long[] raw = rawGroups.length == 0 ? new long[0]
				: NativeMemoryEngine.expandResultGroups(rawGroups, valueType);
		if (raw == null) return MemoryEngineContract.RESULT_INVALID_REQUEST;
		long[] combined = concatIds(managed, raw);
		if (combined == null || combined.length == 0
				|| !globalWatchCapacityAvailable(token, combined)) {
			return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
		}
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedAddWatch(token, isManagedCurrent(token) ? managedRevision : 0L, managed);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: addRawWatchAuxiliary(token, raw);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int setFreezeResultGroupsMixed(long token, long[] resultIds, int valueType,
	                                      int mode, String firstValue, String secondValue) {
		if (!MemoryEngineContract.isCandidateType(valueType)
				|| resultIds == null || resultIds.length == 0
				|| resultIds.length > MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		if (mode != MemoryEngineContract.FREEZE_LOCK) return MemoryEngineContract.RESULT_UNSUPPORTED;
		long[] managed = idsForBackend(resultIds, true);
		long[] rawGroups = idsForBackend(resultIds, false);
		long[] raw = rawGroups.length == 0 ? new long[0]
				: NativeMemoryEngine.expandResultGroups(rawGroups, valueType);
		if (raw == null) return MemoryEngineContract.RESULT_INVALID_REQUEST;
		long[] combined = concatIds(managed, raw);
		if (combined == null || combined.length == 0
				|| !globalWatchCapacityAvailable(token, combined)
				|| !globalFreezeCapacityAvailable(token, combined)) {
			return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
		}
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedFreeze(token, isManagedCurrent(token) ? managedRevision : 0L,
						managed, mode, firstValue, secondValue, false);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: setRawFreezeAuxiliary(token, raw, firstValue, secondValue);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int refreshMixed(long token, long[] ids, boolean passiveRefresh) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: refreshRawAuxiliary(token, raw, passiveRefresh);
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedRefresh(token, managed, passiveRefresh);
		return rawResult != MemoryEngineContract.RESULT_OK ? rawResult : managedResult;
	}

	private int addWatchMixed(long token, long[] ids) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		if (!globalWatchCapacityAvailable(token, ids)) {
			return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedAddWatch(token, isManagedCurrent(token) ? managedRevision : 0L, managed);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: addRawWatchAuxiliary(token, raw);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int addRawWatchAuxiliary(long token, long[] ids) {
		int ready = refreshRawAuxiliary(token, ids, false);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		int result = NativeMemoryEngine.pin(ids, true);
		if (result == MemoryEngineContract.RESULT_OK) {
			bindingCache.recordPage(NativeMemoryEngine.watchPage(), true, 0);
		}
		return result;
	}

	private int removeWatchMixed(long token, long[] ids) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedRemoveWatch(token, managed);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: removeRawWatchAuxiliary(token, raw);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int removeRawWatchAuxiliary(long token, long[] ids) {
		int ready = configureRawAuxiliary(token);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		int result = NativeMemoryEngine.pin(ids, false);
		if (result == MemoryEngineContract.RESULT_OK) {
			for (long id : ids) {
				watchLabels.remove(id);
				freezeRecords.remove(id);
				gcBindings.forgetCandidate(id);
			}
			stopFreezeTaskIfIdle();
		}
		return result;
	}

	private int clearFreezeMixed(long token, long[] ids) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedClearFreeze(token, managed);
		int rawResult = MemoryEngineContract.RESULT_OK;
		for (long id : raw) {
			if (freezeRecords.remove(id) == null) rawResult = MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		stopFreezeTaskIfIdle();
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int setFreezeMixed(long token, long[] ids, int mode, String first, String second) {
		if (mode != MemoryEngineContract.FREEZE_LOCK) {
			return MemoryEngineContract.RESULT_UNSUPPORTED;
		}
		if (!globalWatchCapacityAvailable(token, ids)
				|| !globalFreezeCapacityAvailable(token, ids)) {
			return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
		}
		long[] managed = idsForBackend(ids, true);
		long[] raw = idsForBackend(ids, false);
		int managedResult = managed.length == 0 ? MemoryEngineContract.RESULT_OK
				: managedFreeze(token, isManagedCurrent(token) ? managedRevision : 0L,
						managed, mode, first, second, true);
		int rawResult = raw.length == 0 ? MemoryEngineContract.RESULT_OK
				: setRawFreezeAuxiliary(token, raw, first, second);
		return managedResult != MemoryEngineContract.RESULT_OK ? managedResult : rawResult;
	}

	private int setRawFreezeAuxiliary(long token, long[] ids, String firstValue,
	                                 String secondValue) {
		if (!isWriteSupported(token)) return MemoryEngineContract.RESULT_UNSUPPORTED;
		int ready = refreshRawAuxiliary(token, ids, false);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		long[] newlyWatched = idsForUnwatchedRaw(ids);
		if (newlyWatched.length > 0) {
			int pinResult = NativeMemoryEngine.pin(newlyWatched, true);
			if (pinResult != MemoryEngineContract.RESULT_OK) return pinResult;
		}
		int result = NativeMemoryEngine.freeze(ids, MemoryEngineContract.FREEZE_LOCK,
				firstValue, secondValue == null ? "" : secondValue);
		if (result != MemoryEngineContract.RESULT_OK && newlyWatched.length > 0) {
			NativeMemoryEngine.pin(newlyWatched, false);
		}
		if (result == MemoryEngineContract.RESULT_OK) {
			long gc = currentGcCount(token);
			for (long id : ids) freezeRecords.put(id,
					new FreezeRecord(MemoryEngineContract.FREEZE_LOCK, firstValue,
							secondValue == null ? "" : secondValue, gc));
			startFreezeTaskIfNeeded();
		}
		return result;
	}

	private long[] idsForUnwatchedRaw(long[] ids) {
		ArrayList<Long> result = new ArrayList<>();
		for (long id : ids) if (!isWatchedCandidate(id)) result.add(id);
		long[] output = new long[result.size()];
		for (int index = 0; index < result.size(); index++) output[index] = result.get(index);
		return output;
	}

	private int managedStartUnknown(long token, int type) {
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Unknown search received an unsupported type selector");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = nativeSearchClearGeneration.get();
		try {
			Bundle result = bridge.managedStartUnknown(token, type, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			boolean wasRaw;
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, 0L);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				wasRaw = searchBackend != MemoryEngineContract.BACKEND_MANAGED;
				clearSearchSession();
				configuredToken = token;
				configuredScope = MemoryEngineContract.SCOPE_MANAGED_JAVA;
				searchBackend = MemoryEngineContract.BACKEND_MANAGED;
				resetSearchSession(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
						MemoryEngineContract.SEARCH_MODE_UNKNOWN, type,
						MemoryEngineContract.SCOPE_MANAGED_JAVA,
						MemoryEngineContract.GC_COUNT_UNKNOWN);
			}
			if (wasRaw) NativeMemoryEngine.clearSearch();
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedFilterResultGroups(long token, long expectedRevision, long[] resultIds,
	                                     boolean keep) {
		if (resultIds == null || resultIds.length == 0
				|| resultIds.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
			return managedFailure(MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed candidate filtering is limited to 128 selected rows");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = nativeSearchClearGeneration.get();
		try {
			Bundle result = bridge.managedFilter(token, expectedRevision, resultIds, keep,
					operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedStartExact(long token, int type, int predicate, String first, String second) {
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed Java supports Byte, Short, Char, Int, Long, Float, and Double");
		}
		if (predicate < MemoryEngineContract.PREDICATE_EQUAL
				|| predicate > MemoryEngineContract.PREDICATE_BETWEEN) {
			return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Known search received an unsupported predicate");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = nativeSearchClearGeneration.get();
		try {
			Bundle result = bridge.managedStartExact(token, type, predicate, first, second,
					operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			boolean wasRaw;
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, 0L);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				wasRaw = searchBackend != MemoryEngineContract.BACKEND_MANAGED;
				clearSearchSession();
				configuredToken = token;
				configuredScope = MemoryEngineContract.SCOPE_MANAGED_JAVA;
				searchBackend = MemoryEngineContract.BACKEND_MANAGED;
				resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
						MemoryEngineContract.SEARCH_MODE_KNOWN, type,
						MemoryEngineContract.SCOPE_MANAGED_JAVA,
						MemoryEngineContract.GC_COUNT_UNKNOWN);
			}
			if (wasRaw) NativeMemoryEngine.clearSearch();
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRefine(long token, int predicate, int compareTarget, String value) {
		return managedRefine(token, managedRevision, managedSearchType(), predicate,
				compareTarget, value, "");
	}

	private int managedRefine(long token, long expectedRevision, int type, int predicate,
	                          int compareTarget, String value, String secondValue) {
		if (!isTargetToken(token)) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime changed or ended");
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed refine has no supported primitive search type");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = nativeSearchClearGeneration.get();
		try {
			Bundle result = bridge.managedRefine(token, expectedRevision, type,
					predicate, compareTarget, value, secondValue, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				searchBackend = MemoryEngineContract.BACKEND_MANAGED;
				advanceManagedSearchSession(type);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRefresh(long token, long[] ids, boolean passiveRefresh) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedRefresh(token, ids,
					passiveRefresh ? 0L : managedRevision, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedEdit(long token, long expectedRevision, long[] ids, int declaredType,
	                       String replacementValue, boolean allowWatchOnly) {
		if (!allowWatchOnly && expectedRevision <= 0L) expectedRevision = managedRevision;
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_REQUEST_TARGETS) {
			return managedFailure(MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed edits are limited to 128 logical targets");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedEditTyped(token,
					expectedRevision, ids, declaredType, replacementValue, allowWatchOnly,
					cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedEditInspector(long token, long anchorCandidateId, boolean watchAnchor,
	                                int relativeOffset, int valueType, long expectedBits,
	                                String replacementValue) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			long expectedRevision = watchAnchor ? 0L : managedRevision;
			return consumeManagedResult(token, bridge, bridge.managedEditInspector(token,
				expectedRevision, anchorCandidateId, watchAnchor, relativeOffset, valueType,
				expectedBits, replacementValue, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedAddWatch(long token, long expectedRevision, long[] ids) {
		if (!globalWatchCapacityAvailable(token, ids)) {
			return managedFailure(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
					"The global Watch List limit is 128 rows");
		}
		if (expectedRevision <= 0L) expectedRevision = managedRevision;
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedAddWatch(token,
					expectedRevision, ids, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRemoveWatch(long token, long[] ids) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedRemoveWatch(token, ids, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) {
				stopFreezeTaskIfIdle();
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedSetWatchLabel(long token, long id, String label) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge,
					bridge.managedSetWatchLabel(token, id, label, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedFreeze(long token, long expectedRevision, long[] ids, int mode,
	                          String firstValue, String secondValue, boolean allowWatchOnly) {
		if (mode != MemoryEngineContract.FREEZE_LOCK) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed Freeze supports Lock only");
		}
		if (!globalWatchCapacityAvailable(token, ids)
				|| !globalFreezeCapacityAvailable(token, ids)) {
			return managedFailure(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
					"The global Watch/Freeze limit would be exceeded");
		}
		if (!allowWatchOnly && expectedRevision <= 0L) expectedRevision = managedRevision;
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedSetFreezeLockTyped(token, expectedRevision, ids,
							firstValue, allowWatchOnly, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) startFreezeTaskIfNeeded();
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedClearFreeze(long token, long[] ids) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedClearFreeze(token, ids, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) {
				stopFreezeTaskIfIdle();
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime connection was lost");
		}
	}

	private Bundle managedResultPage(long token, long expectedRevision, int offset, int limit) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return emptyResultPage();
		long clearGeneration = nativeSearchClearGeneration.get();
		long operationEpoch = cancelEpoch.get();
		try {
			Bundle result = bridge.managedResultPage(token, expectedRevision, offset, limit);
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				return decision == MemoryEngineContract.RESULT_OK ? result : emptyResultPage();
			}
		} catch (RemoteException exception) {
			return emptyResultPage();
		}
	}

	private Bundle managedWatchPage(long token) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return emptyWatchPage();
		try {
			Bundle result = bridge.managedWatchPage(token);
			return acceptManagedRuntimeState(token, bridge, result) ? result : emptyWatchPage();
		} catch (RemoteException exception) {
			return emptyWatchPage();
		}
	}

	private int consumeManagedResult(long token, IMemoryTargetBridge bridge,
	                                @Nullable Bundle result) {
		if (managedResultCode(result) == Integer.MIN_VALUE) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed target returned no operation result");
		}
		try {
			if (!acceptManagedRuntimeState(token, bridge, result)) {
				return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
						"Managed reply belongs to an old runtime generation");
			}
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
		updateManagedState(token, result);
		return managedResultCode(result);
	}

	private static int managedResultCode(@Nullable Bundle result) {
		if (result == null || !result.containsKey(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT)) {
			return Integer.MIN_VALUE;
		}
		return result.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
				MemoryEngineContract.RESULT_TARGET_LOST);
	}

	private boolean acceptManagedRuntimeState(long token, IMemoryTargetBridge bridge,
	                                         @Nullable Bundle state) throws RemoteException {
		if (state == null) return false;
		long replyToken = state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L);
		long runtimeAfterRpc = bridge.getRuntimeToken();
		synchronized (searchCommitLock) {
			return MemoryManagedServicePolicy.managedReplyFromCurrentRuntime(token, replyToken,
					runtimeAfterRpc, target == bridge);
		}
	}

	private int managedSearchReplyDecisionLocked(long token, IMemoryTargetBridge bridge,
	                                             @Nullable Bundle state, long runtimeAfterRpc,
	                                             long expectedClearGeneration,
	                                             long operationEpoch, long expectedRevision) {
		if (state == null) return MemoryEngineContract.RESULT_TARGET_LOST;
		long replyToken = state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L);
		long replyRevision = state.getLong(MemoryEngineContract.KEY_MANAGED_REVISION, 0L);
		return MemoryManagedServicePolicy.managedReplyDecision(token, replyToken, runtimeAfterRpc,
				expectedClearGeneration, nativeSearchClearGeneration.get(), target == bridge,
				operationEpoch, cancelEpoch.get(), expectedRevision, replyRevision, managedRevision);
	}

	private static String managedReplyFailureMessage(int decision) {
		return switch (decision) {
			case MemoryEngineContract.RESULT_CANCELLED ->
					"Managed reply was invalidated by explicit Clear";
			case MemoryEngineContract.RESULT_IDENTITY_UNSAFE ->
					"Managed search revision changed before the reply could be published";
			default -> "Managed reply belongs to an old runtime generation";
		};
	}

	private int managedFailure(int code, String message) {
		managedLastMessage = message;
		return code;
	}

	private void updateManagedState(long token, @Nullable Bundle state) {
		if (state == null || token == 0L
				|| state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L) != token) return;
		if (managedStateToken != token) {
			managedStateToken = token;
			managedRevision = 0L;
			managedResultCount = 0L;
			managedBaselineCount = 0L;
			managedWatchCount = 0;
			managedFreezeCount = 0;
			managedLastMessage = null;
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH)) {
			synchronizeCancelEpoch(state.getLong(
					MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH));
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_SUPPORTED)) {
			managedSupported = state.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED)) {
			managedWriteSupported = state.getBoolean(
					MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED);
		}
		boolean acceptSearchMetadata = true;
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_REVISION)) {
			long incomingRevision = state.getLong(MemoryEngineContract.KEY_MANAGED_REVISION);
			// Pages and Binder replies can arrive after a newer operation. Revision zero is
			// not a Watch-page sentinel here; only an authoritative clear (which resets the
			// local fields explicitly) may move a nonzero search revision back to zero.
			acceptSearchMetadata = incomingRevision >= managedRevision
					|| (incomingRevision == 0L && managedRevision == 0L);
			if (acceptSearchMetadata) managedRevision = incomingRevision;
		}
		if (acceptSearchMetadata && state.containsKey(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT)) {
			managedResultCount = state.getLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT);
		}
		if (acceptSearchMetadata && state.containsKey(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT)) {
			managedBaselineCount = state.getLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT)) {
			managedWatchCount = state.getInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT)) {
			managedFreezeCount = state.getInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MESSAGE)) {
			managedLastMessage = state.getString(MemoryEngineContract.KEY_MESSAGE);
		}
	}

	private void advanceManagedSearchSession(int type) {
		synchronized (searchSessionLock) {
			searchSessionStage = MemoryEngineContract.SEARCH_SESSION_CANDIDATES;
			searchRequestedType = type;
			searchSessionScope = MemoryEngineContract.SCOPE_MANAGED_JAVA;
			searchStageHistory.clear();
			searchGcHistory.clear();
			gcBindings.clearSearchEpoch();
		}
	}

	private int managedSearchType() {
		synchronized (searchSessionLock) {
			return searchRequestedType;
		}
	}

	private static void synchronizeCancelEpoch(long targetEpoch) {
		if (targetEpoch < 0L) return;
		while (true) {
			long current = cancelEpoch.get();
			if (current >= targetEpoch || cancelEpoch.compareAndSet(current, targetEpoch)) return;
		}
	}

	private boolean synchronizeManagedCancelEpoch(long token) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return false;
		try {
			if (bridge.getRuntimeToken() != token) return false;
			Bundle state = bridge.getManagedCapabilities(token);
			if (!acceptManagedRuntimeState(token, bridge, state)) return false;
			updateManagedState(token, state);
			return true;
		} catch (RemoteException exception) {
			return false;
		}
	}

	private int runNewSearchWithGcGuard(long token, NativeOperation operation, int stage,
	                                    int mode, int requestedType, int scope) {
		long gcBefore = currentGcCount(token);
		int result = operation.run();
		if (result != MemoryEngineContract.RESULT_OK) return result;
		long gcAfter = currentGcCount(token);
		boolean addressSnapshotBaseline =
				stage == MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE;
		if (MemoryGcPolicy.firstSearchRequiresStableEpoch(
				addressSnapshotBaseline, gcBefore, gcAfter)) {
			NativeMemoryEngine.clearSearch();
			clearSearchSession();
			return MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED;
		}
		resetSearchSession(stage, mode, requestedType, scope,
				MemoryGcPolicy.publishedSearchEpoch(
						addressSnapshotBaseline, gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int refineKnownAddressSet(long token, int valueType, int predicate,
	                                 String first, String second) {
		// Bulk Known/Auto results are address membership. A stale GC epoch merely permits native
		// to probe for broad relocation; it does not itself request a full target scan. Native
		// additionally requires a large result set and strong sampled fingerprint evidence.
		// After a successful refine the published revision epoch advances, so the same GC epoch
		// cannot repeatedly trigger reconciliation. Small result sets always stay candidate-only.
		long gcBefore = currentGcCount(token);
		boolean unknownBaseline;
		synchronized (searchSessionLock) {
			unknownBaseline = searchSessionStage ==
					MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE;
		}
		if (unknownBaseline && gcBindings.searchEpochChanged(gcBefore)) {
			NativeMemoryEngine.clearSearch();
			clearSearchSession();
			return MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED;
		}
		boolean allowRelocationReconcile = !unknownBaseline &&
				gcBindings.searchEpochChanged(gcBefore);
		int result = NativeMemoryEngine.refineKnown(
				valueType, predicate, first, second, allowRelocationReconcile);
		if (result != MemoryEngineContract.RESULT_OK) return result;
		long gcAfter = currentGcCount(token);
		if (unknownBaseline && MemoryEngineContract.didGcCountChange(gcBefore, gcAfter)) {
			return rollbackAfterGcRace();
		}
		advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
				MemoryGcPolicy.publishedSearchEpoch(false, gcBefore, gcAfter), valueType);
		return MemoryEngineContract.RESULT_OK;
	}

	private int refineRelativeGcAware(long token, int valueType, int predicate, int compareTarget,
	                                  String first, String second) {
		long gcBefore = currentGcCount(token);
		if (gcBindings.searchEpochChanged(gcBefore)) {
			boolean unknownBaseline;
			synchronized (searchSessionLock) {
				unknownBaseline = searchSessionStage ==
						MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE;
			}
			if (unknownBaseline) {
				NativeMemoryEngine.clearSearch();
				clearSearchSession();
			}
			return MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED;
		}
		int result = NativeMemoryEngine.refineRelative(
				valueType, predicate, compareTarget, first, second);
		if (result != MemoryEngineContract.RESULT_OK) return result;
		long gcAfter = currentGcCount(token);
		if (MemoryEngineContract.didGcCountChange(gcBefore, gcAfter)) {
			return rollbackAfterGcRace();
		}
		advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter), valueType);
		return MemoryEngineContract.RESULT_OK;
	}

	private int rollbackAfterGcRace() {
		int undoResult = NativeMemoryEngine.undo();
		if (undoResult == MemoryEngineContract.RESULT_OK) {
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		NativeMemoryEngine.clearSearch();
		clearSearchSession();
		return MemoryEngineContract.RESULT_GC_RACE;
	}

	/**
	 * Cheap presentation refresh for rows that are already materialized on screen. It updates only
	 * the live Candidate overlay at the current binding and never performs a heap-wide relocation
	 * search, resident-range rebuild, GC-epoch mutation, or SearchState/history commit.
	 */
	private int refreshVisibleCandidates(long[] candidateIds) {
		if (candidateIds == null || candidateIds.length == 0) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		return NativeMemoryEngine.refresh(candidateIds, false);
	}

	/** Explicit Refresh/rebind path. This may perform identity recovery after a copying GC. */
	private int refreshForRead(long token, long[] candidateIds) {
		if (candidateIds == null || candidateIds.length == 0) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		Map<Long, MemoryCandidateBindingCache.Binding> before =
				bindingCache.snapshot(candidateIds);
		if (before == null) {
			int result = refreshWithRecovery(token, candidateIds);
			if (result == MemoryEngineContract.RESULT_OK) refreshCachedBindings(candidateIds);
			return result;
		}

		long gcBefore = currentGcCount(token);
		// Verify the existing CandidateId bindings against their fingerprints first. Native recovery
		// here is bounded to the already-configured resident ranges, so an ordinary GC does not
		// immediately force target-side mincore() and a full range refresh.
		int fastResult = NativeMemoryEngine.refresh(candidateIds, true);
		int bindingResult = fastResult == MemoryEngineContract.RESULT_OK
				? compareRefreshedBindings(before)
				: MemoryEngineContract.RESULT_OK;
		long gcAfter = currentGcCount(token);
		boolean gcChangedDuringFastRefresh =
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);

		if (MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				fastResult, bindingResult, gcChangedDuringFastRefresh)) {
			int result = refreshWithRecovery(token, candidateIds);
			if (result != MemoryEngineContract.RESULT_OK) return result;
			int refreshedBindingResult = compareRefreshedBindings(before);
			if (refreshedBindingResult == MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
				return refreshedBindingResult;
			}
			// A uniquely recovered move is normal for read-only presentation; explicit mutation will
			// perform its own native identity check immediately before writing.
			return MemoryEngineContract.RESULT_OK;
		}

		if (fastResult != MemoryEngineContract.RESULT_OK) return fastResult;
		if (bindingResult == MemoryEngineContract.RESULT_IDENTITY_UNSAFE) return bindingResult;
		gcBindings.markCandidatesValidated(candidateIds,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int refreshWithRecovery(long token, long[] candidateIds) {
		long[] ids = candidateIds == null ? new long[0] : candidateIds;
		long gcBefore = currentGcCount(token);
		int result = configureTarget(token, configuredScope);
		if (result != MemoryEngineContract.RESULT_OK) return result;
		result = NativeMemoryEngine.refresh(ids, true);
		if (result != MemoryEngineContract.RESULT_OK) return result;
		long gcAfter = currentGcCount(token);
		if (MemoryEngineContract.didGcCountChange(gcBefore, gcAfter)) {
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		gcBindings.markCandidatesValidated(ids,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int prepareExplicitMutation(long token, long[] candidateIds) {
		Map<Long, MemoryCandidateBindingCache.Binding> before =
				bindingCache.snapshot(candidateIds);
		if (before == null) {
			// A guarded mutation must be tied to a binding the UI actually materialized. Reloading the
			// page/watch list populates this small cache; never guess an unseen raw address here.
			return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}

		long gcBefore = currentGcCount(token);
		// First verify the displayed binding against the already-configured ranges. A GC-count change
		// alone is only a stale-address hint and must not force target-side mincore() on every Edit or
		// Freeze setup.
		int fastResult = NativeMemoryEngine.refresh(candidateIds, true);
		int bindingResult = fastResult == MemoryEngineContract.RESULT_OK
				? compareRefreshedBindings(before)
				: MemoryEngineContract.RESULT_OK;
		long gcAfter = currentGcCount(token);
		boolean gcChangedDuringFastRefresh =
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);

		if (MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				fastResult, bindingResult, gcChangedDuringFastRefresh)) {
			int result = refreshWithRecovery(token, candidateIds);
			if (result != MemoryEngineContract.RESULT_OK) return result;
			int refreshedBindingResult = compareRefreshedBindings(before);
			// A unique recovery is already verified by native refresh and can continue into the
			// guarded operation. Ambiguous/lost identity still fails closed.
			if (!MemoryGcPolicy.mutationBindingIsReady(refreshedBindingResult)) {
				return refreshedBindingResult;
			}
			return MemoryEngineContract.RESULT_OK;
		}

		if (fastResult != MemoryEngineContract.RESULT_OK) return fastResult;
		if (!MemoryGcPolicy.mutationBindingIsReady(bindingResult)) return bindingResult;
		gcBindings.markCandidatesValidated(candidateIds,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int revalidateAgainIfGcMoved(long token, long[] candidateIds) {
		long gcCount = currentGcCount(token);
		if (!gcBindings.candidatesNeedRevalidation(candidateIds, gcCount)) {
			return MemoryEngineContract.RESULT_OK;
		}
		return prepareExplicitMutation(token, candidateIds);
	}

	private int compareRefreshedBindings(
			Map<Long, MemoryCandidateBindingCache.Binding> before) {
		LinkedHashMap<Long, MemoryCandidateBindingCache.Binding> after = new LinkedHashMap<>();
		Set<Integer> resultPageOffsets = new HashSet<>();
		boolean needsWatchPage = false;
		for (MemoryCandidateBindingCache.Binding binding : before.values()) {
			if (binding.watch) {
				needsWatchPage = true;
			} else {
				resultPageOffsets.add(binding.resultPageOffset);
			}
		}
		if (needsWatchPage && !MemoryCandidateBindingCache.collectPage(
				NativeMemoryEngine.watchPage(), true, 0, after)) {
			return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		}
		for (int offset : resultPageOffsets) {
			if (!MemoryCandidateBindingCache.collectPage(
					NativeMemoryEngine.resultPage(offset,
							MemoryEngineContract.MAX_RESULT_PAGE_SIZE),
					false, offset, after)) {
				return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
			}
		}
		int comparison = bindingCache.compareAndRecord(before, after);
		return switch (comparison) {
			case MemoryCandidateBindingCache.COMPARE_STABLE -> MemoryEngineContract.RESULT_OK;
			case MemoryCandidateBindingCache.COMPARE_MOVED ->
					MemoryEngineContract.RESULT_GC_REVALIDATED;
			default -> MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		};
	}

	private void refreshCachedBindings(long[] candidateIds) {
		Map<Long, MemoryCandidateBindingCache.Binding> before = bindingCache.snapshot(candidateIds);
		if (before != null) compareRefreshedBindings(before);
	}

	private int performGuardedMutation(long token, long[] candidateIds, NativeOperation write) {
		int ready = prepareExplicitMutation(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		ready = revalidateAgainIfGcMoved(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		long gcBeforeWrite = currentGcCount(token);
		int result = write.run();
		long gcAfterWrite = currentGcCount(token);
		if (MemoryGcPolicy.shouldReportGcRaceAfterMutation(
				result, gcBeforeWrite, gcAfterWrite)) {
			// Do not attempt a second write. Full or partial success means target bytes may already have
			// changed; a copying GC makes the final binding unconfirmable until a later user action.
			refreshWithRecovery(token, candidateIds);
			refreshCachedBindings(candidateIds);
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		if (result == MemoryEngineContract.RESULT_OK) {
			gcBindings.markCandidatesValidated(candidateIds,
					MemoryEngineContract.latestKnownGcCount(gcBeforeWrite, gcAfterWrite));
			refreshCachedBindings(candidateIds);
		}
		return result;
	}

	/** Raw half of a mixed operation; it never changes the managed session/backend metadata. */
	private int refreshRawWithRecovery(long token, long[] candidateIds) {
		long gcBefore = currentGcCount(token);
		int ready = configureRawAuxiliary(token);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		int result = NativeMemoryEngine.refresh(candidateIds, true);
		if (result != MemoryEngineContract.RESULT_OK) return result;
		long gcAfter = currentGcCount(token);
		if (MemoryEngineContract.didGcCountChange(gcBefore, gcAfter)) {
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		gcBindings.markCandidatesValidated(candidateIds,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int prepareExplicitRawMutation(long token, long[] candidateIds) {
		Map<Long, MemoryCandidateBindingCache.Binding> before =
				bindingCache.snapshot(candidateIds);
		if (before == null) return MemoryEngineContract.RESULT_IDENTITY_UNSAFE;

		long gcBefore = currentGcCount(token);
		int fastResult = NativeMemoryEngine.refresh(candidateIds, true);
		int bindingResult = fastResult == MemoryEngineContract.RESULT_OK
				? compareRefreshedBindings(before)
				: MemoryEngineContract.RESULT_OK;
		long gcAfter = currentGcCount(token);
		boolean gcChangedDuringFastRefresh =
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);

		if (MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				fastResult, bindingResult, gcChangedDuringFastRefresh)) {
			int result = refreshRawWithRecovery(token, candidateIds);
			if (result != MemoryEngineContract.RESULT_OK) return result;
			int refreshedBindingResult = compareRefreshedBindings(before);
			if (!MemoryGcPolicy.mutationBindingIsReady(refreshedBindingResult)) {
				return refreshedBindingResult;
			}
			return MemoryEngineContract.RESULT_OK;
		}

		if (fastResult != MemoryEngineContract.RESULT_OK) return fastResult;
		if (!MemoryGcPolicy.mutationBindingIsReady(bindingResult)) return bindingResult;
		gcBindings.markCandidatesValidated(candidateIds,
				MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter));
		return MemoryEngineContract.RESULT_OK;
	}

	private int revalidateRawIfGcMoved(long token, long[] candidateIds) {
		long gcCount = currentGcCount(token);
		if (!gcBindings.candidatesNeedRevalidation(candidateIds, gcCount)) {
			return MemoryEngineContract.RESULT_OK;
		}
		int ready = configureRawAuxiliary(token);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		return prepareExplicitRawMutation(token, candidateIds);
	}

	private int performGuardedRawMutation(long token, long[] candidateIds, NativeOperation write) {
		int ready = configureRawAuxiliary(token);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		ready = prepareExplicitRawMutation(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		ready = revalidateRawIfGcMoved(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) return ready;
		long gcBeforeWrite = currentGcCount(token);
		int result = write.run();
		long gcAfterWrite = currentGcCount(token);
		if (MemoryGcPolicy.shouldReportGcRaceAfterMutation(
				result, gcBeforeWrite, gcAfterWrite)) {
			refreshRawWithRecovery(token, candidateIds);
			refreshCachedBindings(candidateIds);
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		if (result == MemoryEngineContract.RESULT_OK) {
			gcBindings.markCandidatesValidated(candidateIds,
					MemoryEngineContract.latestKnownGcCount(gcBeforeWrite, gcAfterWrite));
			refreshCachedBindings(candidateIds);
		}
		return result;
	}

	private Bundle inspectManagedCandidate(long token, long candidateId, int radius,
	                                      boolean watchAnchor) {
		if (!isCurrentToken(token)) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed runtime is no longer current");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			long expectedRevision = watchAnchor ? 0L : managedRevision;
			Bundle result = bridge.managedInspect(token, expectedRevision, candidateId, radius,
					watchAnchor);
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, nativeSearchClearGeneration.get(), cancelEpoch.get(),
						expectedRevision);
				return decision == MemoryEngineContract.RESULT_OK ? result
						: inspectionFailure(decision, managedReplyFailureMessage(decision));
			}
		} catch (RemoteException exception) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private Bundle inspectCandidateOnWorker(long token, long candidateId, int radius) {
		if (!isTargetToken(token)) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime changed or ended");
		}
		long[] candidateIds = new long[]{candidateId};
		int ready = refreshWithRecovery(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) {
			return inspectionFailure(ready, gcSafetyMessage(ready));
		}
		refreshCachedBindings(candidateIds);
		long[] raw = NativeMemoryEngine.inspect(candidateId, radius);
		if (raw == null || raw.length < 4) {
			return inspectionFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Inspector returned an invalid native response");
		}
		int result = (int) raw[0];
		if (result != MemoryEngineContract.RESULT_OK) {
			return inspectionFailure(result, NativeMemoryEngine.lastMessage());
		}
		long byteCountLong = raw[3];
		if (raw[1] <= 0L || raw[2] <= 0L || byteCountLong < 0L ||
				byteCountLong > MemoryEngineContract.MAX_INSPECT_BYTES ||
				raw.length != 4L + byteCountLong) {
			return inspectionFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Inspector returned malformed bounded data");
		}
		if (!isCurrentToken(token)) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime changed during Inspector read");
		}
		int byteCount = (int) byteCountLong;
		byte[] bytes = new byte[byteCount];
		for (int index = 0; index < byteCount; index++) {
			bytes[index] = (byte) (raw[4 + index] & 0xffL);
		}
		Bundle bundle = new Bundle();
		bundle.putInt(MemoryEngineContract.KEY_INSPECT_RESULT, MemoryEngineContract.RESULT_OK);
		bundle.putLong(MemoryEngineContract.KEY_INSPECT_START, raw[1]);
		bundle.putLong(MemoryEngineContract.KEY_INSPECT_ANCHOR, raw[2]);
		bundle.putByteArray(MemoryEngineContract.KEY_INSPECT_BYTES, bytes);
		return bundle;
	}

	private static Bundle inspectionFailure(int result, @Nullable String message) {
		Bundle bundle = new Bundle();
		bundle.putInt(MemoryEngineContract.KEY_INSPECT_RESULT, result);
		if (message != null && !message.isBlank()) {
			bundle.putString(MemoryEngineContract.KEY_MESSAGE, message);
		}
		return bundle;
	}

	private int setFreezeRecords(long token, long[] candidateIds, int mode,
	                             String firstValue, String secondValue) {
		if (candidateIds == null || candidateIds.length == 0 ||
				candidateIds.length > MemoryEngineContract.MAX_FREEZE_RECORDS ||
				mode < MemoryEngineContract.FREEZE_LOCK ||
				mode > MemoryEngineContract.FREEZE_RANGE) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		Set<Long> uniqueIds = new HashSet<>();
		for (long id : candidateIds) {
			uniqueIds.add(id);
		}
		int additionalRecords = 0;
		for (long id : uniqueIds) {
			if (!freezeRecords.containsKey(id)) {
				additionalRecords++;
			}
		}
		if (freezeRecords.size() + additionalRecords >
				MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		if (!globalWatchCapacityAvailable(token, candidateIds)
				|| !globalFreezeCapacityAvailable(token, candidateIds)) {
			return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
		}
		if (!isWriteSupported(token)) {
			return MemoryEngineContract.RESULT_UNSUPPORTED;
		}
		int ready = prepareExplicitMutation(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) {
			return ready;
		}
		ready = revalidateAgainIfGcMoved(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) {
			return ready;
		}
		long[] newWatchBuffer = new long[candidateIds.length];
		int newWatchCount = 0;
		for (long id : candidateIds) {
			if (!isWatchedCandidate(id)) {
				newWatchBuffer[newWatchCount++] = id;
			}
		}
		long[] newlyWatched = Arrays.copyOf(newWatchBuffer, newWatchCount);
		if (newlyWatched.length > 0) {
			int pinResult = NativeMemoryEngine.pin(newlyWatched, true);
			if (pinResult != MemoryEngineContract.RESULT_OK) {
				return pinResult;
			}
		}
		ready = revalidateAgainIfGcMoved(token, candidateIds);
		if (ready != MemoryEngineContract.RESULT_OK) {
			if (newlyWatched.length > 0) NativeMemoryEngine.pin(newlyWatched, false);
			return ready;
		}
		long gcBeforeWrite = currentGcCount(token);
		int result = NativeMemoryEngine.freeze(
				candidateIds, mode, firstValue, secondValue);
		long gcAfterWrite = currentGcCount(token);
		if (MemoryGcPolicy.shouldReportGcRaceAfterMutation(
				result, gcBeforeWrite, gcAfterWrite)) {
			if (newlyWatched.length > 0) NativeMemoryEngine.pin(newlyWatched, false);
			refreshWithRecovery(token, candidateIds);
			refreshCachedBindings(candidateIds);
			return MemoryEngineContract.RESULT_GC_RACE;
		}
		if (result != MemoryEngineContract.RESULT_OK) {
			if (newlyWatched.length > 0) {
				NativeMemoryEngine.pin(newlyWatched, false);
			}
			return result;
		}
		long validatedGc = MemoryEngineContract.latestKnownGcCount(gcBeforeWrite, gcAfterWrite);
		gcBindings.markCandidatesValidated(candidateIds, validatedGc);
		refreshCachedBindings(candidateIds);
		for (long id : candidateIds) {
			freezeRecords.put(id,
					new FreezeRecord(mode, firstValue, secondValue, validatedGc));
		}
		startFreezeTaskIfNeeded();
		return MemoryEngineContract.RESULT_OK;
	}

	private static boolean isWatchedCandidate(long candidateId) {
		long[] rows = NativeMemoryEngine.watchPage();
		int count = validatedPageCount(rows);
		if (count < 0) {
			return false;
		}
		for (int index = 0; index < count; index++) {
			if (rows[1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE] == candidateId) {
				return true;
			}
		}
		return false;
	}

	private static int validatedPageCount(long[] rows) {
		if (rows == null || rows.length == 0 || rows[0] < 0L ||
				rows[0] > (rows.length - 1L) / MemoryEngineContract.RESULT_PAGE_STRIDE) {
			return -1;
		}
		int count = (int) rows[0];
		return 1 + count * MemoryEngineContract.RESULT_PAGE_STRIDE == rows.length ? count : -1;
	}

	private void startFreezeTaskIfNeeded() {
		ScheduledFuture<?> current = freezeTask;
		if (current == null || current.isDone()) {
			freezeTask = worker.scheduleWithFixedDelay(
					this::runFreezeTick, 750L, 750L, TimeUnit.MILLISECONDS);
		}
	}

	private void stopFreezeTaskIfIdle() {
		if (!MemoryManagedServicePolicy.freezeSchedulerIdle(
				freezeRecords.size(), managedFreezeCount)) {
			return;
		}
		ScheduledFuture<?> current = freezeTask;
		freezeTask = null;
		if (current != null) {
			current.cancel(false);
		}
	}

	private void runFreezeTick() {
		long token = configuredToken;
		if (token == 0L || !isCurrentToken(token)) {
			freezeRecords.clear();
			managedFreezeCount = 0;
			stopFreezeTaskIfIdle();
			return;
		}
		List<Map.Entry<Long, FreezeRecord>> active = new ArrayList<>();
		for (Map.Entry<Long, FreezeRecord> entry : freezeRecords.entrySet()) {
			if (!entry.getValue().paused) {
				active.add(entry);
			}
		}
		boolean managedActive = managedFreezeCount > 0;
		if (active.isEmpty() && !managedActive) {
			stopFreezeTaskIfIdle();
			return;
		}
		if (managedActive) synchronizeManagedCancelEpoch(token);
		long operationEpoch = cancelEpoch.get();
		if (managedActive) {
			IMemoryTargetBridge bridge = target;
			if (bridge == null) {
				managedFreezeCount = 0;
			} else {
				try {
					int managedResult = consumeManagedResult(token, bridge,
							bridge.managedFreezeTick(token, operationEpoch));
					if (managedResult == MemoryEngineContract.RESULT_TARGET_LOST) {
						managedFreezeCount = 0;
					}
				} catch (RemoteException ignored) {
					managedFreezeCount = 0;
				}
			}
		}
		if (active.isEmpty()) {
			stopFreezeTaskIfIdle();
			return;
		}
		// Raw FreezeRecords can coexist with a managed search/watch session. Keep the native
		// target configured from the last raw scope without publishing the session as raw or
		// passing any managed logical IDs to JNI.
		int rawReady = configureRawAuxiliary(token);
		if (rawReady != MemoryEngineContract.RESULT_OK) {
			return;
		}
		if (!NativeMemoryEngine.prepareOperation(operationEpoch)) {
			return;
		}

		long gcStart = currentGcCount(token);
		boolean anyRecordNeedsRecovery = false;
		for (Map.Entry<Long, FreezeRecord> entry : active) {
			if (MemoryGcPolicy.freezeRecordNeedsRecovery(
					entry.getValue().validatedGcCount, gcStart)) {
				anyRecordNeedsRecovery = true;
				break;
			}
		}
		if (anyRecordNeedsRecovery) {
			// Refresh the resident-range view once, then recover each stale CandidateId independently.
			// One ambiguous/lost value must not pause unrelated freezes.
			int configureResult = configureRawAuxiliary(token);
			if (configureResult != MemoryEngineContract.RESULT_OK) {
				for (Map.Entry<Long, FreezeRecord> entry : active) {
					entry.getValue().paused = true;
				}
				return;
			}
			long gcAfterConfigure = currentGcCount(token);
			if (MemoryEngineContract.didGcCountChange(gcStart, gcAfterConfigure)) {
				// The collector moved again while ranges were captured. Defer this tick rather than
				// converting a transient global race into permanently paused Freeze records.
				return;
			}
			gcStart = MemoryEngineContract.latestKnownGcCount(gcStart, gcAfterConfigure);
			for (Map.Entry<Long, FreezeRecord> entry : active) {
				if (cancelEpoch.get() != operationEpoch) return;
				FreezeRecord record = entry.getValue();
				if (!MemoryGcPolicy.freezeRecordNeedsRecovery(record.validatedGcCount, gcStart)) {
					continue;
				}
				long[] ids = new long[]{entry.getKey()};
				int recovery = NativeMemoryEngine.refresh(ids, true);
				if (recovery != MemoryEngineContract.RESULT_OK) {
					record.paused = true;
					continue;
				}
				long gcAfterRecovery = currentGcCount(token);
				if (MemoryEngineContract.didGcCountChange(gcStart, gcAfterRecovery)) {
					return;
				}
				refreshCachedBindings(ids);
				gcBindings.markCandidatesValidated(ids, gcStart);
				record.validatedGcCount = gcStart;
			}
		}

		List<Map.Entry<Long, FreezeRecord>> eligible = new ArrayList<>();
		for (Map.Entry<Long, FreezeRecord> entry : active) {
			if (!entry.getValue().paused) eligible.add(entry);
		}
		if (eligible.isEmpty()) return;
		long[] eligibleIds = new long[eligible.size()];
		for (int index = 0; index < eligible.size(); index++) {
			eligibleIds[index] = eligible.get(index).getKey();
		}

		int batchRefresh = NativeMemoryEngine.refresh(eligibleIds, false);
		for (Map.Entry<Long, FreezeRecord> entry : eligible) {
			if (cancelEpoch.get() != operationEpoch) {
				return;
			}
			if (MemoryEngineContract.didGcCountChange(gcStart, currentGcCount(token))) {
				// Stop before another write. The next tick will recover only records whose epoch is stale.
				return;
			}
			FreezeRecord record = entry.getValue();
			long[] ids = new long[]{entry.getKey()};
			// A failed batch is retried individually so one stale address cannot pause unrelated freezes.
			int result = batchRefresh == MemoryEngineContract.RESULT_OK
					? batchRefresh : NativeMemoryEngine.refresh(ids, false);
			if (result == MemoryEngineContract.RESULT_OK) {
				result = NativeMemoryEngine.freeze(
						ids, record.mode, record.firstValue, record.secondValue);
			}
			if (result != MemoryEngineContract.RESULT_OK) {
				record.paused = true;
			}
		}
		long gcEnd = currentGcCount(token);
		if (!MemoryEngineContract.didGcCountChange(gcStart, gcEnd)) {
			long validated = MemoryEngineContract.latestKnownGcCount(gcStart, gcEnd);
			long[] validatedBuffer = new long[eligible.size()];
			int validatedCount = 0;
			for (Map.Entry<Long, FreezeRecord> entry : eligible) {
				if (!entry.getValue().paused) {
					validatedBuffer[validatedCount++] = entry.getKey();
					entry.getValue().validatedGcCount = validated;
				}
			}
			if (validatedCount > 0) {
				long[] validatedIds = Arrays.copyOf(validatedBuffer, validatedCount);
				gcBindings.markCandidatesValidated(validatedIds, validated);
				refreshCachedBindings(validatedIds);
			}
		}
		// If GC changed during this tick, keep the older epoch. The next tick detects the mismatch
		// before another write and performs recovery first. Native identity checks remain the
		// immediate per-write safety net for the tick that raced the collector.
		stopFreezeTaskIfIdle();
	}

	private Bundle searchSessionInfo(long token) {
		Bundle bundle = new Bundle();
		boolean current = isCurrentToken(token);
		if (current && isManagedCurrent(token)) {
			IMemoryTargetBridge bridge = target;
			if (bridge != null) {
				long clearGeneration = nativeSearchClearGeneration.get();
				long operationEpoch = cancelEpoch.get();
				long expectedRevision = managedRevision;
				try {
					Bundle managed = bridge.getManagedSessionInfo(token);
					long runtimeAfterRpc = bridge.getRuntimeToken();
					synchronized (searchCommitLock) {
						int decision = managedSearchReplyDecisionLocked(token, bridge, managed,
								runtimeAfterRpc, clearGeneration, operationEpoch,
								expectedRevision);
						if (decision == MemoryEngineContract.RESULT_OK && managed != null) {
							updateManagedState(token, managed);
							bundle.putAll(managed);
						}
					}
				} catch (RemoteException ignored) {
					// Return the local session snapshot below when the target disappears mid-read.
				}
			}
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
					bundle.getInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
							MemoryEngineContract.SEARCH_SESSION_EMPTY));
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_MODE,
					bundle.getInt(MemoryEngineContract.KEY_SEARCH_MODE,
							MemoryEngineContract.SEARCH_MODE_KNOWN));
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
					bundle.getInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
							managedSearchType()));
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_SCOPE,
					MemoryEngineContract.SCOPE_MANAGED_JAVA);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, 0);
			bundle.putLong(MemoryEngineContract.KEY_GC_COUNT,
					MemoryEngineContract.GC_COUNT_UNKNOWN);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_BACKEND,
					MemoryEngineContract.BACKEND_MANAGED);
			return bundle;
		}
		int nativeHistoryDepth = current && !nativeSearchClearPending
				? NativeMemoryEngine.historyDepth() : 0;
		synchronized (searchSessionLock) {
			if (current) trimSearchHistoryLocked(nativeHistoryDepth);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
					current ? searchSessionStage : MemoryEngineContract.SEARCH_SESSION_EMPTY);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_MODE,
					current ? searchSessionMode : MemoryEngineContract.SEARCH_MODE_KNOWN);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
					current ? searchRequestedType : MemoryEngineContract.TYPE_AUTO);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_SCOPE,
					current ? searchSessionScope : MemoryEngineContract.SCOPE_JAVA_FAST);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH,
					current ? Math.min(nativeHistoryDepth, searchStageHistory.size()) : 0);
			bundle.putLong(MemoryEngineContract.KEY_GC_COUNT,
					current ? gcBindings.searchEpoch() : MemoryEngineContract.GC_COUNT_UNKNOWN);
			bundle.putInt(MemoryEngineContract.KEY_SEARCH_BACKEND,
					current && searchBackend == MemoryEngineContract.BACKEND_MANAGED
							? MemoryEngineContract.BACKEND_MANAGED : MemoryEngineContract.BACKEND_RAW);
		}
		return bundle;
	}

	private void resetSearchSession(int stage, int mode, int requestedType, int scope,
	                                long gcCount) {
		synchronized (searchSessionLock) {
			searchStageHistory.clear();
			searchGcHistory.clear();
			searchSessionStage = stage;
			searchSessionMode = mode;
			searchRequestedType = requestedType;
			searchSessionScope = scope;
			gcBindings.setSearchEpoch(gcCount);
		}
	}

	private void advanceSearchSession(int nextStage, long gcCount) {
		advanceSearchSession(nextStage, gcCount, Integer.MIN_VALUE);
	}

	private void advanceSearchSession(int nextStage, long gcCount, int requestedType) {
		int nativeHistoryDepth = NativeMemoryEngine.historyDepth();
		synchronized (searchSessionLock) {
			searchStageHistory.addLast(searchSessionStage);
			searchGcHistory.addLast(gcBindings.searchEpoch());
			trimSearchHistoryLocked(nativeHistoryDepth);
			searchSessionStage = nextStage;
			if (requestedType != Integer.MIN_VALUE) {
				searchRequestedType = requestedType;
			}
			gcBindings.setSearchEpoch(gcCount);
		}
	}

	private void synchronizeSearchHistoryDepth(int nativeDepth) {
		synchronized (searchSessionLock) {
			trimSearchHistoryLocked(nativeDepth);
		}
	}

	private void trimSearchHistoryLocked(int nativeDepth) {
		int boundedDepth = Math.max(0, Math.min(nativeDepth,
				MemoryEngineContract.MAX_SEARCH_HISTORY));
		while (searchStageHistory.size() > boundedDepth) {
			searchStageHistory.removeFirst();
		}
		while (searchGcHistory.size() > boundedDepth) {
			searchGcHistory.removeFirst();
		}
	}

	private void undoSearchSession() {
		synchronized (searchSessionLock) {
			if (!searchStageHistory.isEmpty()) searchSessionStage = searchStageHistory.removeLast();
			if (!searchGcHistory.isEmpty()) gcBindings.setSearchEpoch(searchGcHistory.removeLast());
		}
	}

	private void clearSearchSession() {
		synchronized (searchSessionLock) {
			searchStageHistory.clear();
			searchGcHistory.clear();
			searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
			searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
			searchRequestedType = MemoryEngineContract.TYPE_AUTO;
			searchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
			gcBindings.clearSearchEpoch();
		}
	}

	private boolean isCurrentToken(long token) {
		if (token == 0L || token != configuredToken) {
			return false;
		}
		return isTargetToken(token);
	}

	/** Enforces the one 128-row Watch capacity across both backends. */
	private boolean globalWatchCapacityAvailable(long token, @Nullable long[] requested) {
		if (requested == null || requested.length == 0
				|| requested.length > MemoryEngineContract.MAX_WATCH_RECORDS) return false;
		Set<Long> existing = new HashSet<>();
		long[] raw = NativeMemoryEngine.watchPage();
		int rawCount = validatedPageCount(raw);
		if (rawCount >= 0) {
			for (int index = 0; index < rawCount; index++) {
				existing.add(raw[1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE]);
			}
		}
		Bundle managed = managedWatchPage(token);
		long[] managedIds = watchLongs(managed, MemoryEngineContract.KEY_WATCH_IDS);
		for (long id : managedIds) existing.add(id);
		int additional = 0;
		Set<Long> unique = new HashSet<>();
		for (long value : requested) unique.add(value);
		for (long id : unique) {
			if (!existing.contains(id)) additional++;
		}
		return existing.size() + additional <= MemoryEngineContract.MAX_WATCH_RECORDS;
	}

	/** Enforces the one 32-row Freeze capacity across both backends. */
	private boolean globalFreezeCapacityAvailable(long token, @Nullable long[] requested) {
		if (requested == null || requested.length == 0
				|| requested.length > MemoryEngineContract.MAX_FREEZE_RECORDS) return false;
		IMemoryTargetBridge bridge = target;
		if (bridge != null) {
			try {
				Bundle state = bridge.getManagedCapabilities(token);
				if (!acceptManagedRuntimeState(token, bridge, state)) return false;
				updateManagedState(token, state);
			} catch (RemoteException ignored) {
				return false;
			}
		}
		Set<Long> frozen = new HashSet<>(freezeRecords.keySet());
		Bundle managed = managedWatchPage(token);
		long[] managedIds = watchLongs(managed, MemoryEngineContract.KEY_WATCH_IDS);
		int[] managedModes = managed == null ? null
				: managed.getIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES);
		if (managedModes != null && managedModes.length == managedIds.length) {
			for (int index = 0; index < managedIds.length; index++) {
				if (managedModes[index] >= MemoryEngineContract.FREEZE_LOCK) {
					frozen.add(managedIds[index]);
				}
			}
		}
		Set<Long> unique = new HashSet<>();
		for (long id : requested) unique.add(id);
		int additional = 0;
		for (long id : unique) if (!frozen.contains(id)) additional++;
		return frozen.size() + additional <= MemoryEngineContract.MAX_FREEZE_RECORDS;
	}

	private boolean isManagedCurrent(long token) {
		return searchBackend == MemoryEngineContract.BACKEND_MANAGED && isCurrentToken(token);
	}

	private static boolean containsManagedIds(@Nullable long[] ids) {
		if (ids == null) return false;
		for (long id : ids) if (ManagedJavaMemoryIds.isManaged(id)) return true;
		return false;
	}

	private static boolean allManagedIds(@Nullable long[] ids) {
		if (ids == null || ids.length == 0) return false;
		for (long id : ids) if (!ManagedJavaMemoryIds.hasValidNamespace(id)) return false;
		return true;
	}

	private boolean isTargetToken(long token) {
		IMemoryTargetBridge bridge = target;
		if (token == 0L || bridge == null) {
			return false;
		}
		try {
			return bridge.getRuntimeToken() == token;
		} catch (RemoteException exception) {
			return false;
		}
	}

	private long currentGcCount(long token) {
		IMemoryTargetBridge bridge = target;
		if (token == 0L || bridge == null) return MemoryEngineContract.GC_COUNT_UNKNOWN;
		try {
			if (bridge.getRuntimeToken() != token) return MemoryEngineContract.GC_COUNT_UNKNOWN;
			return bridge.getGcCount(token);
		} catch (RemoteException exception) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
	}

	private static boolean canReadProbe(int pid, long[] probe) {
		return pid > 0 && probe != null && probe.length == 2 && probe[0] > 0L &&
				NativeMemoryEngine.canReadTarget(pid, probe[0], probe[1]);
	}

	private static boolean canWriteProbe(int pid, long[] probe) {
		return pid > 0 && probe != null && probe.length == 2 && probe[0] > 0L &&
				NativeMemoryEngine.canWriteTarget(pid, probe[0], probe[1]);
	}

	private boolean isWriteSupported(long token) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) {
			return false;
		}
		try {
			return bridge.getRuntimeToken() == token &&
					canWriteProbe(bridge.getTargetPid(), bridge.getReadProbe(token));
		} catch (RemoteException exception) {
			return false;
		}
	}

	private void invalidateTarget() {
		long cancel;
		synchronized (searchCommitLock) {
			observedRuntimeToken = 0L;
			configuredToken = 0L;
			searchBackend = MemoryEngineContract.BACKEND_RAW;
			managedSupported = false;
			managedWriteSupported = false;
			managedStateToken = 0L;
			managedRevision = 0L;
			managedResultCount = 0L;
			managedBaselineCount = 0L;
			managedWatchCount = 0;
			managedFreezeCount = 0;
			managedLastMessage = null;
			nativeSearchClearGeneration.incrementAndGet();
			nativeSearchClearPending = false;
			clearSearchSession();
			gcBindings.clearAll();
			bindingCache.clear();
			watchLabels.clear();
			freezeRecords.clear();
			stopFreezeTaskIfIdle();
			cancel = cancelEpoch.incrementAndGet();
		}
		NativeMemoryEngine.cancel(cancel);
		try {
			worker.execute(NativeMemoryEngine::clearTarget);
		} catch (RejectedExecutionException ignored) {
			// Service teardown already clears native state directly.
		}
		notifyLocalRuntimeUnavailable();
	}

	private static String configurationFailureMessage(int result) {
		return switch (result) {
			case MemoryEngineContract.RESULT_UNSUPPORTED ->
					"Cross-process memory reads are not supported by this device/runtime";
			case MemoryEngineContract.RESULT_TARGET_LOST -> "MIDlet runtime changed or ended";
			case MemoryEngineContract.RESULT_RESOURCE_LIMIT ->
					"The complete resident range set exceeds the engine resource limit";
			default -> "Invalid memory engine target configuration";
		};
	}

	@Nullable
	private static String gcSafetyMessage(int result) {
		return switch (result) {
			case MemoryEngineContract.RESULT_GC_REVALIDATED ->
					"The selected value moved to a new verified address. Memory Editor updated the " +
							"CandidateId binding; the next guarded operation will use the refreshed address.";
			case MemoryEngineContract.RESULT_GC_RACE ->
					"Java GC occurred during the operation. The result or write could not be safely " +
							"confirmed, so no automatic retry was attempted.";
			case MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED ->
					"The address-based baseline is no longer safe to compare because managed memory " +
							"moved during or after capture. Capture a new baseline to continue.";
			default -> null;
		};
	}

	private void notifyFinished(long operationId, long token, int result,
	                            @Nullable String serviceMessage, boolean passiveRefresh,
	                            boolean searchOperation) {
		// Native operations are transactional. Cancellation or failure may leave
		// a valid previous result set, so do not present it as zero.
		boolean managedVisible = searchBackend == MemoryEngineContract.BACKEND_MANAGED
				&& configuredToken == token;
		long count = managedVisible ? managedResultCount
				: nativeSearchClearPending ? 0L : NativeMemoryEngine.resultCount();
		String message = serviceMessage != null ? serviceMessage
				: managedVisible ? managedLastMessage : NativeMemoryEngine.lastMessage();
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationFinished(operationId, result, count, message,
									passiveRefresh, searchOperation);
				} catch (RemoteException ignored) {
					// RemoteCallbackList removes dead clients.
				}
			}
		} finally {
			callbacks.finishBroadcast();
		}
	}

	private static Bundle emptyResultPage() {
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_RESULT_IDS, new long[0]);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_ADDRESSES, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_ALIAS_MASKS, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_TYPES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_STATES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_RELOCATIONS, new int[0]);
		return result;
	}

	private static Bundle emptyWatchPage() {
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_WATCH_IDS, new long[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_TYPES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_STATES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS, new int[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, new int[0]);
		result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, new boolean[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_BACKENDS, new int[0]);
		return result;
	}

	private static Bundle formatResultPage(long[] rows) {
		int count = validatedPageCount(rows);
		if (count < 0) {
			return emptyResultPage();
		}
		long[] ids = new long[count];
		String[] values = new String[count];
		String[] addresses = new String[count];
		int[] aliasMasks = new int[count];
		int[] types = new int[count];
		int[] states = new int[count];
		int[] relocations = new int[count];
		LinkedHashMap<Long, Integer> addressPositions = new LinkedHashMap<>();
		int output = 0;
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			long address = rows[base + 1];
			int type = (int) rows[base + 3];
			if (rows[base] <= 0L || !MemoryEngineContract.isCandidateType(type)) {
				return emptyResultPage();
			}
			Integer position = addressPositions.get(address);
			if (position == null) {
				position = output++;
				addressPositions.put(address, position);
				ids[position] = rows[base];
				values[position] = formatCandidateValue(type, rows[base + 8]);
				addresses[position] = "0x" + Long.toUnsignedString(address, 16).toUpperCase(Locale.ROOT);
				types[position] = type;
				states[position] = (int) rows[base + 4];
				relocations[position] = (int) rows[base + 5];
			}
			aliasMasks[position] |= 1 << type;
		}
		if (output != count) {
			ids = Arrays.copyOf(ids, output);
			values = Arrays.copyOf(values, output);
			addresses = Arrays.copyOf(addresses, output);
			aliasMasks = Arrays.copyOf(aliasMasks, output);
			types = Arrays.copyOf(types, output);
			states = Arrays.copyOf(states, output);
			relocations = Arrays.copyOf(relocations, output);
		}
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_RESULT_IDS, ids);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_VALUES, values);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_ADDRESSES, addresses);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_ALIAS_MASKS, aliasMasks);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_TYPES, types);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_STATES, states);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_RELOCATIONS, relocations);
		return result;
	}

	private Bundle formatWatchPage(long[] rows) {
		int count = validatedPageCount(rows);
		if (count < 0) {
			return emptyWatchPage();
		}
		long[] ids = new long[count];
		String[] values = new String[count];
		String[] initialValues = new String[count];
		String[] previousValues = new String[count];
		String[] addresses = new String[count];
		int[] types = new int[count];
		int[] states = new int[count];
		int[] relocations = new int[count];
		String[] labels = new String[count];
		int[] freezeModes = new int[count];
		boolean[] freezePaused = new boolean[count];
		int[] backends = new int[count];
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			long id = rows[base];
			int type = (int) rows[base + 3];
			if (id <= 0L || !MemoryEngineContract.isCandidateType(type)) {
				return emptyWatchPage();
			}
			ids[index] = id;
			values[index] = formatCandidateValue(type, rows[base + 8]);
			initialValues[index] = formatCandidateValue(type, rows[base + 6]);
			previousValues[index] = formatCandidateValue(type, rows[base + 7]);
			addresses[index] = "0x" + Long.toUnsignedString(rows[base + 1], 16)
					.toUpperCase(Locale.ROOT);
			types[index] = type;
			states[index] = (int) rows[base + 4];
			relocations[index] = (int) rows[base + 5];
			String label = watchLabels.get(id);
			labels[index] = label == null ? "" : label;
			FreezeRecord freeze = freezeRecords.get(id);
			freezeModes[index] = freeze == null ? -1 : freeze.mode;
			freezePaused[index] = freeze != null && freeze.paused;
			backends[index] = MemoryEngineContract.BACKEND_RAW;
		}
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_WATCH_IDS, ids);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_VALUES, values);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, initialValues);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, previousValues);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES, addresses);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_TYPES, types);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_STATES, states);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS, relocations);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, labels);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, freezeModes);
		result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, freezePaused);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_BACKENDS, backends);
		return result;
	}

	private static Bundle mergeWatchPages(Bundle raw, Bundle managed) {
		long[] rawIds = watchLongs(raw, MemoryEngineContract.KEY_WATCH_IDS);
		long[] managedIds = watchLongs(managed, MemoryEngineContract.KEY_WATCH_IDS);
		int rawCount = rawIds.length;
		int managedCount = managedIds.length;
		int total = rawCount + managedCount;
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_WATCH_IDS, concat(rawIds, managedIds));
		result.putStringArray(MemoryEngineContract.KEY_WATCH_VALUES, concat(
				watchStrings(raw, MemoryEngineContract.KEY_WATCH_VALUES, rawCount),
				watchStrings(managed, MemoryEngineContract.KEY_WATCH_VALUES, managedCount)));
		result.putStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, concat(
				watchStrings(raw, MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, rawCount),
				watchStrings(managed, MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, managedCount)));
		result.putStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, concat(
				watchStrings(raw, MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, rawCount),
				watchStrings(managed, MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, managedCount)));
		result.putStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES, concat(
				watchStrings(raw, MemoryEngineContract.KEY_WATCH_ADDRESSES, rawCount),
				watchStrings(managed, MemoryEngineContract.KEY_WATCH_ADDRESSES, managedCount)));
		result.putIntArray(MemoryEngineContract.KEY_WATCH_TYPES, concat(
				watchInts(raw, MemoryEngineContract.KEY_WATCH_TYPES, rawCount),
				watchInts(managed, MemoryEngineContract.KEY_WATCH_TYPES, managedCount)));
		result.putIntArray(MemoryEngineContract.KEY_WATCH_STATES, concat(
				watchInts(raw, MemoryEngineContract.KEY_WATCH_STATES, rawCount),
				watchInts(managed, MemoryEngineContract.KEY_WATCH_STATES, managedCount)));
		result.putIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS, concat(
				watchInts(raw, MemoryEngineContract.KEY_WATCH_RELOCATIONS, rawCount),
				watchInts(managed, MemoryEngineContract.KEY_WATCH_RELOCATIONS, managedCount)));
		result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, concat(
				watchStrings(raw, MemoryEngineContract.KEY_WATCH_LABELS, rawCount),
				watchStrings(managed, MemoryEngineContract.KEY_WATCH_LABELS, managedCount)));
		result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, concat(
				watchInts(raw, MemoryEngineContract.KEY_WATCH_FREEZE_MODES, rawCount),
				watchInts(managed, MemoryEngineContract.KEY_WATCH_FREEZE_MODES, managedCount)));
		result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, concat(
				watchBooleans(raw, rawCount), watchBooleans(managed, managedCount)));
		result.putIntArray(MemoryEngineContract.KEY_WATCH_BACKENDS, concat(
				watchBackends(raw, rawCount), watchBackends(managed, managedCount)));
		return result;
	}

	private static long[] watchLongs(@Nullable Bundle bundle, String key) {
		long[] values = bundle == null ? null : bundle.getLongArray(key);
		return values == null ? new long[0] : values;
	}

	private static String[] watchStrings(@Nullable Bundle bundle, String key, int expected) {
		String[] values = bundle == null ? null : bundle.getStringArray(key);
		return values != null && values.length == expected ? values : new String[expected];
	}

	private static int[] watchInts(@Nullable Bundle bundle, String key, int expected) {
		int[] values = bundle == null ? null : bundle.getIntArray(key);
		return values != null && values.length == expected ? values : new int[expected];
	}

	private static int[] watchBackends(@Nullable Bundle bundle, int expected) {
		int[] values = bundle == null ? null
				: bundle.getIntArray(MemoryEngineContract.KEY_WATCH_BACKENDS);
		if (values != null && values.length == expected) return values;
		int[] defaults = new int[expected];
		Arrays.fill(defaults, MemoryEngineContract.BACKEND_RAW);
		return defaults;
	}

	private static boolean[] watchBooleans(@Nullable Bundle bundle, int expected) {
		boolean[] values = bundle == null ? null
				: bundle.getBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED);
		return values != null && values.length == expected ? values : new boolean[expected];
	}

	private static long[] concat(long[] first, long[] second) {
		long[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private static String[] concat(String[] first, String[] second) {
		String[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private static int[] concat(int[] first, int[] second) {
		int[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private static boolean[] concat(boolean[] first, boolean[] second) {
		boolean[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private static String formatCandidateValue(int type, long bits) {
		return switch (type) {
			case MemoryEngineContract.TYPE_BYTE -> Byte.toString((byte) bits);
			case MemoryEngineContract.TYPE_SHORT -> Short.toString((short) bits);
			case MemoryEngineContract.TYPE_CHAR -> Integer.toString((int) bits & 0xffff);
			case MemoryEngineContract.TYPE_INT -> Integer.toString((int) bits);
			case MemoryEngineContract.TYPE_LONG -> Long.toString(bits);
			case MemoryEngineContract.TYPE_FLOAT -> Float.toString(Float.intBitsToFloat((int) bits));
			case MemoryEngineContract.TYPE_DOUBLE -> Double.toString(Double.longBitsToDouble(bits));
			default -> "?";
		};
	}

	private void notifyProgress(long operationId, long[] baseline, boolean searchOperation) {
		long[] progress = NativeMemoryEngine.scanProgress();
		if (progress == null || progress.length != 2 || progress[1] <= 0L) {
			return;
		}
		if (baseline != null && baseline.length == 2 &&
				progress[0] == baseline[0] && progress[1] == baseline[1]) {
			return;
		}
		long scannedBytes = Math.min(progress[0], progress[1]);
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationProgress(operationId, scannedBytes, progress[1], searchOperation);
				} catch (RemoteException ignored) {
					// RemoteCallbackList removes dead clients.
				}
			}
		} finally {
			callbacks.finishBroadcast();
		}
	}

	private interface NativeOperation {
		int run();
	}

	private static final class FreezeRecord {
		final int mode;
		final String firstValue;
		final String secondValue;
		volatile boolean paused;
		volatile long validatedGcCount;

		FreezeRecord(int mode, String firstValue, String secondValue, long validatedGcCount) {
			this.mode = mode;
			this.firstValue = firstValue == null ? "" : firstValue;
			this.secondValue = secondValue == null ? "" : secondValue;
			this.validatedGcCount = validatedGcCount;
		}
	}
}
