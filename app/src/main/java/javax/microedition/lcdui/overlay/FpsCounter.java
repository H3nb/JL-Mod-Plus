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
import io.github.h3nb.jlmodplus.R;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.shell.timing.FrameMetrics;

/** Passive, per-surface FPS readout. Sampling never runs on the UI or guest thread. */
public final class FpsCounter extends TimerTask implements Layer {
	private final View view;
	private final Canvas owner;
	private final FrameMetrics metrics;
	private final int position;
	private final int contentColor;
	private final Timer timer = new Timer("FpsCounter", true);
	private final RectF drawingBounds = new RectF();
	private final RectF badgeBounds = new RectF();
	private final int[] viewLocation = new int[2];
	private final int[] rootLocation = new int[2];

	private volatile String label;
	private boolean stopped;
	private long previousGeneration = Long.MIN_VALUE;
	private long previousSampleNanos;
	private long previousFrames;

	public FpsCounter(View view, FrameMetrics metrics, Canvas owner, int position) {
		this.view = view;
		this.metrics = metrics;
		this.owner = owner;
		this.position = sanitizePosition(position);
		contentColor = ContextCompat.getColor(view.getContext(), R.color.fps_overlay_content);
		timer.schedule(this, 0, 500);
	}

	@Override
	public synchronized void run() {
		if (stopped) return;
		long now = System.nanoTime();
		boolean active = owner.getPerformanceSourceActive();
		long generation = owner.getPerformanceGeneration();
		long currentFrames = metrics.renderedFrames();
		if (!active || generation != previousGeneration) {
			previousGeneration = generation;
			previousFrames = currentFrames;
			previousSampleNanos = now;
			updateLabel(active ? "FPS —" : null);
			return;
		}
		long elapsed = now - previousSampleNanos;
		if (elapsed < 1_000_000_000L) return;
		double fps = (currentFrames - previousFrames) * 1_000_000_000d / elapsed;
		previousFrames = currentFrames;
		previousSampleNanos = now;
		updateLabel("FPS " + String.format(Locale.ROOT, "%.0f", fps));
	}

	private void updateLabel(String next) {
		if (Objects.equals(label, next)) return;
		label = next;
		view.postInvalidate();
	}

	@Override
	public void paint(CanvasWrapper graphics) {
		String text = label;
		if (text == null) return;
		DiagnosticOverlayLayout.bounds(view, drawingBounds, viewLocation, rootLocation);
		if (drawingBounds.width() <= 0 || drawingBounds.height() <= 0) return;
		boolean right = position == TOP_RIGHT || position == BOTTOM_RIGHT;
		boolean bottom = position == BOTTOM_LEFT || position == BOTTOM_RIGHT;
		float density = view.getResources().getDisplayMetrics().density;
		float horizontalPadding = 6f * density;
		float verticalPadding = 3f * density;
		float width = graphics.measureDiagnosticText(text) + 2f * horizontalPadding;
		float height = graphics.getDiagnosticTextHeight() + 2f * verticalPadding;
		float x = right ? drawingBounds.right - width : drawingBounds.left;
		float y = bottom ? drawingBounds.bottom - height : drawingBounds.top;
		badgeBounds.set(x, y, x + width, y + height);
		int save = graphics.clipDiagnostics(drawingBounds);
		try {
			graphics.fillControlRoundRect(badgeBounds, 5f * density, 0xB8000000);
			graphics.drawDiagnosticText(text, contentColor, x + horizontalPadding, y + verticalPadding);
		} finally {
			graphics.restoreDiagnostics(save);
		}
	}

	public synchronized void stop() {
		stopped = true;
		timer.cancel();
	}
}
