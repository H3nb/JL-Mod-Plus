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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StandardVirtualControlsLayoutTest {
	private static final float EPS = 2.0f;

	@Test
	public void landscapeFullScreenMatchesReferenceComposition() {
		float width = 1275.0f;
		float height = 1056.0f;
		float radius = 0.24f * height;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height,
				0.0f, 0.0f, width, height,
				radius);

		assertTrue(layout.landscape);
		assertEquals(width * 0.25f, layout.movementCenterX, EPS);
		assertEquals(height * 0.55f, layout.movementCenterY, EPS);
		assertEquals(width * 0.165f, layout.shoulderLeftX, EPS);
		assertEquals(width * 0.835f, layout.shoulderRightX, EPS);
		assertEquals(height * 0.125f, layout.shoulderCenterY, EPS);
		assertEquals(width * 0.74f, layout.actionCenterX, EPS);
		assertEquals(height * 0.51f, layout.actionCenterY, EPS);
		assertEquals(height * 0.69f, layout.bottomRowY, EPS);
		assertEquals(height * 0.178f, layout.keySize, EPS);
		assertTrue(layout.shoulderWidth > layout.fireSize);
		assertTrue(layout.fireSize > layout.keySize);
	}

	@Test
	public void portraitLetterboxKeepsGameplayControlsInsideGuestViewport() {
		float screenWidth = 945.0f;
		float screenHeight = 2048.0f;
		float guestLeft = 18.0f;
		float guestTop = 0.0f;
		float guestRight = 812.0f;
		float guestBottom = 1118.0f;
		float radius = 0.20f * screenWidth;

		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, screenWidth, screenHeight,
				guestLeft, guestTop, guestRight, guestBottom,
				radius);

		assertFalse(layout.landscape);
		assertTrue(layout.movementCenterX - radius >= guestLeft - EPS);
		assertTrue(layout.movementCenterY + radius <= guestBottom + EPS);
		assertTrue(layout.actionCenterX < guestRight);
		assertTrue(layout.actionCenterY < guestBottom);
		assertTrue(layout.bottomLeftX > guestLeft);
		assertTrue(layout.bottomRightX < guestRight);
		assertTrue(layout.bottomLeftX - layout.keySize * 0.5f
				> layout.movementCenterX + radius);
		assertTrue(layout.bottomRowY < guestBottom);
		assertTrue(layout.shoulderCenterY < layout.actionCenterY);
		assertTrue(layout.movementCenterY > layout.actionCenterY);
	}

	@Test
	public void invalidGuestFallsBackToScreenBounds() {
		float width = 1080.0f;
		float height = 2400.0f;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height,
				0.0f, 0.0f, 0.0f, 0.0f,
				0.20f * width);

		assertFalse(layout.landscape);
		assertTrue(layout.movementCenterY < height);
		assertTrue(layout.bottomRowY < height);
		assertTrue(layout.bottomRightX < width);
	}
}
