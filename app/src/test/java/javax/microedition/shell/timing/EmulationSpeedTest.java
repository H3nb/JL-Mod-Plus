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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmulationSpeedTest {
	@Test
	public void supportedPresetsAreExactAndDefensive() {
		assertArrayEquals(
				new int[] {25, 50, 75, 100, 125, 150, 200, 300, 400, 600, 800, 1200, 1600},
				EmulationSpeed.presets());
		int[] presets = EmulationSpeed.presets();
		presets[0] = 100;
		assertEquals(25, EmulationSpeed.presets()[0]);
	}

	@Test
	public void rangeValidationAndMigrationFallbackAreDeterministic() {
		assertTrue(EmulationSpeed.isValidPercent(25));
		assertTrue(EmulationSpeed.isValidPercent(1600));
		assertFalse(EmulationSpeed.isValidPercent(24));
		assertFalse(EmulationSpeed.isValidPercent(1601));
		assertEquals(EmulationSpeed.NORMAL_PERCENT, EmulationSpeed.sanitizePercent(0));
		assertEquals(EmulationSpeed.NORMAL_PERCENT, EmulationSpeed.sanitizePercent(1601));
		assertEquals(250, EmulationSpeed.requireValidPercent(250));
	}

	@Test(expected = IllegalArgumentException.class)
	public void invalidSpeedIsRejected() {
		EmulationSpeed.requireValidPercent(0);
	}
}
