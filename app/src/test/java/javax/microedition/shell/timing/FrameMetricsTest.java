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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class FrameMetricsTest {
	@Test
	public void gameFramesCountCompletePublicationsAndRenderOnlyNewSequences() {
		FrameMetrics metrics = new FrameMetrics();

		assertEquals(1L, metrics.recordGameFrame());
		assertEquals(2L, metrics.recordGameFrame());
		metrics.recordRender(2L);
		metrics.recordRender(2L);

		FrameMetricsSnapshot snapshot = metrics.snapshot();
		assertEquals(2L, snapshot.gameFrames());
		assertEquals(1L, snapshot.renderFrames());
		assertEquals(1L, snapshot.coalescedFrames());
	}

	@Test
	public void staleAndEmptyRenderCallbacksDoNotCount() {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordRender(0L);
		metrics.recordRender(-1L);
		assertEquals(1L, metrics.recordGameFrame());
		metrics.recordRender(1L);
		metrics.recordRender(1L);
		metrics.recordRender(0L);

		FrameMetricsSnapshot snapshot = metrics.snapshot();
		assertEquals(1L, snapshot.gameFrames());
		assertEquals(1L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}

	@Test
	public void snapshotsKeepSequenceOwnershipAndCumulativeCounts() {
		FrameMetrics metrics = new FrameMetrics();
		assertEquals(1L, metrics.recordGameFrame());
		metrics.recordRender(1L);
		metrics.snapshot();

		assertEquals(2L, metrics.recordGameFrame());
		metrics.recordRender(2L);
		FrameMetricsSnapshot snapshot = metrics.snapshot();
		assertEquals(2L, snapshot.gameFrames());
		assertEquals(2L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}

	@Test
	public void surfaceMailboxRestartKeepsMetricsSequenceOwnership() {
		FrameMetrics metrics = new FrameMetrics();
		PresentationMailbox mailbox = new PresentationMailbox();
		mailbox.begin();
		for (int i = 1; i <= 3; i++) {
			assertEquals(i, mailbox.publish());
			metrics.recordRender(metrics.recordGameFrame());
		}
		mailbox.close();
		mailbox.begin();
		assertEquals(1L, mailbox.publish());
		long replacementMetricsSequence = metrics.recordGameFrame();
		assertEquals(4L, replacementMetricsSequence);
		metrics.recordRender(replacementMetricsSequence);
		metrics.recordRender(replacementMetricsSequence);
		FrameMetricsSnapshot snapshot = metrics.snapshot();
		assertEquals(4L, snapshot.gameFrames());
		assertEquals(4L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}

	@Test
	public void activationAbandonsOldPendingFramesWithoutResettingCounters() throws Exception {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordRender(metrics.recordGameFrame());
		long abandoned = metrics.recordGameFrame();
		metrics.recordGameFrame();

		CountDownLatch staleCallbackReady = new CountDownLatch(1);
		Thread staleRenderer = new Thread(() -> {
			staleCallbackReady.countDown();
			metrics.recordRender(abandoned);
		});
		FrameMetricsSnapshot atActivation;
		synchronized (metrics) {
			staleRenderer.start();
			assertTrue(staleCallbackReady.await(1, TimeUnit.SECONDS));
			// The stale callback is now waiting on the same serialization boundary.
			metrics.abandonPendingFrames();
			atActivation = metrics.snapshot();
			assertEquals(3L, atActivation.gameFrames());
			assertEquals(1L, atActivation.renderFrames());
			assertEquals(0L, atActivation.coalescedFrames());
		}
		staleRenderer.join(1000L);
		assertFalse(staleRenderer.isAlive());
		FrameMetricsSnapshot afterStaleCallback = metrics.snapshot();
		assertEquals(atActivation.renderFrames(), afterStaleCallback.renderFrames());
		assertEquals(atActivation.coalescedFrames(), afterStaleCallback.coalescedFrames());

		metrics.recordRender(metrics.recordGameFrame());
		assertEquals(2L, metrics.snapshot().renderFrames());
		assertEquals(0L, metrics.snapshot().coalescedFrames());
		// Genuine latest-buffer replacement within the new visible window still contributes.
		metrics.recordGameFrame();
		metrics.recordRender(metrics.recordGameFrame());
		assertEquals(3L, metrics.snapshot().renderFrames());
		assertEquals(1L, metrics.snapshot().coalescedFrames());
	}

	@Test
	public void cumulativeSnapshotDoesNotConsumeAnotherObserversData() {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordGameFrame();

		assertEquals(1L, metrics.snapshot().gameFrames());
		assertEquals(1L, metrics.snapshot().gameFrames());
		assertEquals(1L, metrics.snapshot().gameFrames());
	}
}
