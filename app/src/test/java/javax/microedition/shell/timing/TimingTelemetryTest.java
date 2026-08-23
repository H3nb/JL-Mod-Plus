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

import org.junit.Test;

public class TimingTelemetryTest {

	@Test
	public void reportsRateOnlyAfterAStableSameSpeedInterval() {
		TimingTelemetry telemetry = new TimingTelemetry();
		TimingTelemetrySnapshot first = telemetry.sample(snapshot(1L, 100L, 100L, 200));
		assertFalse(first.hasMeasuredPercent());

		TimingTelemetrySnapshot second = telemetry.sample(snapshot(1L, 200L, 300L, 200));
		assertTrue(second.hasMeasuredPercent());
		assertEquals(200, second.measuredPercent());
	}

	@Test
	public void discardsIntervalsCrossingSpeedOrGenerationChanges() {
		TimingTelemetry telemetry = new TimingTelemetry();
		telemetry.sample(snapshot(1L, 100L, 100L, 100));

		TimingTelemetrySnapshot speedChange = telemetry.sample(snapshot(1L, 200L, 200L, 200));
		assertFalse(speedChange.hasMeasuredPercent());

		TimingTelemetrySnapshot generationChange = telemetry.sample(snapshot(2L, 300L, 300L, 200));
		assertFalse(generationChange.hasMeasuredPercent());
	}

	private static TimingSnapshot snapshot(
			long generation, long hostNanos, long guestNanos, int speedPercent) {
		return new TimingSnapshot(
				generation,
				hostNanos,
				guestNanos,
				guestNanos / 1_000_000L,
				speedPercent);
	}
}
