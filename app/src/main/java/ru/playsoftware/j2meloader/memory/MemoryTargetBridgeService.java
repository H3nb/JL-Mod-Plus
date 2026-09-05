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
import android.content.Intent;
import android.os.Debug;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

/** Minimal :midlet bridge for runtime identity and target-local mincore collection. */
public final class MemoryTargetBridgeService extends Service {
	private static final String ART_GC_COUNT_STAT = "art.gc.gc-count";
	private static final long[] EMPTY_RUNS = new long[]{0L, 0L};
	private final Object rangeLock = new Object();
	private final RemoteCallbackList<IMemoryTargetCallback> callbacks = new RemoteCallbackList<>();
	private final MemoryRuntimeSession.Listener runtimeListener = this::notifyRuntimeEnded;

	private final IMemoryTargetBridge.Stub binder = new IMemoryTargetBridge.Stub() {
		@Override
		public void registerTargetCallback(IMemoryTargetCallback callback) {
			if (callback != null) {
				callbacks.register(callback);
			}
		}

		@Override
		public void unregisterTargetCallback(IMemoryTargetCallback callback) {
			if (callback != null) {
				callbacks.unregister(callback);
			}
		}

		@Override
		public long getRuntimeToken() {
			return MemoryRuntimeSession.currentToken();
		}

		@Override
		public int getTargetPid() {
			return Process.myPid();
		}

		@Override
		public int getPageSize() {
			return NativeMemoryTarget.pageSize();
		}

		@Override
		public long getGcCount(long runtimeToken) {
			return MemoryRuntimeSession.isActive(runtimeToken)
					? readGcCount() : MemoryEngineContract.GC_COUNT_UNKNOWN;
		}

		@Override
		public long[] getReadProbe(long runtimeToken) {
			if (!MemoryRuntimeSession.isActive(runtimeToken)) {
				return new long[0];
			}
			long[] probe = NativeMemoryTarget.readProbe();
			return probe == null ? new long[0] : probe;
		}

		@Override
		public long[] getResidentRuns(long runtimeToken, int scope, int maxRuns) {
			if (!MemoryRuntimeSession.isActive(runtimeToken)
					|| !MemoryEngineContract.isScope(scope)
					|| maxRuns <= 0 || maxRuns > MemoryEngineContract.MAX_RESIDENT_RUNS) {
				return EMPTY_RUNS;
			}
			synchronized (rangeLock) {
				long[] runs = NativeMemoryTarget.collectResidentRuns(scope, maxRuns);
				return runs == null ? EMPTY_RUNS : runs;
			}
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		MemoryRuntimeSession.addListener(runtimeListener);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		MemoryRuntimeSession.removeListener(runtimeListener);
		callbacks.kill();
		super.onDestroy();
	}

	static long readGcCount() {
		try {
			return parseGcCount(Debug.getRuntimeStat(ART_GC_COUNT_STAT));
		} catch (RuntimeException exception) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
	}

	static long parseGcCount(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
		try {
			long count = Long.parseLong(value);
			return count >= 0L ? count : MemoryEngineContract.GC_COUNT_UNKNOWN;
		} catch (NumberFormatException exception) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
	}

	private void notifyRuntimeEnded(long token) {
		int count = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < count; index++) {
				try {
					callbacks.getBroadcastItem(index).onRuntimeEnded(token);
				} catch (RemoteException ignored) {
					// RemoteCallbackList removes dead clients.
				}
			}
		} finally {
			callbacks.finishBroadcast();
		}
	}
}
