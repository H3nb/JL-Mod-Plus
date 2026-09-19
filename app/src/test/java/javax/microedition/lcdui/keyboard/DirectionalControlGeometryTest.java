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

package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectionalControlGeometryTest {
	private static final float EPSILON = 0.0001f;
	private static final float CENTER_X = 100.0f;
	private static final float CENTER_Y = 200.0f;
	private static final float RADIUS = 50.0f;

	@Test
	public void centerProducesNeutralVectorAndCenteredThumb() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X, CENTER_Y);

		assertEquals(0.0f, sample.x, EPSILON);
		assertEquals(0.0f, sample.y, EPSILON);
		assertEquals(CENTER_X, sample.thumbX, EPSILON);
		assertEquals(CENTER_Y, sample.thumbY, EPSILON);
	}

	@Test
	public void edgeCardinalProducesUnitVector() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.sample(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X + RADIUS, CENTER_Y);

		assertEquals(1.0f, sample.x, EPSILON);
		assertEquals(0.0f, sample.y, EPSILON);
	}

	@Test
	public void diagonalClampsToCircularBoundary() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS, CENTER_Y - RADIUS);

		float magnitude = (float) Math.hypot(sample.x, sample.y);
		float thumbDistance = (float) Math.hypot(
				sample.thumbX - CENTER_X, sample.thumbY - CENTER_Y);
		assertEquals(1.0f, magnitude, EPSILON);
		assertEquals(RADIUS, thumbDistance, EPSILON);
		assertTrue(sample.x > 0.0f);
		assertTrue(sample.y < 0.0f);
	}

	@Test
	public void analogTouchDownIsNeutralUntilFirstMove() {
		DirectionalControlGeometry.Sample down = DirectionalControlGeometry.gestureSample(
				true, false, CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS, CENTER_Y);
		assertEquals(0.0f, down.x, EPSILON);
		assertEquals(0.0f, down.y, EPSILON);
		assertEquals(CENTER_X, down.thumbX, EPSILON);
		assertEquals(CENTER_Y, down.thumbY, EPSILON);

		DirectionalControlGeometry.Sample move = DirectionalControlGeometry.gestureSample(
				true, true, CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS, CENTER_Y);
		assertEquals(1.0f, move.x, EPSILON);
		assertEquals(0.0f, move.y, EPSILON);
		assertEquals(CENTER_X + RADIUS, move.thumbX, EPSILON);
	}

	@Test
	public void dpadTouchDownRemainsImmediate() {
		DirectionalControlGeometry.Sample down = DirectionalControlGeometry.gestureSample(
				false, false, CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS, CENTER_Y);

		assertEquals(1.0f, down.x, EPSILON);
		assertEquals(0.0f, down.y, EPSILON);
	}

	@Test
	public void analogHalfDeflectionProducesHalfVisualTravel() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 0.5f, CENTER_Y);

		assertEquals(0.5f, sample.x, EPSILON);
		assertEquals(CENTER_X + RADIUS * 0.5f, sample.thumbX, EPSILON);
		assertEquals(CENTER_Y, sample.thumbY, EPSILON);
	}

	@Test
	public void analogFullDeflectionMovesThumbCenterToBaseRadius() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS, CENTER_Y);

		assertEquals(1.0f, (float) Math.hypot(sample.x, sample.y), EPSILON);
		assertEquals(RADIUS, sample.thumbX - CENTER_X, EPSILON);
	}

	@Test
	public void analogThumbRadiusUsesSmallerFixedScale() {
		assertEquals(0.28f, DirectionalControlGeometry.ANALOG_THUMB_RADIUS_SCALE, EPSILON);
		assertEquals(RADIUS * 0.28f,
				DirectionalControlGeometry.analogThumbRadius(RADIUS), EPSILON);
		assertTrue(DirectionalControlGeometry.analogThumbRadius(RADIUS) < RADIUS * 0.44f);
	}

	@Test
	public void radialCaptureScaleRemainsOnePointZeroFive() {
		assertEquals(1.05f, DirectionalControlGeometry.ANALOG_CAPTURE_RADIUS_SCALE, EPSILON);
		assertTrue(DirectionalControlGeometry.containsAnalogCapture(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 1.04f, CENTER_Y));
		assertTrue(!DirectionalControlGeometry.containsAnalogCapture(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 1.06f, CENTER_Y));
		assertTrue(!DirectionalControlGeometry.containsAnalogCapture(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 0.9f, CENTER_Y + RADIUS * 0.9f));
	}

	@Test
	public void samplingOutsideCaptureAreaStillClampsForCapturedDrag() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 2.0f, CENTER_Y);

		assertEquals(1.0f, sample.x, EPSILON);
		assertEquals(0.0f, sample.y, EPSILON);
		assertEquals(CENTER_X + RADIUS, sample.thumbX, EPSILON);
	}
}
