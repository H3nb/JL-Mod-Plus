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

/**
 * Thread-safe frame ownership metrics for one Canvas lifecycle.
 *
 * <p>A game frame is counted only after the complete guest buffer has been copied into the
 * presentation buffer. A render frame is counted only when a renderer consumes a sequence newer
 * than the last sequence it consumed. This keeps host redraw callbacks and repeated display of a
 * static buffer out of the Game FPS value.</p>
 */
public final class FrameMetrics {
	private long nextSequence;
	private long lastRenderedSequence;
	private long gameFrames;
	private long renderFrames;
	private long coalescedFrames;

	/** Records one complete guest publication and returns its monotonically increasing sequence. */
	public synchronized long recordGameFrame() {
		if (nextSequence == Long.MAX_VALUE) {
			// This is not reachable during a normal Canvas lifetime. Resetting the local sequence is
			// safer than allowing a negative value to make every subsequent render look stale.
			nextSequence = 0L;
			lastRenderedSequence = 0L;
		}
		nextSequence++;
		gameFrames++;
		return nextSequence;
	}

	/** Records consumption of the newest complete frame. Repeated consumption is ignored. */
	public synchronized void recordRender(long sequence) {
		if (sequence <= lastRenderedSequence || sequence <= 0L) {
			return;
		}
		long skipped = sequence - lastRenderedSequence - 1L;
		if (skipped > 0L) {
			coalescedFrames = saturatingAdd(coalescedFrames, skipped);
		}
		lastRenderedSequence = sequence;
		renderFrames++;
	}

	/** Returns the current window and starts a new measurement window without resetting sequence. */
	public synchronized FrameMetricsSnapshot snapshotAndReset() {
		FrameMetricsSnapshot snapshot = new FrameMetricsSnapshot(
				gameFrames,
				renderFrames,
				coalescedFrames);
		gameFrames = 0L;
		renderFrames = 0L;
		coalescedFrames = 0L;
		return snapshot;
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}
}
