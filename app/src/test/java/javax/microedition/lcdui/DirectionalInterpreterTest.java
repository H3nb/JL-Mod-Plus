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

import static org.junit.Assert.assertSame;

import org.junit.Test;

public class DirectionalInterpreterTest {
	private static final float EPSILON = 0.0001f;

	@Test
	public void radialActivationUsesPressAndReleaseHysteresis() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();

		assertSame(DirectionalInterpreter.Direction.CENTER, sample(interpreter, 0.19f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 0.20f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 0.15f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER, sample(interpreter, 0.14f, 0.0f, 8));

		assertSame(DirectionalInterpreter.Direction.CENTER,
				sample(new DirectionalInterpreter(), 0.19f, 225.0f, 8));
		assertSame(DirectionalInterpreter.Direction.UP_LEFT,
				sample(new DirectionalInterpreter(), 0.20f, 225.0f, 8));
	}

	@Test
	public void sameAngleKeepsSameSectorAcrossMagnitude() {
		DirectionalInterpreter diagonal = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT, sample(diagonal, 0.30f, 45.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT, sample(diagonal, 0.60f, 45.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT, sample(diagonal, 1.00f, 45.0f, 8));

		DirectionalInterpreter cardinal = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(cardinal, 0.30f, 2.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(cardinal, 0.60f, 2.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(cardinal, 1.00f, 2.0f, 8));
	}

	@Test
	public void allEightSectorsResolveNearTheirCenters() {
		assertFresh(DirectionalInterpreter.Direction.RIGHT, 3.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.DOWN_RIGHT, 48.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.DOWN, 93.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.DOWN_LEFT, 138.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.LEFT, 183.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.UP_LEFT, 228.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.UP, 273.0f, 8);
		assertFresh(DirectionalInterpreter.Direction.UP_RIGHT, 318.0f, 8);
	}

	@Test
	public void angularHysteresisPreventsBoundaryFlickerInBothDirections() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 22.4f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 22.6f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 28.4f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT, sample(interpreter, 1.0f, 29.0f, 8));

		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT, sample(interpreter, 1.0f, 17.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 16.0f, 8));
	}

	@Test
	public void fourWayUsesNearestCardinalWithAngularHysteresis() {
		assertFresh(DirectionalInterpreter.Direction.RIGHT, 10.0f, 4);
		assertFresh(DirectionalInterpreter.Direction.DOWN, 100.0f, 4);
		assertFresh(DirectionalInterpreter.Direction.LEFT, 190.0f, 4);
		assertFresh(DirectionalInterpreter.Direction.UP, 280.0f, 4);
		assertFresh(DirectionalInterpreter.Direction.RIGHT, 40.0f, 4);
		assertFresh(DirectionalInterpreter.Direction.DOWN, 50.0f, 4);
	}

	@Test
	public void fourWayAngularHysteresisStabilizesCardinalBoundary() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 0.0f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 44.9f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 45.1f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 50.9f, 4));
		assertSame(DirectionalInterpreter.Direction.DOWN, sample(interpreter, 1.0f, 52.0f, 4));

		assertSame(DirectionalInterpreter.Direction.DOWN, sample(interpreter, 1.0f, 40.0f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 1.0f, 38.0f, 4));
	}

	@Test
	public void returnToCenterClearsCurrentSector() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.UP_RIGHT, sample(interpreter, 0.8f, 315.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER, sample(interpreter, 0.0f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_LEFT, sample(interpreter, 0.8f, 135.0f, 8));
	}

	private static void assertFresh(
			DirectionalInterpreter.Direction expected,
			float degrees,
			int sectors) {
		assertSame(expected, sample(new DirectionalInterpreter(), 0.8f, degrees, sectors));
	}

	private static DirectionalInterpreter.Direction sample(
			DirectionalInterpreter interpreter,
			float magnitude,
			float degrees,
			int sectors) {
		double radians = Math.toRadians(degrees);
		float x = (float) (Math.cos(radians) * magnitude);
		float y = (float) (Math.sin(radians) * magnitude);
		if (Math.abs(x) < EPSILON) x = 0.0f;
		if (Math.abs(y) < EPSILON) y = 0.0f;
		return interpreter.update(x, y, sectors);
	}
}
