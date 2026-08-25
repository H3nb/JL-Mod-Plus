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

package javax.microedition.lcdui.overlay;

import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingSnapshot;
import javax.microedition.shell.timing.TimingTelemetry;
import javax.microedition.shell.timing.TimingTelemetrySnapshot;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;

/**
 * Host-only timing diagnostic. Its timer invalidates this overlay view only; it never requests a
 * guest repaint and therefore cannot become a hidden frame-pacing loop.
 */
public final class TimingMonitor extends TimerTask implements Layer {
	private static final long UPDATE_PERIOD_MILLIS = 1000L;

	private final View view;
	private final TimingSession session;
	private final int diagnosticRow;
	private final int pillBackgroundColor;
	private final int pillContentColor;
	private final TimingTelemetry telemetry = new TimingTelemetry();
	private final Timer timer;

	public TimingMonitor(View view, boolean fpsVisible) {
		this.view = view;
		this.session = GuestTimingBridge.activeSession();
		this.diagnosticRow = fpsVisible ? 1 : 0;
		pillBackgroundColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_surface);
		pillContentColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_content);
		timer = new Timer("TimingMonitor", true);
		// Keep diagnostics responsive without coupling their cadence to guest rendering.
		timer.schedule(this, 0L, UPDATE_PERIOD_MILLIS);
	}

	@Override
	public void run() {
		view.postInvalidate();
	}

	@Override
	public void paint(CanvasWrapper g) {
		if (session == null || session.isClosed()) {
			return;
		}

		TimingTelemetrySnapshot sample;
		try {
			TimingSnapshot snapshot = session.snapshot();
			sample = telemetry.sample(snapshot);
		} catch (IllegalStateException ignored) {
			return;
		}

		String measured = sample.hasMeasuredPercent()
				? EmulationSpeed.formatMeasuredMultiplier(sample.measuredPercent())
				: "—";
		String text = ContextHolder.getAppContext().getString(
				R.string.emulation_speed_overlay,
				EmulationSpeed.formatMultiplier(sample.targetPercent()),
				measured);

		g.drawPillBackgroundedText(
				text,
				pillBackgroundColor,
				pillContentColor,
				DiagnosticOverlayLayout.PILL_SCALE,
				DiagnosticOverlayLayout.left(view),
				DiagnosticOverlayLayout.rowTop(view, g, diagnosticRow));
	}

	public void stop() {
		timer.cancel();
		telemetry.reset();
	}
}
