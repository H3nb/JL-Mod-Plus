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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Owns all scan state in a dedicated app process and exposes only logical candidate IDs. */
public final class MemoryEngineService extends Service {
	private static final int MAX_RUNS = 4096;

	private final AtomicLong nextOperationId = new AtomicLong(1L);
	private final AtomicLong cancelEpoch = new AtomicLong();
	private final RemoteCallbackList<IMemoryEngineCallback> callbacks = new RemoteCallbackList<>();
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MemoryEditorEngine");
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
			return enqueue(token, true, scope,
					() -> NativeMemoryEngine.startKnown(type, predicate, first, second));
		}

		@Override
		public long startUnknownSearch(long token, int scope, int type) {
			return enqueue(token, true, scope, () -> NativeMemoryEngine.startUnknown(type));
		}

		@Override
		public long startGroupSearch(long token, int scope, int[] types,
		                             String[] values, int maxDistance) {
			return enqueue(token, true, scope,
					() -> NativeMemoryEngine.startGroup(types, values, maxDistance));
		}

		@Override
		public long refineKnown(long token, int predicate, String first, String second) {
			return enqueue(token, false, 0, () -> {
				int result = NativeMemoryEngine.refineKnown(predicate, first, second);
				if (result != MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
					return result;
				}
				result = configureTarget(token, configuredScope);
				return result == MemoryEngineContract.RESULT_OK
						? NativeMemoryEngine.recoverKnown(predicate, first, second)
						: result;
			});
		}

		@Override
		public long refineRelative(long token, int predicate, int compareTarget,
		                           String first, String second) {
			return enqueue(token, false, 0,
					() -> NativeMemoryEngine.refineRelative(predicate, compareTarget, first, second));
		}

		@Override
		public long undoSearch(long token) {
			return enqueue(token, false, 0, NativeMemoryEngine::undo);
		}

		@Override
		public long refreshCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0,
					() -> refreshWithRecovery(token, candidateIds));
		}

		@Override
		public long removeCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0,
					() -> NativeMemoryEngine.filter(candidateIds, false));
		}

		@Override
		public long keepCandidates(long token, long[] candidateIds) {
			return enqueue(token, false, 0,
					() -> NativeMemoryEngine.filter(candidateIds, true));
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
		public long[] getResultPage(long token, int offset, int limit) {
			if (!isCurrentToken(token) || offset < 0 || limit <= 0 ||
					limit > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
				return new long[]{0L};
			}
			long[] result = NativeMemoryEngine.resultPage(offset, limit);
			return result == null ? new long[]{0L} : result;
		}

		@Override
		public Bundle getWatchPage(long token) {
			Bundle result = new Bundle();
			if (!isCurrentToken(token)) {
				result.putLongArray(MemoryEngineContract.KEY_WATCH_ROWS, new long[]{0L});
				result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, new String[0]);
				result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, new int[0]);
				result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, new boolean[0]);
				return result;
			}
			long[] rows = NativeMemoryEngine.watchPage();
			int count = validatedPageCount(rows);
			if (count < 0) {
				rows = new long[]{0L};
				count = 0;
			}
			String[] labels = new String[count];
			int[] freezeModes = new int[count];
			boolean[] freezePaused = new boolean[count];
			for (int index = 0; index < count; index++) {
				long id = rows[1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE];
				String label = watchLabels.get(id);
				labels[index] = label == null ? "" : label;
				FreezeRecord freeze = freezeRecords.get(id);
				freezeModes[index] = freeze == null ? -1 : freeze.mode;
				freezePaused[index] = freeze != null && freeze.paused;
			}
			result.putLongArray(MemoryEngineContract.KEY_WATCH_ROWS,
					rows == null ? new long[]{0L} : rows);
			result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, labels);
			result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, freezeModes);
			result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, freezePaused);
			return result;
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
				worker.execute(NativeMemoryEngine::clearSearch);
			}
		}

		@Override
		public void cancelOperation(long token) {
			if (token != 0L && (token == configuredToken || isTargetToken(token))) {
				cancelEpoch.incrementAndGet();
				NativeMemoryEngine.cancel();
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
		NativeMemoryEngine.cancel();
		worker.shutdownNow();
		callbacks.kill();
		if (targetBound) {
			unbindService(targetConnection);
			targetBound = false;
		}
		NativeMemoryEngine.clearTarget();
		super.onDestroy();
	}

	private long enqueue(long token, boolean configure, int scope, NativeOperation operation) {
		long operationId = nextOperationId.getAndIncrement();
		long enqueueEpoch = cancelEpoch.get();
		worker.execute(() -> {
			int result;
			String serviceMessage = null;
			if (enqueueEpoch != cancelEpoch.get()) {
				result = MemoryEngineContract.RESULT_CANCELLED;
				serviceMessage = "Operation cancelled before it started";
			} else if (token == 0L) {
				result = MemoryEngineContract.RESULT_NO_SESSION;
				serviceMessage = "No active MIDlet runtime";
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

			if (result == MemoryEngineContract.RESULT_OK && !isCurrentToken(token)) {
				NativeMemoryEngine.clearTarget();
				configuredToken = 0L;
				result = MemoryEngineContract.RESULT_TARGET_LOST;
				serviceMessage = "MIDlet runtime changed during the operation";
			}
			notifyFinished(operationId, result, serviceMessage);
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
			int result = NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
			if (result == MemoryEngineContract.RESULT_OK) {
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
		int result = NativeMemoryEngine.refresh(ids, false);
		if (result != MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
			return result;
		}
		result = configureTarget(token, configuredScope);
		return result == MemoryEngineContract.RESULT_OK
				? NativeMemoryEngine.refresh(ids, true) : result;
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
		for (Map.Entry<Long, FreezeRecord> entry : freezeRecords.entrySet()) {
			FreezeRecord record = entry.getValue();
			if (record.paused) {
				continue;
			}
			long[] ids = new long[]{entry.getKey()};
			int result = refreshWithRecovery(token, ids);
			if (result == MemoryEngineContract.RESULT_OK) {
				result = NativeMemoryEngine.freeze(
						ids, record.mode, record.firstValue, record.secondValue);
			}
			if (result != MemoryEngineContract.RESULT_OK) {
				record.paused = true;
			}
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
		watchLabels.clear();
		freezeRecords.clear();
		stopFreezeTaskIfIdle();
		NativeMemoryEngine.cancel();
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

	private void notifyFinished(long operationId, int result, @Nullable String serviceMessage) {
		// Native operations are transactional. Cancellation or failure may leave
		// a valid previous result set, so do not present it as zero.
		long count = NativeMemoryEngine.resultCount();
		String message = serviceMessage == null ? NativeMemoryEngine.lastMessage() : serviceMessage;
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationFinished(operationId, result, count, message);
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
