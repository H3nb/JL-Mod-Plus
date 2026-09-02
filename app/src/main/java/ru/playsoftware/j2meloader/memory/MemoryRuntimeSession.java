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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.util.ContextHolder;

/** Process-local identity for one live MIDlet runtime, independent from Activity visibility. */
public final class MemoryRuntimeSession {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final List<Listener> LISTENERS = new ArrayList<>();
	private static long activeToken;
	private static boolean engineBound;

	interface Listener {
		void onRuntimeEnded(long token);
	}

	/**
	 * Keeps :memory_engine alive for exactly the lifetime of the MIDlet runtime. UI visibility is
	 * deliberately not part of this ownership: hiding the editor must not tear down scan state, while
	 * ending the MIDlet must release the engine even if the editor panel is still visible for a frame.
	 */
	private static final ServiceConnection ENGINE_LIFECYCLE_CONNECTION = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// Lifetime ownership only. MemoryEditorComposeController owns the interactive Binder.
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			// Android keeps the binding and normally reconnects the crashed remote service.
		}

		@Override
		public void onBindingDied(ComponentName name) {
			rebindEngineIfRuntimeActive();
		}

		@Override
		public void onNullBinding(ComponentName name) {
			rebindEngineIfRuntimeActive();
		}
	};

	private MemoryRuntimeSession() {
	}

	public static synchronized long start() {
		if (activeToken != 0L) {
			ensureEngineBoundLocked();
			return activeToken;
		}
		long token;
		do {
			token = RANDOM.nextLong()
					^ SystemClock.elapsedRealtimeNanos()
					^ ((long) Process.myPid() << 32);
		} while (token == 0L);
		activeToken = token;
		ensureEngineBoundLocked();
		return token;
	}

	public static synchronized long currentToken() {
		return activeToken;
	}

	public static synchronized boolean isActive(long token) {
		return token != 0L && activeToken == token;
	}

	public static void close(long token) {
		Listener[] listeners;
		synchronized (MemoryRuntimeSession.class) {
			if (token == 0L || activeToken != token) {
				return;
			}
			activeToken = 0L;
			listeners = LISTENERS.toArray(new Listener[0]);
		}
		for (Listener listener : listeners) {
			try {
				listener.onRuntimeEnded(token);
			} catch (RuntimeException ignored) {
				// Runtime teardown must not be interrupted by an observer.
			}
		}
		releaseEngineBinding();
	}

	static synchronized void addListener(Listener listener) {
		if (!LISTENERS.contains(listener)) {
			LISTENERS.add(listener);
		}
	}

	static synchronized void removeListener(Listener listener) {
		LISTENERS.remove(listener);
	}

	private static void ensureEngineBoundLocked() {
		if (engineBound || activeToken == 0L) {
			return;
		}
		Context context = ContextHolder.getAppContext();
		try {
			engineBound = context.bindService(
					new Intent(context, MemoryEngineService.class),
					ENGINE_LIFECYCLE_CONNECTION,
					Context.BIND_AUTO_CREATE);
		} catch (RuntimeException ignored) {
			engineBound = false;
		}
	}

	private static void rebindEngineIfRuntimeActive() {
		Context context = ContextHolder.getAppContext();
		boolean shouldRebind;
		synchronized (MemoryRuntimeSession.class) {
			shouldRebind = activeToken != 0L;
			engineBound = false;
		}
		try {
			context.unbindService(ENGINE_LIFECYCLE_CONNECTION);
		} catch (IllegalArgumentException ignored) {
			// The dead binding may already have been removed by the framework.
		}
		if (!shouldRebind) {
			return;
		}
		synchronized (MemoryRuntimeSession.class) {
			if (activeToken != 0L) {
				ensureEngineBoundLocked();
			}
		}
	}

	private static void releaseEngineBinding() {
		Context context = ContextHolder.getAppContext();
		boolean shouldUnbind;
		synchronized (MemoryRuntimeSession.class) {
			shouldUnbind = engineBound;
			engineBound = false;
		}
		if (shouldUnbind) {
			try {
				context.unbindService(ENGINE_LIFECYCLE_CONNECTION);
			} catch (IllegalArgumentException ignored) {
				// A process/service death may have already removed the binding.
			}
		}
		// Harmless for a purely bound service, and guarantees an accidentally-started engine cannot
		// survive the MIDlet session after the last binding disappears.
		try {
			context.stopService(new Intent(context, MemoryEngineService.class));
		} catch (RuntimeException ignored) {
			// Process teardown is already authoritative.
		}
	}
}
