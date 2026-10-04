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

import java.util.Arrays;

import io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions;

/**
 * Optional host-time diagnostics for one Canvas surface lifecycle and active visibility window.
 * Hot paths only write primitive samples into bounded, preallocated rings. A snapshot covers the
 * most recent five seconds (up to 4096 samples
 * per metric); unavailable or disabled metrics are NaN. No guest clock or pacing state is changed.
 */
public final class PerformanceDiagnostics {
	public static final int FRAME_INTERVAL = PerformanceOverlayOptions.FRAME_INTERVAL
			| PerformanceOverlayOptions.P95_INTERVAL | PerformanceOverlayOptions.MAX_INTERVAL;
	public static final int PAINT = PerformanceOverlayOptions.PAINT;
	public static final int COPY = PerformanceOverlayOptions.COPY;
	public static final int SUBMIT = PerformanceOverlayOptions.SUBMIT;
	public static final int INPUT_QUEUE = PerformanceOverlayOptions.INPUT_QUEUE;
	public static final int FRAME_QUEUE = PerformanceOverlayOptions.FRAME_QUEUE;
	public static final int TIMING_MASK = FRAME_INTERVAL | PAINT | COPY | SUBMIT
			| INPUT_QUEUE | FRAME_QUEUE;
	private static final long WINDOW_NANOS = 5_000_000_000L;
	private static final int CAPACITY = 4096;

	private final int mask;
	private final Samples interval, paint, copy, submit, inputQueue, frameQueue;
	private volatile boolean active;
	private long activeSinceNanos;
	private long generation;
	private boolean hasPublication;
	private long previousPublicationNanos;
	private long lastRenderedSequence;

	public PerformanceDiagnostics(int mask) {
		this.mask = mask;
		interval = samples(FRAME_INTERVAL);
		paint = samples(PAINT);
		copy = samples(COPY);
		submit = samples(SUBMIT);
		inputQueue = samples(INPUT_QUEUE);
		frameQueue = samples(FRAME_QUEUE);
	}

	private Samples samples(int bits) {
		return (mask & bits) != 0 ? new Samples() : null;
	}

	public boolean enabled(int bits) {
		return active && (mask & bits) != 0;
	}

	public void setActive(boolean active) {
		setActive(active, System.nanoTime());
	}

	/** Clears every window on an effective visibility edge; stale in-flight samples are rejected. */
	public synchronized void setActive(boolean active, long nowNanos) {
		this.active = active;
		activeSinceNanos = nowNanos;
		generation++;
		hasPublication = false;
		lastRenderedSequence = 0L;
		clear(interval);
		clear(paint);
		clear(copy);
		clear(submit);
		clear(inputQueue);
		clear(frameQueue);
	}

	private static void clear(Samples samples) {
		if (samples != null) samples.clear();
	}

	public synchronized long lifecycleGeneration() {
		return generation;
	}

	/** Called after a complete copy while Canvas still owns the presentation buffer lock. */
	public synchronized void recordPublication(long sequence, long nowNanos) {
		if (!active || sequence <= 0L || nowNanos < activeSinceNanos) return;
		if (interval != null && hasPublication && nowNanos >= previousPublicationNanos) {
			interval.add(nowNanos, nowNanos - previousPublicationNanos);
		}
		hasPublication = true;
		previousPublicationNanos = nowNanos;
	}

	/**
	 * Called after successful host submission. Publication and acquisition timestamps must be
	 * captured under the same buffer lock as the selected sequence, before its draw/upload starts.
	 * Queue time ends at acquisition; submit duration ends at completion of the host render work.
	 * Repeated/stale sequence consumption contributes neither queue nor submit.
	 */
	public synchronized void recordRender(long sequence, long publicationNanos,
			long consumptionNanos, long submitStartedNanos, long nowNanos) {
		if (!active || sequence <= lastRenderedSequence || sequence <= 0L
				|| publicationNanos < activeSinceNanos || consumptionNanos < publicationNanos
				|| nowNanos < consumptionNanos) return;
		lastRenderedSequence = sequence;
		if (frameQueue != null) frameQueue.add(nowNanos, consumptionNanos - publicationNanos);
		if (submit != null && submitStartedNanos >= activeSinceNanos
				&& nowNanos >= submitStartedNanos) {
			submit.add(nowNanos, nowNanos - submitStartedNanos);
		}
	}

