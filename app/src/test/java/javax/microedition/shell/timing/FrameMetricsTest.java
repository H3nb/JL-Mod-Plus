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

import org.junit.Test;

public class FrameMetricsTest {
	@Test
	public void gameFramesCountCompletePublicationsAndRenderOnlyNewSequences() {
		FrameMetrics metrics = new FrameMetrics();

		assertEquals(1L, metrics.recordGameFrame());
		assertEquals(2L, metrics.recordGameFrame());
		metrics.recordRender(2L);
		metrics.recordRender(2L);

		FrameMetricsSnapshot snapshot = metrics.snapshotAndReset();
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

		FrameMetricsSnapshot snapshot = metrics.snapshotAndReset();
		assertEquals(1L, snapshot.gameFrames());
		assertEquals(1L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}

	@Test
	public void resetClearsWindowButKeepsSequenceOwnership() {
		FrameMetrics metrics = new FrameMetrics();
		assertEquals(1L, metrics.recordGameFrame());
		metrics.recordRender(1L);
		metrics.snapshotAndReset();

		assertEquals(2L, metrics.recordGameFrame());
		metrics.recordRender(2L);
		FrameMetricsSnapshot snapshot = metrics.snapshotAndReset();
		assertEquals(1L, snapshot.gameFrames());
		assertEquals(1L, snapshot.renderFrames());
		assertEquals(0L, snapshot.coalescedFrames());
	}
}
