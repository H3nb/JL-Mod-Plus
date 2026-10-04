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
	public void gameFramesCountCompletePublicationsAndRenderOnlyNewMailboxSequences() {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordGameFrame();
		metrics.recordGameFrame();
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
		metrics.recordGameFrame();
		metrics.recordRender(1L);
		metrics.recordRender(1L);
		metrics.recordRender(0L);

		FrameMetricsSnapshot snapshot = metrics.snapshot();
		assertEquals(1L, snapshot.gameFrames());
		assertEquals(1L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}

	@Test
	public void newSurfaceGetsFreshMetricsAndFreshMailboxSequence() {
		PresentationMailbox mailbox = new PresentationMailbox();
		FrameMetrics firstSurface = new FrameMetrics();
		mailbox.begin();
		long first = mailbox.publish();
		firstSurface.recordGameFrame();
		firstSurface.recordRender(first);
		long second = mailbox.publish();
		firstSurface.recordGameFrame();
		firstSurface.recordRender(second);
		assertEquals(2L, firstSurface.snapshot().gameFrames());

		mailbox.close();
		FrameMetrics replacementSurface = new FrameMetrics();
		mailbox.begin();
		long replacement = mailbox.publish();
		replacementSurface.recordGameFrame();
		replacementSurface.recordRender(replacement);

		assertEquals(1L, replacement);
		assertEquals(1L, replacementSurface.snapshot().gameFrames());
		assertEquals(1L, replacementSurface.snapshot().renderFrames());
		assertEquals(0L, replacementSurface.snapshot().coalescedFrames());
		assertEquals(2L, firstSurface.snapshot().gameFrames());
		assertEquals(2L, firstSurface.snapshot().renderFrames());
	}

	@Test
	public void activationAbandonsOldPendingMailboxSequencesWithoutResettingCounters()
			throws Exception {
		PresentationMailbox mailbox = new PresentationMailbox();
		long generation = mailbox.begin();
		FrameMetrics metrics = new FrameMetrics();

		long first = mailbox.publish();
		metrics.recordGameFrame();
		metrics.recordRender(first);
		long stale = mailbox.publish();
		metrics.recordGameFrame();
		long boundary = mailbox.publish();
		metrics.recordGameFrame();

		CountDownLatch staleCallbackReady = new CountDownLatch(1);
		Thread staleRenderer = new Thread(() -> {
			staleCallbackReady.countDown();
			metrics.recordRender(stale);
		});
		FrameMetricsSnapshot atActivation;
		synchronized (metrics) {
			staleRenderer.start();
			assertTrue(staleCallbackReady.await(1, TimeUnit.SECONDS));
			metrics.abandonPendingFrames(boundary);
			atActivation = metrics.snapshot();
			assertEquals(3L, atActivation.gameFrames());
			assertEquals(1L, atActivation.renderFrames());
			assertEquals(0L, atActivation.coalescedFrames());
		}
		staleRenderer.join(1000L);
		assertFalse(staleRenderer.isAlive());
		assertEquals(atActivation.renderFrames(), metrics.snapshot().renderFrames());
		assertEquals(atActivation.coalescedFrames(), metrics.snapshot().coalescedFrames());

		long firstVisible = mailbox.publish();
		metrics.recordGameFrame();
		metrics.recordRender(firstVisible);
		assertEquals(2L, metrics.snapshot().renderFrames());
		assertEquals(0L, metrics.snapshot().coalescedFrames());

		mailbox.publish();
		metrics.recordGameFrame();
		long latestVisible = mailbox.publish();
		metrics.recordGameFrame();
		metrics.recordRender(latestVisible);
		assertEquals(3L, metrics.snapshot().renderFrames());
		assertEquals(1L, metrics.snapshot().coalescedFrames());

		assertFalse(mailbox.complete(generation, 0L));
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
