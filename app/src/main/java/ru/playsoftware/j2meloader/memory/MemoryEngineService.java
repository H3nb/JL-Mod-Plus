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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Owns all scan state in a dedicated app process and exposes only logical candidate IDs. */
public final class MemoryEngineService extends Service {
	private static final int MAX_RUNS = 4096;

	private final AtomicLong nextOperationId = new AtomicLong(1L);
	private final AtomicLong cancelEpoch = new AtomicLong();
	private final RemoteCallbackList<IMemoryEngineCallback> callbacks = new RemoteCallbackList<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MemoryEditorEngine");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private volatile IMemoryTargetBridge target;
	private volatile boolean targetBound;
	private volatile long configuredToken;
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
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, supported);
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
		public long refineKnown(long token, int predicate, String first, String second) {
			return enqueue(token, false, 0,
					() -> NativeMemoryEngine.refineKnown(predicate, first, second));
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
		public void clearSearch(long token) {
			if (isCurrentToken(token)) {
				worker.execute(NativeMemoryEngine::clear);
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
		NativeMemoryEngine.clear();
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
				NativeMemoryEngine.clear();
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
			}
			return result;
		} catch (RemoteException exception) {
			return MemoryEngineContract.RESULT_TARGET_LOST;
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

	private void invalidateTarget() {
		configuredToken = 0L;
		NativeMemoryEngine.cancel();
		try {
			worker.execute(NativeMemoryEngine::clear);
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
		long count = result == MemoryEngineContract.RESULT_OK ? NativeMemoryEngine.resultCount() : 0L;
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
}
