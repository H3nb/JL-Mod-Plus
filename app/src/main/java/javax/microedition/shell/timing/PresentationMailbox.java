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
	private long requestedHostRevision;
	private long presentedHostRevision;
	private boolean open;
	private boolean renderScheduled;

	/** Starts a new lifecycle generation and discards state from the previous surface. */
	public synchronized long begin() {
		generation = nextGeneration(generation);
		publishedSequence = 0L;
		renderedSequence = 0L;
		requestedHostRevision = 0L;
		presentedHostRevision = 0L;
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

	/** Requests a host-only redraw without pretending that a guest frame was published. */
	public synchronized long invalidateHost(long expectedGeneration) {
		if (!open || generation != expectedGeneration) return 0L;
		requestedHostRevision = nextSequence(requestedHostRevision);
		return requestedHostRevision;
	}

	/** Returns the newest host revision captured for the active draw. */
	public synchronized long captureHostRevision(long expectedGeneration) {
		if (!open || generation != expectedGeneration || !renderScheduled) return 0L;
		return requestedHostRevision;
	}

	/** Arms one renderer request. Repeated producer requests coalesce while one is in flight. */
	public synchronized boolean trySchedule(long expectedGeneration) {
		if (!open || generation != expectedGeneration || renderScheduled
				|| !hasPendingLocked()) {
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
		return complete(expectedGeneration, consumedSequence, 0L);
	}

	/** Completes a draw and acknowledges only the guest/host revisions captured by that draw. */
	public synchronized boolean complete(long expectedGeneration, long consumedSequence,
			long consumedHostRevision) {
		if (!open || generation != expectedGeneration || !renderScheduled) {
			return false;
		}
		if (consumedSequence > renderedSequence) {
			renderedSequence = consumedSequence;
		}
		if (consumedHostRevision > presentedHostRevision) {
			presentedHostRevision = consumedHostRevision;
		}
		if (hasPendingLocked()) {
			return true;
		}
		renderScheduled = false;
		return false;
	}

	/**
	 * Completes and disarms the active request even when a newer publication is pending. This is
	 * used by bounded synchronous drains so a later producer or host retry can re-arm the mailbox.
	 */
	public synchronized boolean completeAndRelease(long expectedGeneration, long consumedSequence) {
		return completeAndRelease(expectedGeneration, consumedSequence, 0L);
	}

	public synchronized boolean completeAndRelease(long expectedGeneration, long consumedSequence,
			long consumedHostRevision) {
		if (!open || generation != expectedGeneration || !renderScheduled) {
			return false;
		}
		if (consumedSequence > renderedSequence) {
			renderedSequence = consumedSequence;
		}
		if (consumedHostRevision > presentedHostRevision) {
			presentedHostRevision = consumedHostRevision;
		}
		boolean pending = hasPendingLocked();
		renderScheduled = false;
		return pending;
	}

	/** Releases a renderer request after a transient presentation failure. */
	public synchronized boolean releaseAfterFailure(long expectedGeneration) {
		if (!open || generation != expectedGeneration || !renderScheduled) {
			return false;
		}
		renderScheduled = false;
		return hasPendingLocked();
	}

	private boolean hasPendingLocked() {
		return publishedSequence > renderedSequence
				|| requestedHostRevision > presentedHostRevision;
	}

	private static long nextSequence(long sequence) {
		return sequence == Long.MAX_VALUE ? 1L : sequence + 1L;
	}

	private static long nextGeneration(long generation) {
		return generation == Long.MAX_VALUE ? 1L : generation + 1L;
	}
}
