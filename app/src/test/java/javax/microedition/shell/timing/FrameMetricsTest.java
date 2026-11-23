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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class FrameMetricsTest {
	@Test
	public void onlyDistinctRenderedSequencesCount() {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordRender(0L);
		metrics.recordRender(-1L);
		metrics.recordRender(2L);
		metrics.recordRender(2L);
		metrics.recordRender(1L);
		assertEquals(1L, metrics.renderedFrames());
		metrics.recordRender(5L);
		assertEquals(2L, metrics.renderedFrames());
	}

	@Test
	public void resumedSurfaceDoesNotCountPendingOldFrames() throws Exception {
		FrameMetrics metrics = new FrameMetrics();
		metrics.recordRender(1L);
		CountDownLatch ready = new CountDownLatch(1);
		Thread stale = new Thread(() -> {
			ready.countDown();
			metrics.recordRender(2L);
		});
		synchronized (metrics) {
			stale.start();
			assertTrue(ready.await(1, TimeUnit.SECONDS));
			metrics.abandonPendingFrames(3L);
		}
		stale.join(1000L);
		assertFalse(stale.isAlive());
		assertEquals(1L, metrics.renderedFrames());
		metrics.recordRender(4L);
		assertEquals(2L, metrics.renderedFrames());
	}

	@Test
	public void replacementSurfaceHasIndependentCounters() {
		FrameMetrics oldSurface = new FrameMetrics();
		oldSurface.recordRender(3L);
		FrameMetrics nextSurface = new FrameMetrics();
		nextSurface.recordRender(1L);
		assertEquals(1L, oldSurface.renderedFrames());
		assertEquals(1L, nextSurface.renderedFrames());
	}
}
