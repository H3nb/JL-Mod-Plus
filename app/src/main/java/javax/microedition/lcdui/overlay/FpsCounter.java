/*
 * Copyright 2019 Yury Kharchenko
 * Modified for JL-Mod Plus.
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

import static io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions.*;

import android.graphics.RectF;
import android.view.View;
import androidx.core.content.ContextCompat;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingSnapshot;
import javax.microedition.shell.timing.FrameMetrics;
import javax.microedition.shell.timing.FrameMetricsSnapshot;
import javax.microedition.shell.timing.PerformanceDiagnostics;
import io.github.h3nb.jlmodplus.R;

/** Host diagnostics: sampling/formatting off the UI thread, drawing without guest input ownership. */
public class FpsCounter extends TimerTask implements Layer {
	private final View view;
	private final Canvas owner;
	private final FrameMetrics metrics;
	private final TimingSession timingSession;
	private final PerformanceResources resources;
	private final int mask, position, contentColor;
	private final Timer timer;
	private volatile String[][] groups = new String[0][];
	private boolean stopped;
	private FrameMetricsSnapshot previousSnapshot;
	private long previousSampleNanos, previousGeneration = Long.MIN_VALUE;
	private double fps = Double.NaN, guestFps = Double.NaN, coalesced = Double.NaN;
	// UI-thread layout cache; reflow only when text or available bounds change.
	private String[][] laidOutGroups;
	private String[] rows = new String[0];
	private float layoutWidth, layoutHeight, columnWidth;
	private int columnRows;
	private final RectF drawingBounds = new RectF();
	private final int[] viewLocation = new int[2], rootLocation = new int[2];

	public FpsCounter(View view, FrameMetrics metrics, TimingSession timingSession,
			Canvas owner, int mask, int position) {
		this.view = view;
		this.metrics = metrics;
		this.timingSession = timingSession;
		this.owner = owner;
		this.mask = sanitize(mask);
		this.position = sanitizePosition(position);
		contentColor = ContextCompat.getColor(view.getContext(), R.color.fps_overlay_content);
		resources = new PerformanceResources(view.getContext(), this.mask);
		if (this.mask == 0) {
			timer = null;
		} else {
			timer = new Timer("PerformanceOverlay", true);
			timer.schedule(this, 0, 500);
		}
	}

	@Override
	public synchronized void run() {
		if (stopped) return;
		try {
			sample();
		} catch (RuntimeException ignored) {
			// Diagnostics must never terminate the guest or the host sampler.
		}
	}

	private void sample() {
		long now = System.nanoTime();
		// Read visibility first: observing visible=true also observes the preceding
		// performanceGeneration update from the same lifecycle transition.
		boolean active = owner.getPerformanceSourceActive();
		long generation = owner.getPerformanceGeneration();
		FrameMetricsSnapshot snapshot = metrics == null ? null : metrics.snapshot();
		if (!active || generation != previousGeneration) {
			previousSnapshot = snapshot;
			previousSampleNanos = now;
			previousGeneration = generation;
			fps = guestFps = coalesced = Double.NaN;
			if ((mask & CPU) != 0) resources.resetCpuSample();
		} else if (snapshot != null) {
			if (previousSnapshot == null) {
				previousSnapshot = snapshot;
				previousSampleNanos = now;
			} else {
				long elapsed = now - previousSampleNanos;
				if (elapsed >= 1_000_000_000L) {
					fps = rate(snapshot.renderFrames(), previousSnapshot.renderFrames(), elapsed);
					guestFps = rate(snapshot.gameFrames(), previousSnapshot.gameFrames(), elapsed);
					coalesced = rate(snapshot.coalescedFrames(), previousSnapshot.coalescedFrames(), elapsed);
					previousSnapshot = snapshot;
					previousSampleNanos = now;
				}
			}
		}
		if (!active) {
			if (groups.length != 0) {
				groups = new String[0][];
				view.postInvalidate();
			}
			return;
		}
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.fps = fps;
		v.guestFps = guestFps;
		v.coalesced = coalesced;
		if ((mask & CAP) != 0) v.cap = owner.getPerformanceFpsCap();
		if ((mask & SPEED) != 0 && timingSession != null) {
			TimingSnapshot timing = timingSession.snapshotIfOpen();
			if (timing != null) v.speedPercent = timing.speedPercent();
		}
		if ((mask & RENDERER) != 0) v.renderer = owner.getPerformanceRenderer();
		if ((mask & DISPLAY) != 0) v.displayHz = owner.getPerformanceDisplayHz();
		PerformanceDiagnostics diagnostics = owner.getPerformanceDiagnostics();
		if (diagnostics != null) {
			PerformanceDiagnostics.Snapshot diagnosticSnapshot = diagnostics.snapshot(now);
			v.interval = diagnosticSnapshot.intervalMeanMs;
			v.p95 = diagnosticSnapshot.intervalP95Ms;
			v.maximum = diagnosticSnapshot.intervalMaxMs;
			v.renderInterval = diagnosticSnapshot.renderIntervalMeanMs;
			v.renderP95 = diagnosticSnapshot.renderIntervalP95Ms;
			v.renderMaximum = diagnosticSnapshot.renderIntervalMaxMs;
			v.paint = diagnosticSnapshot.paintMeanMs;
			v.copy = diagnosticSnapshot.copyMeanMs;
			v.submit = diagnosticSnapshot.submitMeanMs;
			v.inputQueue = diagnosticSnapshot.inputQueueMeanMs;
			v.frameQueue = diagnosticSnapshot.frameQueueMeanMs;
		}
		if ((mask & (CPU | RAM | JAVA_HEAP | NATIVE_HEAP | THERMAL)) != 0) {
			PerformanceResources.Snapshot system = resources.sample(now);
			v.cpu = system.getCpuPercent();
			v.ram = system.getRamMiB();
			v.javaHeap = system.getJavaHeapMiB();
			v.nativeHeap = system.getNativeHeapMiB();
			v.thermal = system.getThermalStatus();
		}
		String[][] nextGroups = PerformanceOverlayText.format(mask, v);
		if (!Arrays.deepEquals(groups, nextGroups)) {
			groups = nextGroups;
			view.postInvalidate();
		}
	}

