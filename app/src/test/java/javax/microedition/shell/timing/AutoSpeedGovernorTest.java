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

public class AutoSpeedGovernorTest {
	private static final long ONE_SECOND = 1_000_000_000L;

	@Test
	public void waitsForRealWorkBeforeIncreasingSpeed() {
		AutoSpeedGovernor governor = new AutoSpeedGovernor();
		governor.reset();

		for (int i = 0; i < 6; i++) {
			AutoSpeedGovernor.Decision decision = governor.sample(100, 0, ONE_SECOND);
			assertEquals(100, decision.speedPercent);
			assertEquals(AutoSpeedGovernor.Phase.WAITING_FOR_ACTIVITY, decision.phase);
		}
	}

	@Test
	public void probesContinuousValuesBeyondManualMaximumWhileHealthy() {
		AutoSpeedGovernor governor = calibratedAtThirtyFps();
		int speed = 125;

		while (speed <= EmulationSpeed.MAX_PERCENT) {
			long frames = Math.max(1L, Math.round(30d * speed / 100d));
			speed = governor.sample(speed, frames, ONE_SECOND).speedPercent;
		}

		assertTrue(speed > EmulationSpeed.MAX_PERCENT);
		assertTrue(EmulationSpeed.requireRuntimePercent(speed) > EmulationSpeed.MAX_PERCENT);
	}

	@Test
	public void backsOffAfterSustainedThroughputLoss() {
		AutoSpeedGovernor governor = calibratedAtThirtyFps();
		int speed = 500;

		AutoSpeedGovernor.Decision first = governor.sample(speed, 130, ONE_SECOND);
		AutoSpeedGovernor.Decision second = governor.sample(speed, 130, ONE_SECOND);

		assertEquals(speed, first.speedPercent);
		assertTrue(second.speedPercent < speed);
		assertEquals(AutoSpeedGovernor.Phase.BACKING_OFF, second.phase);
	}

	@Test
	public void zeroFramesAtHighSpeedBacksOffButNeverBelowOneX() {
		AutoSpeedGovernor governor = calibratedAtThirtyFps();

		assertEquals(400, governor.sample(400, 0, ONE_SECOND).speedPercent);
		assertEquals(300, governor.sample(400, 0, ONE_SECOND).speedPercent);
		assertEquals(100, governor.sample(100, 0, ONE_SECOND).speedPercent);
	}

	private static AutoSpeedGovernor calibratedAtThirtyFps() {
		AutoSpeedGovernor governor = new AutoSpeedGovernor();
		governor.reset();
		governor.sample(100, 30, ONE_SECOND);
		governor.sample(100, 30, ONE_SECOND);
		AutoSpeedGovernor.Decision decision = governor.sample(100, 30, ONE_SECOND);
		assertEquals(125, decision.speedPercent);
		return governor;
	}
}
