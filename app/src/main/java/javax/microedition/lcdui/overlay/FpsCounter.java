/*
 * Copyright 2019 Yury Kharchenko
 * Modified in 2026 for guest/render frame telemetry.
 *
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
package javax.microedition.lcdui.overlay;

import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.shell.timing.AutoSpeedController;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.FrameMetrics;
import javax.microedition.shell.timing.FrameMetricsSnapshot;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;

public class FpsCounter extends TimerTask implements Layer {

	private final View view;
	private final String frameRateFormat;
	private final int pillBackgroundColor;
	private final int pillContentColor;
	private volatile String previousFrameRate;
	private final FrameMetrics metrics;
	private final AutoSpeedController speedController;
	private final Timer timer;
	private FrameMetricsSnapshot previousSnapshot;
	private long previousSampleNanos;

	public FpsCounter(
			View view, FrameMetrics metrics, AutoSpeedController speedController) {
		this.view = view;
		this.metrics = metrics;
		this.speedController = speedController;
		frameRateFormat = ContextHolder.getAppContext().getString(R.string.fps_overlay_value);
		pillBackgroundColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_surface);
		pillContentColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_content);
		previousSnapshot = metrics.snapshot();
		previousSampleNanos = System.nanoTime();
		previousFrameRate = format(0L, 0L, 0L);
		timer = new Timer("FpsCounter", true);
		// Avoid catch-up bursts after a cached process resumes on Android 16.
		timer.schedule(this, 0, 1000);
	}

	public void run() {
		long nowNanos = System.nanoTime();
		FrameMetricsSnapshot snapshot = metrics.snapshot();
		long elapsedNanos = nowNanos - previousSampleNanos;
		long gameFrames = delta(snapshot.gameFrames(), previousSnapshot.gameFrames());
		long renderFrames = delta(snapshot.renderFrames(), previousSnapshot.renderFrames());
		long coalescedFrames = delta(
				snapshot.coalescedFrames(), previousSnapshot.coalescedFrames());
		previousSnapshot = snapshot;
		previousSampleNanos = nowNanos;
		previousFrameRate = format(
				ratePerSecond(gameFrames, elapsedNanos),
				ratePerSecond(renderFrames, elapsedNanos),
				dropPercent(gameFrames, coalescedFrames));
		view.postInvalidate();
	}

	private String format(long gameFrames, long renderFrames, long dropPercent) {
		String speed = "N/A";
		if (speedController != null) {
			speed = EmulationSpeed.formatRuntimeMultiplier(speedController.speedPercent());
			if (speedController.isAutoEnabled()) {
				speed = "AUTO " + speed;
			}
		}
		return String.format(
				java.util.Locale.ROOT,
				frameRateFormat,
				gameFrames,
				renderFrames,
				dropPercent,
				speed);
	}

	private static long ratePerSecond(long count, long elapsedNanos) {
		if (count <= 0L || elapsedNanos <= 0L) {
			return 0L;
		}
		return Math.round(count * 1_000_000_000d / elapsedNanos);
	}

	private static long dropPercent(long gameFrames, long coalescedFrames) {
		if (gameFrames <= 0L || coalescedFrames <= 0L) {
			return 0L;
		}
		return Math.min(100L, Math.round(coalescedFrames * 100d / gameFrames));
	}

	private static long delta(long current, long previous) {
		return current >= previous ? current - previous : current;
	}

	public void paint(CanvasWrapper g) {
		g.drawPillBackgroundedText(
				previousFrameRate,
				pillBackgroundColor,
				pillContentColor,
				DiagnosticOverlayLayout.PILL_SCALE,
				DiagnosticOverlayLayout.left(view),
				DiagnosticOverlayLayout.rowTop(view, g, 0));
	}

	public void stop() {
		timer.cancel();
	}
}
