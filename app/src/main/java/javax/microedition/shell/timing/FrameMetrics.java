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
 * Optional count of distinct guest mailbox sequences consumed by the host renderer.
 * PresentationMailbox owns the sequence; this counter never modifies pacing.
 */
public final class FrameMetrics {
	private long lastRenderedSequence;
	private volatile long renderedFrames;

	public synchronized void recordRender(long mailboxSequence) {
		if (mailboxSequence <= 0L || mailboxSequence <= lastRenderedSequence) return;
		lastRenderedSequence = mailboxSequence;
		if (renderedFrames != Long.MAX_VALUE) renderedFrames++;
	}

	/** Discard frames pending before the current visibility window. */
	public synchronized void abandonPendingFrames(long mailboxBoundary) {
		if (mailboxBoundary > lastRenderedSequence) {
			lastRenderedSequence = mailboxBoundary;
		}
	}

	public long renderedFrames() {
		return renderedFrames;
	}
}