	private static double rate(long current, long previous, long elapsedNanos) {
		return elapsedNanos > 0 && current >= previous
				? (current - previous) * 1_000_000_000d / elapsedNanos : Double.NaN;
	}

	@Override
	public void paint(CanvasWrapper g) {
		String[][] current = groups;
		if (current.length == 0) return;
		RectF bounds = drawingBounds;
		DiagnosticOverlayLayout.bounds(view, bounds, viewLocation, rootLocation);
		if (bounds.width() <= 0 || bounds.height() <= 0) return;
		float density = view.getResources().getDisplayMetrics().density;
		float gap = 2f * density;
		float lineHeight = g.getDiagnosticTextHeight() + gap;
		if (!Arrays.deepEquals(laidOutGroups, current)
				|| layoutWidth != bounds.width() || layoutHeight != bounds.height()) {
			laidOutGroups = current;
			layoutWidth = bounds.width();
			layoutHeight = bounds.height();
			columnRows = Math.max(1, (int) ((bounds.height() + gap) / lineHeight));
			int columns = 1;
			columnWidth = bounds.width();
			rows = PerformanceOverlayText.wrap(current, columnWidth, g::measureDiagnosticText);
			// Short wide windows can use columns without shrinking text or intercepting input.
			while (rows.length > columnRows * columns && columns < 4
					&& bounds.width() / (columns + 1) >= g.measureDiagnosticText("NATIVE 000 MiB")) {
				columns++;
				columnWidth = (bounds.width() - 8f * density * (columns - 1)) / columns;
				rows = PerformanceOverlayText.wrap(current, columnWidth, g::measureDiagnosticText);
			}
		}
		int columns = Math.max(1, (rows.length + columnRows - 1) / columnRows);
		// When content cannot fit, retain the selected corner and clip to the safe host viewport.
		columns = Math.min(columns, Math.max(1, (int) ((bounds.width() + 8f * density)
				/ (columnWidth + 8f * density))));
		float blockHeight = Math.min(rows.length, columnRows) * lineHeight - gap;
		boolean right = position == TOP_RIGHT || position == BOTTOM_RIGHT;
		boolean bottom = position == BOTTOM_LEFT || position == BOTTOM_RIGHT;
		float top = bottom ? bounds.bottom - blockHeight : bounds.top;
		int save = g.clipDiagnostics(bounds);
		try {
			for (int i = 0; i < Math.min(rows.length, columnRows * columns); i++) {
				g.drawDiagnosticText(rows[i], contentColor,
						DiagnosticOverlayLayout.rowLeft(bounds.left, bounds.right,
								g.measureDiagnosticText(rows[i]), right, i / columnRows, columns,
								columnWidth + 8f * density),
						top + (i % columnRows) * lineHeight);
			}
		} finally {
			g.restoreDiagnostics(save);
		}
	}

	public synchronized void stop() {
		stopped = true;
		if (timer != null) timer.cancel();
		resources.close();
	}
}
