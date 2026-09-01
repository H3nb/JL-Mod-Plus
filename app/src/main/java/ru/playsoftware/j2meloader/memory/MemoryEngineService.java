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
	private static final int MAX_RUNS = 4096;
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
	private volatile int configuredScope = MemoryEngineContract.SCOPE_JAVA_FAST;
	private final Map<Long, String> watchLabels = new ConcurrentHashMap<>();
	private final Map<Long, FreezeRecord> freezeRecords = new ConcurrentHashMap<>();
	private volatile ScheduledFuture<?> freezeTask;
	private final Object searchSessionLock = new Object();
	private final ArrayDeque<Integer> searchStageHistory = new ArrayDeque<>();
	private int searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
	private int searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
	private int searchRequestedType = MemoryEngineContract.TYPE_AUTO;
	private int searchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
	private final IMemoryTargetCallback targetCallback = new IMemoryTargetCallback.Stub() {
		@Override
		public void onRuntimeEnded(long runtimeToken) {
			if (runtimeToken == configuredToken) {
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
				result.putString(MemoryEngineContract.KEY_MESSAGE, "MIDlet runtime is not connected");
				return result;
			}
			try {
				long token = bridge.getRuntimeToken();
				int pid = bridge.getTargetPid();
				int pageSize = bridge.getPageSize();
				long[] probe = token == 0L ? null : bridge.getReadProbe(token);
				boolean supported = token != 0L && pid > 0 && pageSize > 0 &&
						canReadProbe(pid, probe);
				boolean writeSupported = supported && canWriteProbe(pid, probe);
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, supported);
				result.putBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, writeSupported);
				result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, token);
				result.putInt(MemoryEngineContract.KEY_TARGET_PID, pid);
				result.putInt(MemoryEngineContract.KEY_PAGE_SIZE, pageSize);
				if (!supported) {
					result.putString(MemoryEngineContract.KEY_MESSAGE, token == 0L
							? "No active MIDlet runtime"
							: "Cross-process memory reads are not supported by this device/runtime");
				}
			} catch (RemoteException exception) {
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, false);
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
			return enqueue(token, true, scope, () -> {
				int result = NativeMemoryEngine.startKnown(type, predicate, first, second);
				if (result == MemoryEngineContract.RESULT_OK) {
					resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							MemoryEngineContract.SEARCH_MODE_KNOWN, type, scope);
				}
				return result;
			});
		}

		@Override
		public long startUnknownSearch(long token, int scope, int type) {
			return enqueue(token, true, scope, () -> {
				int result = NativeMemoryEngine.startUnknown(type);
				if (result == MemoryEngineContract.RESULT_OK) {
					resetSearchSession(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
							MemoryEngineContract.SEARCH_MODE_UNKNOWN, type, scope);
				}
				return result;
			});
		}

		@Override
		public long startGroupSearch(long token, int scope, int[] types,
		                             String[] values, int maxDistance) {
			return enqueue(token, true, scope, () -> {
				int result = NativeMemoryEngine.startGroup(types, values, maxDistance);
				if (result == MemoryEngineContract.RESULT_OK) {
					resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							MemoryEngineContract.SEARCH_MODE_GROUP,
							MemoryEngineContract.TYPE_AUTO, scope);
				}
				return result;
			});
		}

		@Override
		public long startNearbySearch(long token, long anchorCandidateId, int radius,
		                              int type, int predicate, String first, String second) {
			return enqueue(token, false, 0, () -> {
				if (anchorCandidateId <= 0L || !MemoryEngineContract.isNearbyRadius(radius)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				int ready = refreshWithRecovery(token, new long[]{anchorCandidateId});
				if (ready != MemoryEngineContract.RESULT_OK) {
					return ready;
				}
				int result = NativeMemoryEngine.startNearby(anchorCandidateId, radius, type, predicate,
						first, second);
				if (result == MemoryEngineContract.RESULT_OK) {
					resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
							MemoryEngineContract.SEARCH_MODE_KNOWN, type, configuredScope);
				}
				return result;
			});
		}

		@Override
		public long refineKnown(long token, int predicate, String first, String second) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.refineKnown(predicate, first, second);
				if (result != MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
					if (result == MemoryEngineContract.RESULT_OK) {
						advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
					}
					return result;
				}
				result = configureTarget(token, configuredScope);
				if (result == MemoryEngineContract.RESULT_OK) {
					result = NativeMemoryEngine.recoverKnown(predicate, first, second);
				}
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
				}
				return result;
			});
		}

		@Override
		public long refineRelative(long token, int predicate, int compareTarget,
		                           String first, String second) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.refineRelative(predicate, compareTarget, first, second);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
				}
				return result;
			});
		}

		@Override
		public long undoSearch(long token) {
			return enqueue(token, false, 0, () -> {
				synchronizeSearchHistoryDepth(NativeMemoryEngine.historyDepth());
				int result = NativeMemoryEngine.undo();
				if (result == MemoryEngineContract.RESULT_OK) undoSearchSession();
				return result;
			});
		}

		@Override
		public long refreshCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0, true,
					() -> NativeMemoryEngine.refresh(
							candidateIds == null ? new long[0] : candidateIds, false));
		}

		@Override
		public long removeCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.filter(candidateIds, false);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
				}
				return result;
			});
		}

		@Override
		public long keepCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.filter(candidateIds, true);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
				}
				return result;
			});
		}

		@Override
		public long editCandidates(long token, long[] candidateIds, String replacementValue) {
			return enqueue(token, false, 0, () -> {
				if (candidateIds == null || candidateIds.length == 0 ||
						candidateIds.length > MemoryEngineContract.MAX_MULTI_WRITE) {
					return MemoryEngineContract.RESULT_SAFETY_LIMIT;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				int ready = refreshWithRecovery(token, candidateIds);
				return ready == MemoryEngineContract.RESULT_OK
						? NativeMemoryEngine.edit(candidateIds, replacementValue) : ready;
			});
		}

		@Override
		public long getResultCount(long token) {
			return isCurrentToken(token) ? NativeMemoryEngine.resultCount() : 0L;
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
			return formatResultPage(NativeMemoryEngine.resultPage(offset, limit));
		}

		@Override
		public long filterResultGroups(long token, long[] resultIds, boolean keep) {
			return enqueue(token, false, 0, () -> {
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds,
						MemoryEngineContract.TYPE_AUTO);
				if (candidateIds == null || candidateIds.length == 0) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				int result = NativeMemoryEngine.filter(candidateIds, keep);
				if (result == MemoryEngineContract.RESULT_OK) {
					advanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
				}
				return result;
			});
		}

		@Override
		public long editResultGroups(long token, long[] resultIds, int valueType,
		                             String replacementValue) {
			return enqueue(token, false, 0, () -> {
				if (!MemoryEngineContract.isCandidateType(valueType)) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				long[] candidateIds = NativeMemoryEngine.expandResultGroups(resultIds, valueType);
				if (candidateIds == null || candidateIds.length == 0 ||
						candidateIds.length > MemoryEngineContract.MAX_MULTI_WRITE) {
					return MemoryEngineContract.RESULT_SAFETY_LIMIT;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				int ready = refreshWithRecovery(token, candidateIds);
				return ready == MemoryEngineContract.RESULT_OK
						? NativeMemoryEngine.edit(candidateIds, replacementValue) : ready;
			});
		}

		@Override
		public long editInspectorValue(long token, long anchorCandidateId, int relativeOffset,
		                               int valueType, long expectedBits,
		                               String replacementValue) {
			return enqueue(token, false, 0, () -> {
				if (anchorCandidateId <= 0L || !MemoryEngineContract.isCandidateType(valueType) ||
						Math.abs((long) relativeOffset) > MemoryEngineContract.MAX_INSPECT_RADIUS) {
					return MemoryEngineContract.RESULT_INVALID_REQUEST;
				}
				if (!isWriteSupported(token)) {
					return MemoryEngineContract.RESULT_UNSUPPORTED;
				}
				int ready = refreshWithRecovery(token, new long[]{anchorCandidateId});
				if (ready != MemoryEngineContract.RESULT_OK) {
					return ready;
				}
				int result = NativeMemoryEngine.editInspectorValue(
						anchorCandidateId, relativeOffset, valueType, expectedBits, replacementValue);
				if (result == MemoryEngineContract.RESULT_OK) {
					NativeMemoryEngine.refresh(new long[]{anchorCandidateId}, false);
				}
				return result;
			});
		}

		@Override
		public Bundle inspectCandidate(long token, long candidateId, int radius) {
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
			return formatWatchPage(NativeMemoryEngine.watchPage());
		}

		@Override
		public long addWatch(long token, long[] candidateIds) {
			return enqueue(token, false, 0,
					() -> NativeMemoryEngine.pin(candidateIds, true));
		}

		@Override
		public long removeWatch(long token, long[] candidateIds) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.pin(candidateIds, false);
				if (result == MemoryEngineContract.RESULT_OK && candidateIds != null) {
					for (long id : candidateIds) {
						watchLabels.remove(id);
						freezeRecords.remove(id);
					}
				}
				stopFreezeTaskIfIdle();
				return result;
			});
		}

		@Override
		public long setWatchLabel(long token, long candidateId, String label) {
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
			return enqueue(token, false, 0, () -> setFreezeRecords(
					token, candidateIds, mode, firstValue, secondValue));
		}

		@Override
		public long clearFreeze(long token, long[] candidateIds) {
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
			if (isCurrentToken(token)) {
				clearSearchSession();
				worker.execute(NativeMemoryEngine::clearSearch);
			}
		}

		@Override
		public void cancelOperation(long token) {
			if (token != 0L && (token == configuredToken || isTargetToken(token))) {
				NativeMemoryEngine.cancel(cancelEpoch.incrementAndGet());
			}
		}
	};

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
		if (targetBound) {
			unbindService(targetConnection);
			targetBound = false;
		}
		NativeMemoryEngine.clearTarget();
		super.onDestroy();
	}

	private long enqueue(long token, boolean configure, int scope, NativeOperation operation) {
		return enqueue(token, configure, scope, false, operation);
	}

	private long enqueue(long token, boolean configure, int scope, boolean passiveRefresh,
	                     NativeOperation operation) {
		long operationId = nextOperationId.getAndIncrement();
		long enqueueEpoch = cancelEpoch.get();
		worker.execute(() -> {
			ScheduledFuture<?> progressUpdates = progressNotifier.scheduleWithFixedDelay(
					() -> notifyProgress(operationId),
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
				} else if (!NativeMemoryEngine.prepareOperation(enqueueEpoch)) {
					result = MemoryEngineContract.RESULT_CANCELLED;
					serviceMessage = "Operation cancelled before it started";
				} else if (configure) {
					result = configureTarget(token, scope);
					if (result == MemoryEngineContract.RESULT_OK) {
						result = operation.run();
					} else {
						serviceMessage = configurationFailureMessage(result);
					}
				} else if (!isCurrentToken(token)) {
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

				if (result == MemoryEngineContract.RESULT_OK && !isCurrentToken(token)) {
					NativeMemoryEngine.clearTarget();
					configuredToken = 0L;
					result = MemoryEngineContract.RESULT_TARGET_LOST;
					serviceMessage = "MIDlet runtime changed during the operation";
				}
			} finally {
				progressUpdates.cancel(false);
			}
			notifyFinished(operationId, result, serviceMessage, passiveRefresh);
		});
		return operationId;
	}

	private int configureTarget(long token, int scope) {
		if (!MemoryEngineContract.isScope(scope)) {
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
			long[] runs = bridge.getResidentRuns(token, scope, MAX_RUNS);
			if (!MemoryEngineContract.isCompleteRunList(runs)) {
				if (bridge.getRuntimeToken() != token) {
					return MemoryEngineContract.RESULT_TARGET_LOST;
				}
				return MemoryEngineContract.RESULT_RESOURCE_LIMIT;
			}
			long previousToken = configuredToken;
			int result = NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
			if (result == MemoryEngineContract.RESULT_OK) {
				if (previousToken != token) clearSearchSession();
				configuredToken = token;
				configuredScope = scope;
			}
			return result;
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
		}
	}

	private int refreshWithRecovery(long token, long[] candidateIds) {
		long[] ids = candidateIds == null ? new long[0] : candidateIds;
		int result = configureTarget(token, configuredScope);
		return result == MemoryEngineContract.RESULT_OK
				? NativeMemoryEngine.refresh(ids, true) : result;
	}

	private Bundle inspectCandidateOnWorker(long token, long candidateId, int radius) {
		if (!isTargetToken(token)) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime changed or ended");
		}
		int ready = refreshWithRecovery(token, new long[]{candidateId});
		if (ready != MemoryEngineContract.RESULT_OK) {
			return inspectionFailure(ready, NativeMemoryEngine.lastMessage());
		}
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
		Set<Long> uniqueIds = new HashSet<>();
		if (candidateIds != null) {
			for (long id : candidateIds) {
				uniqueIds.add(id);
			}
		}
		int additionalRecords = 0;
		for (long id : uniqueIds) {
			if (!freezeRecords.containsKey(id)) {
				additionalRecords++;
			}
		}
		if (candidateIds == null || candidateIds.length == 0 ||
				candidateIds.length > MemoryEngineContract.MAX_FREEZE_RECORDS ||
				mode < MemoryEngineContract.FREEZE_LOCK ||
				mode > MemoryEngineContract.FREEZE_RANGE ||
				freezeRecords.size() + additionalRecords >
						MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return MemoryEngineContract.RESULT_SAFETY_LIMIT;
		}
		if (!isWriteSupported(token)) {
			return MemoryEngineContract.RESULT_UNSUPPORTED;
		}
		int ready = refreshWithRecovery(token, candidateIds);
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
		int result = NativeMemoryEngine.freeze(
				candidateIds, mode, firstValue, secondValue);
		if (result != MemoryEngineContract.RESULT_OK) {
			if (newlyWatched.length > 0) {
				NativeMemoryEngine.pin(newlyWatched, false);
			}
			return result;
		}
		for (long id : candidateIds) {
			freezeRecords.put(id,
					new FreezeRecord(mode, firstValue, secondValue));
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
		if (!freezeRecords.isEmpty()) {
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
			stopFreezeTaskIfIdle();
			return;
		}
		List<Map.Entry<Long, FreezeRecord>> active = new ArrayList<>();
		for (Map.Entry<Long, FreezeRecord> entry : freezeRecords.entrySet()) {
			if (!entry.getValue().paused) {
				active.add(entry);
			}
		}
		if (active.isEmpty()) {
			return;
		}
		long operationEpoch = cancelEpoch.get();
		if (!NativeMemoryEngine.prepareOperation(operationEpoch)) {
			return;
		}
		long[] activeIds = new long[active.size()];
		for (int index = 0; index < active.size(); index++) {
			activeIds[index] = active.get(index).getKey();
		}
		int batchRefresh = NativeMemoryEngine.refresh(activeIds, false);
		for (Map.Entry<Long, FreezeRecord> entry : active) {
			if (cancelEpoch.get() != operationEpoch) {
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
	}

	private Bundle searchSessionInfo(long token) {
		Bundle bundle = new Bundle();
		boolean current = isCurrentToken(token);
		int nativeHistoryDepth = current ? NativeMemoryEngine.historyDepth() : 0;
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
		}
		return bundle;
	}

	private void resetSearchSession(int stage, int mode, int requestedType, int scope) {
		synchronized (searchSessionLock) {
			searchStageHistory.clear();
			searchSessionStage = stage;
			searchSessionMode = mode;
			searchRequestedType = requestedType;
			searchSessionScope = scope;
		}
	}

	private void advanceSearchSession(int nextStage) {
		int nativeHistoryDepth = NativeMemoryEngine.historyDepth();
		synchronized (searchSessionLock) {
			searchStageHistory.addLast(searchSessionStage);
			trimSearchHistoryLocked(nativeHistoryDepth);
			searchSessionStage = nextStage;
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
	}

	private void undoSearchSession() {
		synchronized (searchSessionLock) {
			if (!searchStageHistory.isEmpty()) searchSessionStage = searchStageHistory.removeLast();
		}
	}

	private void clearSearchSession() {
		synchronized (searchSessionLock) {
			searchStageHistory.clear();
			searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
			searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
			searchRequestedType = MemoryEngineContract.TYPE_AUTO;
			searchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
		}
	}

	private boolean isCurrentToken(long token) {
		if (token == 0L || token != configuredToken) {
			return false;
		}
		return isTargetToken(token);
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
		configuredToken = 0L;
		clearSearchSession();
		watchLabels.clear();
		freezeRecords.clear();
		stopFreezeTaskIfIdle();
		NativeMemoryEngine.cancel(cancelEpoch.incrementAndGet());
		try {
			worker.execute(NativeMemoryEngine::clearTarget);
		} catch (RejectedExecutionException ignored) {
			// Service teardown already clears native state directly.
		}
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

	private void notifyFinished(long operationId, int result, @Nullable String serviceMessage,
	                            boolean passiveRefresh) {
		// Native operations are transactional. Cancellation or failure may leave
		// a valid previous result set, so do not present it as zero.
		long count = NativeMemoryEngine.resultCount();
		String message = serviceMessage == null ? NativeMemoryEngine.lastMessage() : serviceMessage;
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationFinished(operationId, result, count, message,
									passiveRefresh);
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

	private void notifyProgress(long operationId) {
		long[] progress = NativeMemoryEngine.scanProgress();
		if (progress == null || progress.length != 2 || progress[1] <= 0L) {
			return;
		}
		long scannedBytes = Math.min(progress[0], progress[1]);
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationProgress(operationId, scannedBytes, progress[1]);
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

		FreezeRecord(int mode, String firstValue, String secondValue) {
			this.mode = mode;
			this.firstValue = firstValue == null ? "" : firstValue;
			this.secondValue = secondValue == null ? "" : secondValue;
		}
	}
}
