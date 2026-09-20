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

public class StandardVirtualControlsLayoutTest {
	private static final float EPS = 4.0f;

	@Test
	public void portraitReferenceUsesFreeBottomDeck() {
		float width = 709.0f;
		float height = 1536.0f;
		float guestBottom = 944.0f;

		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height,
				0.0f, 0.0f, width, guestBottom);

		assertEquals(
				StandardVirtualControlsLayout.Placement.BOTTOM_DECK,
				layout.placement);
		assertEquals(101.0f, layout.keySize, EPS);
		assertEquals(143.0f, layout.movementRadius, EPS);
		assertEquals(177.0f, layout.movementCenterX, EPS);
		assertEquals(1270.0f, layout.movementCenterY, EPS);
		assertEquals(111.0f, layout.shoulderLeftX, EPS);
		assertEquals(598.0f, layout.shoulderRightX, EPS);
		assertEquals(1006.0f, layout.shoulderCenterY, EPS);
		assertEquals(540.0f, layout.actionCenterX, EPS);
		assertEquals(1221.0f, layout.actionCenterY, EPS);
		assertEquals(449.0f, layout.bottomLeftX, EPS);
		assertEquals(630.0f, layout.bottomRightX, EPS);
		assertEquals(1328.0f, layout.bottomRowY, EPS);

		assertTrue(layout.movementCenterY - layout.movementRadius > guestBottom);
		assertTrue(layout.shoulderCenterY - layout.shoulderHeight * 0.5f > guestBottom);
		assertTrue(layout.actionCenterY - layout.fireSize * 0.5f > guestBottom);
	}

	@Test
	public void landscapeReferenceUsesBothSideGutters() {
		float width = 1536.0f;
		float height = 709.0f;
		float guestLeft = 503.0f;
		float guestRight = 1034.0f;

		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height,
				guestLeft, 0.0f, guestRight, height);

		assertEquals(
				StandardVirtualControlsLayout.Placement.SIDE_GUTTERS,
				layout.placement);
		assertEquals(236.0f, layout.movementCenterX, EPS);
		assertEquals(416.0f, layout.movementCenterY, EPS);
		assertEquals(236.0f, layout.shoulderLeftX, EPS);
		assertEquals(1300.0f, layout.shoulderRightX, EPS);
		assertEquals(146.0f, layout.shoulderCenterY, EPS);
		assertEquals(1300.0f, layout.actionCenterX, EPS);
		assertEquals(355.0f, layout.actionCenterY, EPS);
		assertEquals(1191.0f, layout.bottomLeftX, EPS);
		assertEquals(1409.0f, layout.bottomRightX, EPS);
		assertEquals(464.0f, layout.bottomRowY, EPS);

		assertTrue(layout.movementCenterX + layout.movementRadius < guestLeft);
		assertTrue(layout.shoulderLeftX + layout.shoulderWidth * 0.5f < guestLeft);
		assertTrue(layout.shoulderRightX - layout.shoulderWidth * 0.5f > guestRight);
		assertTrue(layout.bottomLeftX - layout.keySize * 0.5f > guestRight);
	}

	@Test
	public void fullScreenGuestFallsBackToCompactOverlayInsideBounds() {
		float width = 480.0f;
		float height = 800.0f;

		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height,
				0.0f, 0.0f, width, height);

		assertEquals(
				StandardVirtualControlsLayout.Placement.COMPACT_OVERLAY,
				layout.placement);
		assertTrue(layout.movementCenterX - layout.movementRadius >= 0.0f);
		assertTrue(layout.movementCenterY - layout.movementRadius >= 0.0f);
		assertTrue(layout.movementCenterX + layout.movementRadius <= width);
		assertTrue(layout.movementCenterY + layout.movementRadius <= height);
		assertTrue(layout.shoulderLeftX - layout.shoulderWidth * 0.5f >= 0.0f);
		assertTrue(layout.shoulderRightX + layout.shoulderWidth * 0.5f <= width);
		assertTrue(layout.actionCenterX + layout.fireSize * 0.5f <= width);
		assertTrue(layout.bottomLeftX - layout.keySize * 0.5f >= 0.0f);
		assertTrue(layout.bottomRightX + layout.keySize * 0.5f <= width);
		assertTrue(layout.bottomRowY + layout.keySize * 0.5f <= height);
	}
}
