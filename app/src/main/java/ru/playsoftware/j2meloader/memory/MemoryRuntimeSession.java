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

import android.os.Process;
import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Process-local identity for one live MIDlet runtime, independent from Activity visibility. */
public final class MemoryRuntimeSession {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final List<Listener> LISTENERS = new ArrayList<>();
	private static long activeToken;

	interface Listener {
		void onRuntimeEnded(long token);
	}

	private MemoryRuntimeSession() {
	}

	public static synchronized long start() {
		if (activeToken != 0L) {
			return activeToken;
		}
		long token;
		do {
			token = RANDOM.nextLong()
					^ SystemClock.elapsedRealtimeNanos()
					^ ((long) Process.myPid() << 32);
		} while (token == 0L);
		activeToken = token;
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
	}

	static synchronized void addListener(Listener listener) {
		if (!LISTENERS.contains(listener)) {
			LISTENERS.add(listener);
		}
	}

	static synchronized void removeListener(Listener listener) {
		LISTENERS.remove(listener);
	}
}
