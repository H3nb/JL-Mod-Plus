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

package javax.microedition.shell;

import androidx.annotation.Nullable;

import javax.microedition.shell.timing.TimingSession;

/**
 * Parent-classloader-owned ABI for transformed guest timing calls. Guest archives must never
 * provide or shadow this class.
 */
public final class GuestTimingBridge {
	public static final int ABI_VERSION = 1;

	private static final Object LOCK = new Object();
	private static TimingSession activeSession;

	private GuestTimingBridge() {
	}

	/** Installs the only active session for the current MIDlet process. */
	public static void install(TimingSession session) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		synchronized (LOCK) {
			if (activeSession != null && activeSession != session && !activeSession.isClosed()) {
				throw new IllegalStateException("A timing session is already active");
			}
			activeSession = session;
		}
	}

	/** Clears only the session that the caller owns, preventing stale teardown from clearing a new one. */
	public static void clear(@Nullable TimingSession session) {
		synchronized (LOCK) {
			if (activeSession == session) {
				activeSession = null;
				if (session != null) {
					session.close();
				}
			}
		}
	}

	@Nullable
	public static TimingSession activeSession() {
		synchronized (LOCK) {
			return activeSession;
		}
	}

	/** Replacement for transformed guest java.lang.System.currentTimeMillis(). */
	public static long currentTimeMillis() {
		TimingSession session = activeSession();
		return session == null ? System.currentTimeMillis() : session.guestWallTimeMillis();
	}

	/** Replacement for transformed guest java.lang.System.nanoTime(). */
	public static long nanoTime() {
		TimingSession session = activeSession();
		return session == null ? System.nanoTime() : session.guestMonotonicNanos();
	}

	/** Replacement for transformed guest java.lang.Thread.sleep(long). */
	public static void sleep(long guestMillis) throws InterruptedException {
		TimingSession session = activeSession();
		if (session == null) {
			Thread.sleep(guestMillis);
		} else {
			session.sleep(guestMillis);
		}
	}

	/** Replacement for transformed guest java.lang.Thread.sleep(long, int). */
	public static void sleep(long guestMillis, int guestNanos) throws InterruptedException {
		TimingSession session = activeSession();
		if (session == null) {
			Thread.sleep(guestMillis, guestNanos);
		} else {
			session.sleep(guestMillis, guestNanos);
		}
	}
}
