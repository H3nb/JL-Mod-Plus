/*
 * Copyright 2019 Yury Kharchenko
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;

public class FpsCounter extends TimerTask implements Layer {

	private final View view;
	private final String frameRateLabel;
	private final int pillBackgroundColor;
	private final int pillContentColor;
	private volatile String prevFrameCount;
	private final AtomicInteger totalFrameCount = new AtomicInteger();
	private final Timer timer;

	public FpsCounter(View view) {
		this.view = view;
		frameRateLabel = ContextHolder.getAppContext().getString(R.string.fps_overlay_label);
		pillBackgroundColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_surface);
		pillContentColor = ContextCompat.getColor(
				ContextHolder.getAppContext(), R.color.fps_overlay_content);
		prevFrameCount = frameRateLabel + " 0";
		timer = new Timer("FpsCounter", true);
		// Avoid catch-up bursts after a cached process resumes on Android 16.
		timer.schedule(this, 0, 1000);
	}

	public void run() {
		prevFrameCount = frameRateLabel + " " + totalFrameCount.getAndSet(0);
		view.postInvalidate();
	}

	public void increment() {
		totalFrameCount.incrementAndGet();
	}

	public void paint(CanvasWrapper g) {
		float density = view.getResources().getDisplayMetrics().density;
		float margin = 10f * density;
		float left = margin;
		float top = margin;
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		if (insets != null) {
			Insets cutout = insets.getInsetsIgnoringVisibility(
					WindowInsetsCompat.Type.displayCutout());
			left = Math.max(left, cutout.left + margin);
			top = Math.max(top, cutout.top + margin);
		}
		g.drawPillBackgroundedText(
				prevFrameCount,
				pillBackgroundColor,
				pillContentColor,
				0.68f,
				left,
				top);
	}

	public void stop() {
		timer.cancel();
	}
}
