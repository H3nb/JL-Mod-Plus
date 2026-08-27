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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe frame ownership metrics for one Canvas lifecycle.
 *
 * <p>A game frame is counted only after the complete guest buffer has been copied into the
 * presentation buffer. A render frame is counted only when a renderer consumes a sequence newer
 * than the last sequence it consumed. This keeps host redraw callbacks and repeated display of a
 * static buffer out of the Game FPS value.</p>
 */
public final class FrameMetrics {
	private final AtomicLong nextSequence = new AtomicLong();
	private final AtomicLong lastRenderedSequence = new AtomicLong();
	private final AtomicLong gameFrames = new AtomicLong();
	private final AtomicLong renderFrames = new AtomicLong();
	private final AtomicLong coalescedFrames = new AtomicLong();

	/** Records one complete guest publication and returns its monotonically increasing sequence. */
	public long recordGameFrame() {
		long current;
		long sequence;
		do {
			current = nextSequence.get();
			sequence = current == Long.MAX_VALUE ? 1L : current + 1L;
		} while (!nextSequence.compareAndSet(current, sequence));
		if (sequence == 1L) {
			// This is not reachable during a normal Canvas lifetime. Resetting the local sequence is
			// safer than allowing a negative value to make every subsequent render look stale.
			lastRenderedSequence.set(0L);
		}
		incrementSaturated(gameFrames);
		return sequence;
	}

	/** Records consumption of the newest complete frame. Repeated consumption is ignored. */
	public void recordRender(long sequence) {
		if (sequence <= 0L) {
			return;
		}
		long previous;
		do {
			previous = lastRenderedSequence.get();
			if (sequence <= previous) {
				return;
			}
		} while (!lastRenderedSequence.compareAndSet(previous, sequence));
		long skipped = sequence - previous - 1L;
		if (skipped > 0L && sequence > previous) {
			addSaturated(coalescedFrames, skipped);
		}
		incrementSaturated(renderFrames);
	}

	/** Returns lifetime totals without interfering with another diagnostics consumer. */
	public FrameMetricsSnapshot snapshot() {
		return new FrameMetricsSnapshot(
				gameFrames.get(),
				renderFrames.get(),
				coalescedFrames.get());
	}

	private static void incrementSaturated(AtomicLong counter) {
		addSaturated(counter, 1L);
	}

	private static void addSaturated(AtomicLong counter, long increment) {
		long current;
		long updated;
		do {
			current = counter.get();
			updated = increment > Long.MAX_VALUE - current
					? Long.MAX_VALUE : current + increment;
		} while (!counter.compareAndSet(current, updated));
	}
}