	public synchronized void recordDuration(int metric, long startNanos, long endNanos) {
		if (!active || startNanos < activeSinceNanos || endNanos < startNanos) return;
		Samples samples = switch (metric) {
			case PAINT -> paint;
			case COPY -> copy;
			case INPUT_QUEUE -> inputQueue;
			default -> null;
		};
		if (samples != null) samples.add(endNanos, endNanos - startNanos);
	}

	public Snapshot snapshot(long nowNanos) {
		long cutoff = nowNanos - WINDOW_NANOS;
		long[] percentileValues = null;
		int percentileCount = 0;
		double intervalMean, intervalMax, paintMean, copyMean, submitMean, inputMean, frameMean;
		synchronized (this) {
			if (interval != null && (mask & PerformanceOverlayOptions.P95_INTERVAL) != 0) {
				percentileValues = new long[interval.count];
				percentileCount = interval.copyCurrent(cutoff, percentileValues);
			}
			intervalMean = (mask & PerformanceOverlayOptions.FRAME_INTERVAL) != 0
					? mean(interval, cutoff) : Double.NaN;
			intervalMax = (mask & PerformanceOverlayOptions.MAX_INTERVAL) != 0
					? maximum(interval, cutoff) : Double.NaN;
			paintMean = mean(paint, cutoff);
			copyMean = mean(copy, cutoff);
			submitMean = mean(submit, cutoff);
			inputMean = mean(inputQueue, cutoff);
			frameMean = mean(frameQueue, cutoff);
		}
		// Sorting is snapshot work: it must never hold the writer lock or block frame production.
		double p95 = Double.NaN;
		if (percentileCount != 0) {
			Arrays.sort(percentileValues, 0, percentileCount);
			p95 = percentileValues[(int) Math.ceil(percentileCount * 0.95) - 1] / 1_000_000.0;
		}
		return new Snapshot(intervalMean, p95, intervalMax, paintMean, copyMean, submitMean,
				inputMean, frameMean);
	}

	private static double mean(Samples samples, long cutoff) {
		return samples == null ? Double.NaN : samples.mean(cutoff);
	}

	private static double maximum(Samples samples, long cutoff) {
		return samples == null ? Double.NaN : samples.maximum(cutoff);
	}

	public static final class Snapshot {
		public final double intervalMeanMs, intervalP95Ms, intervalMaxMs;
		public final double paintMeanMs, copyMeanMs, submitMeanMs;
		public final double inputQueueMeanMs, frameQueueMeanMs;

		private Snapshot(double intervalMeanMs, double intervalP95Ms, double intervalMaxMs,
				double paintMeanMs, double copyMeanMs, double submitMeanMs,
				double inputQueueMeanMs, double frameQueueMeanMs) {
			this.intervalMeanMs = intervalMeanMs;
			this.intervalP95Ms = intervalP95Ms;
			this.intervalMaxMs = intervalMaxMs;
			this.paintMeanMs = paintMeanMs;
			this.copyMeanMs = copyMeanMs;
			this.submitMeanMs = submitMeanMs;
			this.inputQueueMeanMs = inputQueueMeanMs;
			this.frameQueueMeanMs = frameQueueMeanMs;
		}
	}

	private static final class Samples {
		private final long[] times = new long[CAPACITY];
		private final long[] values = new long[CAPACITY];
		private int next, count;

		void add(long time, long value) {
			times[next] = time;
			values[next] = value;
			next = (next + 1) % CAPACITY;
			count = Math.min(CAPACITY, count + 1);
		}

		void clear() { next = count = 0; }

		double mean(long cutoff) {
			double sum = 0;
			int current = 0;
			for (int i = 0; i < count; i++) {
				if (times[i] >= cutoff) { sum += values[i]; current++; }
			}
			return current == 0 ? Double.NaN : sum / current / 1_000_000.0;
		}

		double maximum(long cutoff) {
			long max = -1;
			for (int i = 0; i < count; i++) {
				if (times[i] >= cutoff) max = Math.max(max, values[i]);
			}
			return max < 0 ? Double.NaN : max / 1_000_000.0;
		}

		int copyCurrent(long cutoff, long[] target) {
			int current = 0;
			for (int i = 0; i < count; i++) {
				if (times[i] >= cutoff) target[current++] = values[i];
			}
			return current;
		}
	}
}
