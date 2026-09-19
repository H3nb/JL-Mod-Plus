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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DirectionalInterpreterTest {
	@Test
	public void centerAndCardinalsResolveIndependentlyPerAxis() {
		assertDirection(new DirectionalInterpreter().update(0.0f, 0.0f), 0, 0);
		assertDirection(new DirectionalInterpreter().update(1.0f, 0.0f), 1, 0);
		assertDirection(new DirectionalInterpreter().update(-1.0f, 0.0f), -1, 0);
		assertDirection(new DirectionalInterpreter().update(0.0f, -1.0f), 0, -1);
		assertDirection(new DirectionalInterpreter().update(0.0f, 1.0f), 0, 1);
	}

	@Test
	public void allFourDiagonalsAreSupported() {
		assertDirection(new DirectionalInterpreter().update(-1.0f, -1.0f), -1, -1);
		assertDirection(new DirectionalInterpreter().update(1.0f, -1.0f), 1, -1);
		assertDirection(new DirectionalInterpreter().update(-1.0f, 1.0f), -1, 1);
		assertDirection(new DirectionalInterpreter().update(1.0f, 1.0f), 1, 1);
	}

	@Test
	public void returnToCenterReleasesBothComponents() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertDirection(interpreter.update(1.0f, -1.0f), 1, -1);
		assertDirection(interpreter.update(0.0f, 0.0f), 0, 0);
	}

	@Test
	public void hysteresisKeepsDirectionStableBetweenPressAndReleaseThresholds() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertDirection(interpreter.update(
				DirectionalInterpreter.PRESS_THRESHOLD + 0.01f, 0.0f), 1, 0);
		assertDirection(interpreter.update(
				(DirectionalInterpreter.PRESS_THRESHOLD
						+ DirectionalInterpreter.RELEASE_THRESHOLD) / 2.0f, 0.0f), 1, 0);
		assertDirection(interpreter.update(
				DirectionalInterpreter.RELEASE_THRESHOLD - 0.01f, 0.0f), 0, 0);
	}

	@Test
	public void upToDiagonalToRightKeepsUnchangedComponentStable() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertDirection(interpreter.update(0.0f, -1.0f), 0, -1);
		assertDirection(interpreter.update(1.0f, -1.0f), 1, -1);
		assertDirection(interpreter.update(1.0f, 0.0f), 1, 0);
	}

	private static void assertDirection(
			DirectionalInterpreter.Direction direction,
			int horizontal,
			int vertical) {
		assertEquals(horizontal, direction.horizontal);
		assertEquals(vertical, direction.vertical);
	}
}
