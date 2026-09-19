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
	private static final float THUMB_RADIUS = 18.0f;

	@Test
	public void centerProducesNeutralVectorAndCenteredThumb() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.sample(
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
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.sample(
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
	public void touchBeyondRadiusKeepsThumbClamped() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.sample(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X + RADIUS * 3.0f, CENTER_Y);

		assertEquals(1.0f, sample.x, EPSILON);
		assertEquals(CENTER_X + RADIUS, sample.thumbX, EPSILON);
	}

	@Test
	public void analogFullDeflectionKeepsNormalizedMagnitudeOneAndThumbInsideBase() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS, THUMB_RADIUS,
				CENTER_X + RADIUS, CENTER_Y);

		assertEquals(1.0f, (float) Math.hypot(sample.x, sample.y), EPSILON);
		assertEquals(RADIUS - THUMB_RADIUS, sample.thumbX - CENTER_X, EPSILON);
		assertTrue(Math.hypot(sample.thumbX - CENTER_X, sample.thumbY - CENTER_Y)
				+ THUMB_RADIUS <= RADIUS + EPSILON);
	}

	@Test
	public void analogDiagonalFullDeflectionKeepsThumbInsideBase() {
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS, THUMB_RADIUS,
				CENTER_X + RADIUS, CENTER_Y + RADIUS);

		assertEquals(1.0f, (float) Math.hypot(sample.x, sample.y), EPSILON);
		assertTrue(Math.hypot(sample.thumbX - CENTER_X, sample.thumbY - CENTER_Y)
				+ THUMB_RADIUS <= RADIUS + EPSILON);
	}

	@Test
	public void radialHitTestRejectsBoundingSquareCorners() {
		assertTrue(DirectionalControlGeometry.containsRadially(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X, CENTER_Y));
		assertTrue(DirectionalControlGeometry.containsRadially(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X + RADIUS * 0.9f, CENTER_Y));
		assertTrue(!DirectionalControlGeometry.containsRadially(
				CENTER_X, CENTER_Y, RADIUS,
				CENTER_X + RADIUS * 0.9f, CENTER_Y + RADIUS * 0.9f));
	}

	@Test
	public void samplingOutsideCaptureAreaStillClampsForCapturedDrag() {
		assertTrue(!DirectionalControlGeometry.containsRadially(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X + RADIUS * 2.0f, CENTER_Y));
		DirectionalControlGeometry.Sample sample = DirectionalControlGeometry.analogSample(
				CENTER_X, CENTER_Y, RADIUS, THUMB_RADIUS,
				CENTER_X + RADIUS * 2.0f, CENTER_Y);
		assertEquals(1.0f, sample.x, EPSILON);
		assertEquals(0.0f, sample.y, EPSILON);
	}

	@Test
	public void releaseOrCancelCenterSampleIsNeutral() {
		DirectionalControlGeometry.Sample centered = DirectionalControlGeometry.sample(
				CENTER_X, CENTER_Y, RADIUS, CENTER_X, CENTER_Y);

		assertEquals(0.0f, centered.x, EPSILON);
		assertEquals(0.0f, centered.y, EPSILON);
	}
}
