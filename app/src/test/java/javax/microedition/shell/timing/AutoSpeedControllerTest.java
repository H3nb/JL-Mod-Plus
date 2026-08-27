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

public class AutoSpeedControllerTest {
	@Test
	public void manualSelectionLeavesAutoAndStillUsesManualValidation() {
		TimingSession session = new TimingSession(new TestTimeSource(), 100, 1L);
		AutoSpeedController controller = new AutoSpeedController(session);
		try {
			assertTrue(controller.enableAuto());
			assertTrue(controller.isAutoEnabled());
			assertTrue(controller.setManualSpeed(250));
			assertFalse(controller.isAutoEnabled());
			assertEquals(250, controller.speedPercent());
			assertFalse(controller.setManualSpeed(2000));
			assertEquals(250, controller.speedPercent());
		} finally {
			controller.close();
			session.close();
		}
	}

	@Test
	public void closedControllerCannotRestartAuto() {
		TimingSession session = new TimingSession(new TestTimeSource(), 100, 1L);
		AutoSpeedController controller = new AutoSpeedController(session);
		controller.close();

		assertFalse(controller.enableAuto());
		assertFalse(controller.setManualSpeed(200));
		session.close();
	}

	private static final class TestTimeSource implements TimingTimeSource {
		@Override
		public long monotonicNanos() {
			return 0L;
		}

		@Override
		public long wallTimeMillis() {
			return 1_000L;
		}
	}
}
