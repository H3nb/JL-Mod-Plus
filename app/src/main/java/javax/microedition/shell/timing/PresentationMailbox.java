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
 * Bounded latest-frame handoff state for a producer and a renderer.
 *
 * <p>The mailbox does not own pixels. The caller publishes only after its complete buffer copy is
 * finished, and the renderer acknowledges the sequence it consumed. At most one renderer request
 * is in flight. If a producer publishes while that request is being consumed, completion keeps the
 * request armed so the caller can schedule one more render without losing the wakeup.</p>
 */
public final class PresentationMailbox {
	private long generation;
	private long publishedSequence;
	private long renderedSequence;
	private boolean open;
	private boolean renderScheduled;

	/** Starts a new lifecycle generation and discards state from the previous surface. */
	public synchronized long begin() {
		generation = nextGeneration(generation);
		publishedSequence = 0L;
		renderedSequence = 0L;
		renderScheduled = false;
		open = true;
		return generation;
	}

	/** Closes the current lifecycle so stale renderer callbacks cannot mutate a new surface. */
	public synchronized void close() {
		open = false;
		renderScheduled = false;
		generation = nextGeneration(generation);
	}

	public synchronized long generation() {
		return generation;
	}

	/** Publishes one complete frame and returns its sequence, or zero when the lifecycle is closed. */
	public synchronized long publish() {
		if (!open) {
			return 0L;
		}
		publishedSequence = nextSequence(publishedSequence);
		return publishedSequence;
	}

	/** Arms one renderer request. Repeated producer requests coalesce while one is in flight. */
	public synchronized boolean trySchedule(long expectedGeneration) {
		if (!open || generation != expectedGeneration || renderScheduled
				|| publishedSequence <= renderedSequence) {
			return false;
		}
		renderScheduled = true;
		return true;
	}

	/**
	 * Completes the active request. Returns true when another render must be scheduled because a
	 * newer complete frame exists. A zero consumed sequence means that the renderer did not consume
	 * a frame (for example, a transient surface lock failure).
	 */
	public synchronized boolean complete(long expectedGeneration, long consumedSequence) {
		if (!open || generation != expectedGeneration || !renderScheduled) {
			return false;
		}
		if (consumedSequence > renderedSequence) {
			renderedSequence = consumedSequence;
		}
		if (publishedSequence > renderedSequence) {
			return true;
		}
		renderScheduled = false;
		return false;
	}

	private static long nextSequence(long sequence) {
		return sequence == Long.MAX_VALUE ? 1L : sequence + 1L;
	}

	private static long nextGeneration(long generation) {
		return generation == Long.MAX_VALUE ? 1L : generation + 1L;
	}
}
