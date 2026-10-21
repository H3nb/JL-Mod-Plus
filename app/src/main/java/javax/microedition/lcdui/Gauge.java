/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2021-2026 Yury Kharchenko
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

package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.SeekBar;

import androidx.appcompat.widget.AppCompatSeekBar;

import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.util.ContextHolder;

public class Gauge extends Item {
	public static final int CONTINUOUS_IDLE = 0;
	public static final int INCREMENTAL_IDLE = 1;
	public static final int CONTINUOUS_RUNNING = 2;
	public static final int INCREMENTAL_UPDATING = 3;
	public static final int INDEFINITE = -1;


	private final boolean interactive;
	private final SeekBarListener seekBarListener = new SeekBarListener();

	// Guest-visible state is authoritative, including while no Android View exists.
	volatile ProgressBar progressBar;
	private int value;
	private int maxValue;
	// Invalidates queued incremental frames after a mode/value transition.
	private long animationEpoch;

	public Gauge(String label, boolean interactive, int maxValue, int initialValue) {
		setLabel(label);
		this.interactive = interactive;
		setMaxValue(maxValue);
		setValue(initialValue);
	}

	public synchronized int getValue() {
		return value;
	}

	public synchronized int getMaxValue() {
		return maxValue;
	}

	public boolean isInteractive() {
		return interactive;
	}

	public synchronized void setValue(int newValue) {
		if (maxValue == INDEFINITE) {
			if (newValue < CONTINUOUS_IDLE || newValue > INCREMENTAL_UPDATING) {
				throw new IllegalArgumentException("Invalid indefinite Gauge state: " + newValue);
			}
		} else {
			newValue = Math.max(0, Math.min(newValue, maxValue));
		}

		if (value == INCREMENTAL_UPDATING && newValue != INCREMENTAL_UPDATING) {
			animationEpoch++;
		}
		value = newValue;

		// Each call with INCREMENTAL_UPDATING is a frame advance, even when
		// getValue() continues to return the same integer.
		postViewUpdate(maxValue == INDEFINITE && newValue == INCREMENTAL_UPDATING);
	}

	public synchronized void setMaxValue(int newMax) {
		if (interactive ? newMax <= 0 : newMax != INDEFINITE && newMax <= 0) {
			throw new IllegalArgumentException("Invalid Gauge maximum: " + newMax);
		}

		int oldMax = maxValue;
		if (oldMax != newMax) {
			animationEpoch++;
			if (oldMax == INDEFINITE) {
				if (newMax != INDEFINITE) {
					value = 0;
				}
			} else if (newMax == INDEFINITE) {
				value = CONTINUOUS_IDLE;
			} else {
				value = Math.min(value, newMax);
			}
		}
		maxValue = newMax;
		postViewUpdate(false);
	}

	/**
	 * UI state is a projection of the current guest model. Queued snapshots
	 * must never restore older values after more recent MIDlet calls.
	 */
	private void postViewUpdate(boolean incrementalStep) {
		ProgressBar target = progressBar;
		if (target == null) {
			return;
		}
		long epoch = animationEpoch;
		ViewHandler.postEvent(() -> {
			if (progressBar != target) {
				return;
			}
			int currentMax;
			int currentValue;
			boolean step;
			synchronized (Gauge.this) {
				currentMax = maxValue;
				currentValue = value;
				step = incrementalStep && animationEpoch == epoch;
			}
			updateProgressBar(target, currentMax, currentValue, step);
		});
	}

	private void updateProgressBar(ProgressBar bar, int currentMax, int currentValue,
			boolean incrementalStep) {
		if (currentMax != INDEFINITE) {
			bar.setIndeterminate(false);
			bar.setMax(currentMax);
			bar.setProgress(currentValue);
			return;
		}

		if (currentValue == CONTINUOUS_RUNNING) {
			bar.setIndeterminate(true);
			return;
		}

		// Continuous idle and incremental idle are static. For incremental
		// updating, advance an activity frame (not a completion percentage).
		boolean wasIndeterminate = bar.isIndeterminate();
		bar.setIndeterminate(false);
		bar.setMax(4);
		if (wasIndeterminate || currentValue == CONTINUOUS_IDLE) {
			bar.setProgress(0);
		}
		if (currentValue == INCREMENTAL_UPDATING && incrementalStep) {
			bar.setProgress(bar.getProgress() % 4 + 1);
		}
	}

	@Override
	View getItemContentView() {
		if (progressBar == null) {
			Context activity = ContextHolder.getActivity();
			ProgressBar bar;
			if (interactive) {
				bar = new AppCompatSeekBar(activity);
				((SeekBar) bar).setOnSeekBarChangeListener(seekBarListener);
			} else {
				bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
			}
			int currentMax;
			int currentValue;
			synchronized (this) {
				currentMax = maxValue;
				currentValue = value;
				progressBar = bar;
			}
			updateProgressBar(bar, currentMax, currentValue, false);
		}
		return progressBar;
	}

	@Override
	void clearItemContentView() {
		progressBar = null;
	}

	private class SeekBarListener implements SeekBar.OnSeekBarChangeListener {
		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (!fromUser) {
				return;
			}
			boolean changed;
			synchronized (Gauge.this) {
				int normalized = Math.max(0, Math.min(progress, maxValue));
				changed = value != normalized;
				value = normalized;
			}
			if (changed) {
				postStateChanged();
			}
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
		}
	}
}
