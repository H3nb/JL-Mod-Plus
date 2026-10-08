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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerformanceDiagnosticsTest {
	private static final long MS = 1_000_000L;

	@Test
	public void publicationIntervalsUseActualSamplesAndExpire() {
		PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(PerformanceDiagnostics.FRAME_INTERVAL);
		diagnostics.setActive(true, 0L);
		assertTrue(Double.isNaN(diagnostics.snapshot(0L).intervalMeanMs));
		diagnostics.recordPublication(1, 10 * MS);
		assertTrue(Double.isNaN(diagnostics.snapshot(10 * MS).intervalMeanMs));
		long now = 10 * MS;
		for (int i = 1; i <= 20; i++) {
			now += i * MS;
			diagnostics.recordPublication(i + 1, now);
		}
		PerformanceDiagnostics.Snapshot snapshot = diagnostics.snapshot(now);
		assertEquals(10.5, snapshot.intervalMeanMs, 0.0001);
		assertEquals(19.0, snapshot.intervalP95Ms, 0.0001);
		assertEquals(20.0, snapshot.intervalMaxMs, 0.0001);
		assertTrue(Double.isNaN(diagnostics.snapshot(now + 5001 * MS).intervalMeanMs));
	}

	@Test
	public void consumptionUsesCapturedPublicationAndCountsOnlyNewFrames() {
		PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(
				PerformanceDiagnostics.FRAME_QUEUE | PerformanceDiagnostics.SUBMIT);
		diagnostics.setActive(true, 0);
		diagnostics.recordPublication(1, 10 * MS);
		diagnostics.recordPublication(2, 20 * MS);
		// The renderer consumed frame 1 even though frame 2 was published before it completed.
		diagnostics.recordRender(1, 10 * MS, 15 * MS, 13 * MS, 25 * MS);
		diagnostics.recordRender(1, 10 * MS, 30 * MS, 26 * MS, 50 * MS);
		diagnostics.recordRender(0, 0, 30 * MS, 26 * MS, 50 * MS);
		PerformanceDiagnostics.Snapshot snapshot = diagnostics.snapshot(50 * MS);
		assertEquals(5.0, snapshot.frameQueueMeanMs, 0.0001);
		assertEquals(12.0, snapshot.submitMeanMs, 0.0001);
		assertTrue(Double.isNaN(snapshot.intervalMeanMs));
	}

	@Test
	public void visibilityResetRejectsInFlightWorkAndStartsFreshIntervals() {
		PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(PerformanceDiagnostics.TIMING_MASK);
		diagnostics.setActive(true, 0);
		diagnostics.recordPublication(1, 10 * MS);
		diagnostics.recordPublication(2, 20 * MS);
		diagnostics.recordDuration(PerformanceDiagnostics.INPUT_QUEUE, 5 * MS, 20 * MS);
		long generation = diagnostics.lifecycleGeneration();
		diagnostics.setActive(false, 30 * MS);
		diagnostics.recordPublication(3, 40 * MS);
		assertTrue(Double.isNaN(diagnostics.snapshot(40 * MS).intervalMeanMs));
		diagnostics.setActive(true, 1000 * MS);
		assertTrue(diagnostics.lifecycleGeneration() > generation);
		diagnostics.recordDuration(PerformanceDiagnostics.INPUT_QUEUE, 20 * MS, 1001 * MS);
		diagnostics.recordRender(2, 20 * MS, 1000 * MS, 1000 * MS, 1001 * MS);
		diagnostics.recordPublication(4, 1010 * MS);
		PerformanceDiagnostics.Snapshot snapshot = diagnostics.snapshot(1010 * MS);
		assertTrue(Double.isNaN(snapshot.intervalMeanMs));
		assertTrue(Double.isNaN(snapshot.inputQueueMeanMs));
		assertTrue(Double.isNaN(snapshot.frameQueueMeanMs));
		assertTrue(Double.isNaN(snapshot.submitMeanMs));
		diagnostics.recordPublication(5, 1030 * MS);
		assertEquals(20.0, diagnostics.snapshot(1030 * MS).intervalMeanMs, 0.0001);
	}

	@Test
	public void renderCadenceOnlyTracksNewSequencesAndExpiresAcrossVisibility() {
		PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(
				PerformanceDiagnostics.RENDER_CADENCE);
		diagnostics.setActive(true, 0L);
		diagnostics.recordRender(1L, 10 * MS, 11 * MS, 11 * MS, 12 * MS);
		diagnostics.recordRender(1L, 10 * MS, 11 * MS, 11 * MS, 13 * MS);
		diagnostics.recordRender(2L, 20 * MS, 21 * MS, 21 * MS, 32 * MS);
		diagnostics.recordRender(3L, 35 * MS, 36 * MS, 36 * MS, 47 * MS);
		PerformanceDiagnostics.Snapshot snapshot = diagnostics.snapshot(47 * MS);
		assertEquals(17.5, snapshot.renderIntervalMeanMs, 0.0001);
		assertEquals(20, snapshot.renderIntervalP95Ms, 0.0001);
		assertEquals(20, snapshot.renderIntervalMaxMs, 0.0001);
		assertTrue(Double.isNaN(snapshot.intervalMeanMs));
		assertTrue(Double.isNaN(diagnostics.snapshot(5050 * MS).renderIntervalMeanMs));
		diagnostics.setActive(false, 5100 * MS);
		diagnostics.setActive(true, 5200 * MS);
		diagnostics.recordRender(4L, 10 * MS, 5210 * MS, 5210 * MS, 5215 * MS);
		assertTrue(Double.isNaN(diagnostics.snapshot(5220 * MS).renderIntervalMeanMs));
		diagnostics.recordRender(5L, 5230 * MS, 5231 * MS, 5231 * MS, 5240 * MS);
		assertTrue(Double.isNaN(diagnostics.snapshot(5240 * MS).renderIntervalMeanMs));
		diagnostics.recordRender(6L, 5250 * MS, 5251 * MS, 5251 * MS, 5260 * MS);
		assertEquals(20, diagnostics.snapshot(5260 * MS).renderIntervalMeanMs, 0.0001);
	}

	@Test
	public void durationsAreIndependentAndDisabledMetricsRemainUnavailable() {
		PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(PerformanceDiagnostics.PAINT);
		diagnostics.setActive(true, 0);
		diagnostics.recordDuration(PerformanceDiagnostics.PAINT, 10 * MS, 12 * MS);
		diagnostics.recordDuration(PerformanceDiagnostics.PAINT, 20 * MS, 24 * MS);
		diagnostics.recordDuration(PerformanceDiagnostics.PAINT, 24 * MS, 20 * MS);
		diagnostics.recordDuration(PerformanceDiagnostics.COPY, 10 * MS, 12 * MS);
		PerformanceDiagnostics.Snapshot snapshot = diagnostics.snapshot(24 * MS);
		assertEquals(3.0, snapshot.paintMeanMs, 0.0001);
		assertTrue(Double.isNaN(snapshot.copyMeanMs));
		assertTrue(Double.isNaN(diagnostics.snapshot(5025 * MS).paintMeanMs));
	}
}
