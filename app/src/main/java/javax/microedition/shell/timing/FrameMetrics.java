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
 * Optional frame-traffic counters for one Canvas surface generation.
 *
 * <p>{@link PresentationMailbox} owns the publication sequence. A game frame is counted only
 * after a complete guest buffer publication. A render frame is counted only when a renderer
 * consumes a newer mailbox sequence, so repeated host redraws do not inflate renderer FPS.</p>
 */
public final class FrameMetrics {
	private long lastRenderedSequence;
	private final AtomicLong gameFrames = new AtomicLong();
	private final AtomicLong renderFrames = new AtomicLong();
	private final AtomicLong coalescedFrames = new AtomicLong();

	/** Records one complete guest publication. Sequence ownership remains with the mailbox. */
	public void recordGameFrame() {
		incrementSaturated(gameFrames);
	}

	/**
	 * Records consumption of a canonical mailbox sequence. Repeated and stale consumption is
	 * ignored. Serialized with abandonment so a pre-resume callback cannot enter a new window.
	 */
	public synchronized void recordRender(long mailboxSequence) {
		if (mailboxSequence <= 0L || mailboxSequence <= lastRenderedSequence) {
			return;
		}
		long skipped = mailboxSequence - lastRenderedSequence - 1L;
		lastRenderedSequence = mailboxSequence;
		if (skipped > 0L) {
			addSaturated(coalescedFrames, skipped);
		}
		incrementSaturated(renderFrames);
	}

	/**
	 * Marks every publication up to the supplied canonical mailbox boundary as already accounted
	 * for without changing lifetime counters.
	 */
	public synchronized void abandonPendingFrames(long mailboxBoundary) {
		if (mailboxBoundary > lastRenderedSequence) {
			lastRenderedSequence = mailboxBoundary;
		}
	}

	/** Returns lifetime totals for this surface generation. */
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
