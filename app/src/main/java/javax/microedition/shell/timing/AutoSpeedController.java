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

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Session-scoped owner for manual/Auto speed transitions and their frame telemetry. */
public final class AutoSpeedController implements AutoCloseable {
	private static final long SAMPLE_MILLIS = 750L;

	private final TimingSession session;
	private final FrameMetrics frameMetrics = new FrameMetrics();
	private final AutoSpeedGovernor governor = new AutoSpeedGovernor();
	private ScheduledExecutorService sampler;

	private boolean autoEnabled;
	private boolean frameSourceActive;
	private boolean closed;
	private long samplerGeneration;
	private long previousSampleNanos;
	private long previousGameFrames;

	public AutoSpeedController(@NonNull TimingSession session) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		this.session = session;
	}

	@NonNull
	public FrameMetrics frameMetrics() {
		return frameMetrics;
	}

	public synchronized boolean isAutoEnabled() {
		return autoEnabled && !closed;
	}

	public int speedPercent() {
		return session.speedPercentOr(EmulationSpeed.NORMAL_PERCENT);
	}

	/** Enables Auto from a fresh 1x calibration for this game session. */
	public synchronized boolean enableAuto() {
		if (closed || session.isClosed()) {
			return false;
		}
		try {
			session.updateSpeedPercent(EmulationSpeed.NORMAL_PERCENT);
		} catch (IllegalStateException e) {
			return false;
		}
		autoEnabled = true;
		governor.reset();
		resetSampleWindow();
		startSampler();
		return true;
	}

	/** Applies a user-selected manual speed and atomically leaves Auto mode. */
	public synchronized boolean setManualSpeed(int speedPercent) {
		if (closed || session.isClosed() || !EmulationSpeed.isValidPercent(speedPercent)) {
			return false;
		}
		autoEnabled = false;
		stopSampler();
		try {
			session.updateSpeedPercent(speedPercent);
			return true;
		} catch (IllegalStateException e) {
			return false;
		}
	}

	/** Prevents menus, backgrounding, and surface replacement from being read as overload. */
	public synchronized void setFrameSourceActive(boolean active) {
		if (closed || frameSourceActive == active) {
			return;
		}
		frameSourceActive = active;
		resetSampleWindow();
	}

	private void sampleSafely(long generation) {
		try {
			sample(generation);
		} catch (RuntimeException ignored) {
			// Host diagnostics must never terminate or crash the guest session.
		}
	}

	private synchronized void sample(long generation) {
		if (generation != samplerGeneration) {
			return;
		}
		if (closed || !autoEnabled || !frameSourceActive || session.isClosed()) {
			resetSampleWindow();
			return;
		}
		long nowNanos = System.nanoTime();
		FrameMetricsSnapshot snapshot = frameMetrics.snapshot();
		long elapsedNanos = nowNanos - previousSampleNanos;
		long gameFrames = nonNegativeDelta(snapshot.gameFrames(), previousGameFrames);
		previousSampleNanos = nowNanos;
		previousGameFrames = snapshot.gameFrames();
		int currentPercent = session.speedPercentOr(EmulationSpeed.NORMAL_PERCENT);
		AutoSpeedGovernor.Decision decision = governor.sample(
				currentPercent, gameFrames, elapsedNanos);
		if (decision.speedPercent != currentPercent) {
			session.updateSpeedPercent(decision.speedPercent);
		}
	}

	private void resetSampleWindow() {
		FrameMetricsSnapshot snapshot = frameMetrics.snapshot();
		previousGameFrames = snapshot.gameFrames();
		previousSampleNanos = System.nanoTime();
	}

	private void startSampler() {
		if (sampler != null) {
			return;
		}
		long generation = ++samplerGeneration;
		sampler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "AutoEmulationSpeed");
				thread.setDaemon(true);
				return thread;
			}
		});
		sampler.scheduleWithFixedDelay(
				() -> sampleSafely(generation),
				SAMPLE_MILLIS,
				SAMPLE_MILLIS,
				TimeUnit.MILLISECONDS);
	}

	private void stopSampler() {
		samplerGeneration++;
		if (sampler != null) {
			sampler.shutdownNow();
			sampler = null;
		}
	}

	private static long nonNegativeDelta(long current, long previous) {
		return current >= previous ? current - previous : current;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		autoEnabled = false;
		frameSourceActive = false;
		stopSampler();
	}
}
