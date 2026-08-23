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

package javax.microedition.shell.timing;

import java.util.concurrent.locks.LockSupport;

/**
 * Session-scoped host pacer for a compatibility FPS ceiling.
 *
 * <p>The configured compatibility limit is the base rate {@code C}. Guest time speed scales the
 * permitted host rate, so the effective ceiling is {@code C * S}. The pacer uses host monotonic
 * time and never calls transformed guest sleep. Callers may explicitly disallow blocking when
 * they are executing an LCDUI callback.</p>
 */
public final class FramePacer {
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final long PERCENT_DENOMINATOR = 100L;

	interface HostSleeper {
		void parkNanos(Object blocker, long nanos);
	}

	private static final HostSleeper SYSTEM_SLEEPER = LockSupport::parkNanos;

	private final TimingSession session;
	private final HostSleeper sleeper;
	private final long createdAtNanos;

	private long nextDeadlineNanos;
	private int previousFps;
	private int previousSpeedPercent;
	private boolean initialized;

	public FramePacer(TimingSession session) {
		this(session, SYSTEM_SLEEPER);
	}

	FramePacer(TimingSession session, HostSleeper sleeper) {
		if (sleeper == null) {
			throw new NullPointerException("sleeper");
		}
		this.session = session;
		this.sleeper = sleeper;
		long initialHostNanos;
		try {
			initialHostNanos = hostNowNanos();
		} catch (IllegalStateException closed) {
			initialHostNanos = SystemTimingTimeSource.INSTANCE.monotonicNanos();
		}
		createdAtNanos = initialHostNanos;
	}

	/**
	 * Paces one guest frame request. A non-positive FPS value disables and resets the pacer. When
	 * {@code allowBlocking} is false, the request still advances the schedule but never parks the
	 * caller; the next guest worker request can absorb the remaining interval.
	 */
	public synchronized void pace(int compatibilityFps, boolean allowBlocking) {
		if (compatibilityFps <= 0) {
			reset();
			return;
		}
		if (session != null && session.isClosed()) {
			reset();
			return;
		}

		long now;
		int speedPercent;
		try {
			now = hostNowNanos();
			speedPercent = speedPercent();
		} catch (IllegalStateException closed) {
			reset();
			return;
		}
		long intervalNanos = intervalNanos(compatibilityFps, speedPercent);
		if (!initialized || previousFps != compatibilityFps
				|| previousSpeedPercent != speedPercent) {
			previousFps = compatibilityFps;
			previousSpeedPercent = speedPercent;
			if (!initialized) {
				nextDeadlineNanos = saturatingAdd(createdAtNanos, intervalNanos);
				initialized = true;
			} else {
				// A runtime speed/FPS transition is effective at the next request and should not
				// retain a deadline computed with the old rate.
				nextDeadlineNanos = now;
			}
		}

		long deadline = nextDeadlineNanos;
		boolean interrupted = false;
		boolean registered = false;
		Thread pacingThread = null;
		if (allowBlocking && deadline > now) {
			// Inspect without consuming the flag. A guest worker may use interruption as its
			// shutdown signal, and pacing must not erase that signal while checking whether it
			// is safe to park.
			interrupted = Thread.currentThread().isInterrupted();
			if (!interrupted) {
				pacingThread = Thread.currentThread();
				if (session != null) {
					session.registerCloseAwareThread(pacingThread);
					registered = true;
				}
				try {
					while (deadline > now) {
						sleeper.parkNanos(this, deadline - now);
						interrupted = Thread.currentThread().isInterrupted();
						if (interrupted) {
							break;
						}
						now = hostNowNanos();
					}
				} catch (IllegalStateException closed) {
					reset();
					return;
				} finally {
					if (registered) {
						session.unregisterCloseAwareThread(pacingThread);
					}
				}
			}
		}

		if (now < deadline) {
			// A callback is intentionally not blocked, or the caller was interrupted. Preserve the
			// cadence so a later worker call cannot create an unbounded burst.
			nextDeadlineNanos = saturatingAdd(deadline, intervalNanos);
		} else {
			nextDeadlineNanos = saturatingAdd(now, intervalNanos);
		}
	}

	/** Resets the schedule without changing the session or the injected host clock. */
	public synchronized void reset() {
		initialized = false;
		previousFps = 0;
		previousSpeedPercent = 0;
		nextDeadlineNanos = 0L;
	}

	static long intervalNanos(int compatibilityFps, int speedPercent) {
		if (compatibilityFps <= 0 || speedPercent <= 0) {
			return 0L;
		}
		long effectiveRate = (long) compatibilityFps * speedPercent;
		long numerator = NANOS_PER_SECOND * PERCENT_DENOMINATOR;
		long interval = numerator / effectiveRate;
		if (numerator % effectiveRate != 0L) {
			interval++;
		}
		return Math.max(1L, interval);
	}

	private long hostNowNanos() {
		return session == null
				? SystemTimingTimeSource.INSTANCE.monotonicNanos()
				: session.snapshot().hostMonotonicNanos();
	}

	private int speedPercent() {
		return session == null ? EmulationSpeed.NORMAL_PERCENT : session.speedPercent();
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}
}
