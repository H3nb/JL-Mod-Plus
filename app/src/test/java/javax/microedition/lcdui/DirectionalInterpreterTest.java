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
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class DirectionalInterpreterTest {
	private static final float EPSILON = 0.0001f;

	@Test
	public void nonAnalogInputsKeepDirectRadialThresholds() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();

		assertSame(DirectionalInterpreter.Direction.CENTER, sample(interpreter, 0.19f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 0.20f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT, sample(interpreter, 0.15f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER, sample(interpreter, 0.14f, 0.0f, 8));
	}

	@Test
	public void analogActivationUsesProcessedPressAndReleaseHysteresis() {
		float radialSpan =
				AnalogRadialProcessor.OUTER_SATURATION - AnalogRadialProcessor.INNER_DEADZONE;
		float rawPress = AnalogRadialProcessor.INNER_DEADZONE
				+ DirectionalInterpreter.ANALOG_PRESS_RADIUS * radialSpan;
		float rawRelease = AnalogRadialProcessor.INNER_DEADZONE
				+ DirectionalInterpreter.ANALOG_RELEASE_RADIUS * radialSpan;
		assertEquals(0.55f, rawPress, EPSILON);
		assertEquals(0.43f, rawRelease, EPSILON);

		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.CENTER,
				sampleAnalog(interpreter, rawPress - 0.01f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, rawPress, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, rawRelease + 0.01f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER,
				sampleAnalog(interpreter, rawRelease, 0.0f, 8));
	}

	@Test
	public void sameAngleKeepsSameSectorAcrossActiveAnalogMagnitudes() {
		DirectionalInterpreter diagonal = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT,
				sampleAnalog(diagonal, 0.60f, 45.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT,
				sampleAnalog(diagonal, 0.75f, 45.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT,
				sampleAnalog(diagonal, 1.00f, 45.0f, 8));

		DirectionalInterpreter cardinal = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(cardinal, 0.60f, 2.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(cardinal, 0.75f, 2.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(cardinal, 1.00f, 2.0f, 8));
	}

	@Test
	public void allEightSectorsResolveNearTheirCenters() {
		assertFreshAnalog(DirectionalInterpreter.Direction.RIGHT, 3.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.DOWN_RIGHT, 48.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.DOWN, 93.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.DOWN_LEFT, 138.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.LEFT, 183.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.UP_LEFT, 228.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.UP, 273.0f, 8);
		assertFreshAnalog(DirectionalInterpreter.Direction.UP_RIGHT, 318.0f, 8);
	}

	@Test
	public void analogAngularHysteresisPreventsBoundaryFlickerInBothDirections() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 29.9f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT,
				sampleAnalog(interpreter, 1.0f, 30.1f, 8));

		assertSame(DirectionalInterpreter.Direction.DOWN_RIGHT,
				sampleAnalog(interpreter, 1.0f, 15.1f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 14.9f, 8));
	}

	@Test
	public void fourWayUsesNearestCardinalWithAnalogAngularHysteresis() {
		assertFreshAnalog(DirectionalInterpreter.Direction.RIGHT, 40.0f, 4);
		assertFreshAnalog(DirectionalInterpreter.Direction.DOWN, 50.0f, 4);

		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 0.0f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 52.4f, 4));
		assertSame(DirectionalInterpreter.Direction.DOWN,
				sampleAnalog(interpreter, 1.0f, 52.6f, 4));

		assertSame(DirectionalInterpreter.Direction.DOWN,
				sampleAnalog(interpreter, 1.0f, 37.6f, 4));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 1.0f, 37.4f, 4));
	}

	@Test
	public void movingInwardReleasesWithoutNearCenterSectorWander() {
		float rawRelease = AnalogRadialProcessor.INNER_DEADZONE
				+ DirectionalInterpreter.ANALOG_RELEASE_RADIUS
				* (AnalogRadialProcessor.OUTER_SATURATION
				- AnalogRadialProcessor.INNER_DEADZONE);
		DirectionalInterpreter interpreter = new DirectionalInterpreter();

		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, 0.80f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.RIGHT,
				sampleAnalog(interpreter, rawRelease + 0.01f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER,
				sampleAnalog(interpreter, rawRelease, 0.0f, 8));
	}

	@Test
	public void returnToCenterClearsCurrentSector() {
		DirectionalInterpreter interpreter = new DirectionalInterpreter();
		assertSame(DirectionalInterpreter.Direction.UP_RIGHT,
				sampleAnalog(interpreter, 0.8f, 315.0f, 8));
		assertSame(DirectionalInterpreter.Direction.CENTER,
				sampleAnalog(interpreter, 0.0f, 0.0f, 8));
		assertSame(DirectionalInterpreter.Direction.DOWN_LEFT,
				sampleAnalog(interpreter, 0.8f, 135.0f, 8));
	}

	private static void assertFreshAnalog(
			DirectionalInterpreter.Direction expected,
			float degrees,
			int sectors) {
		assertSame(expected, sampleAnalog(new DirectionalInterpreter(), 0.8f, degrees, sectors));
	}

	private static DirectionalInterpreter.Direction sample(
			DirectionalInterpreter interpreter,
			float magnitude,
			float degrees,
			int sectors) {
		return sample(interpreter, magnitude, degrees, sectors, false);
	}

	private static DirectionalInterpreter.Direction sampleAnalog(
			DirectionalInterpreter interpreter,
			float magnitude,
			float degrees,
			int sectors) {
		return sample(interpreter, magnitude, degrees, sectors, true);
	}

	private static DirectionalInterpreter.Direction sample(
			DirectionalInterpreter interpreter,
			float magnitude,
			float degrees,
			int sectors,
			boolean analog) {
		double radians = Math.toRadians(degrees);
		float x = (float) (Math.cos(radians) * magnitude);
		float y = (float) (Math.sin(radians) * magnitude);
		if (Math.abs(x) < EPSILON) x = 0.0f;
		if (Math.abs(y) < EPSILON) y = 0.0f;
		return analog ? interpreter.updateAnalog(x, y, sectors) : interpreter.update(x, y, sectors);
	}
}
