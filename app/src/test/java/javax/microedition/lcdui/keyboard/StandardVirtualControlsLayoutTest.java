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
	private static final float EPS = 0.01f;

	@Test
	public void landscapeSpreadsShouldersAndKeepsActionClusterOnRight() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 1820.0f, 864.0f,
				590.0f, 0.0f, 1190.0f, 785.0f,
				0.20f * 864.0f);

		assertTrue(layout.movementUsesLeftGutter);
		assertTrue(layout.actionsUseRightGutter);
		assertTrue(layout.shoulderLeftX < 590.0f);
		assertTrue(layout.shoulderRightX > 1190.0f);
		assertTrue(layout.shoulderWidth > layout.shoulderHeight);
		assertTrue(layout.movementCenterX < layout.actionCenterX);
		assertTrue(layout.movementCenterY > layout.actionCenterY);
		assertTrue(layout.bottomRowY > layout.actionCenterY);
		assertEquals(layout.actionCenterX,
				(layout.bottomLeftX + layout.bottomRightX) * 0.5f, EPS);
	}

	@Test
	public void portraitKeepsMovementAndActionZonesSeparated() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 864.0f, 1820.0f,
				0.0f, 0.0f, 864.0f, 1110.0f,
				0.20f * 864.0f);

		assertFalse(layout.movementUsesLeftGutter);
		assertFalse(layout.actionsUseRightGutter);
		assertTrue(layout.movementCenterY > 1820.0f * 0.55f);
		assertTrue(layout.actionCenterY > 1820.0f * 0.45f);
		assertTrue(layout.actionCenterY - layout.keySize * 0.5f > 1110.0f);
		assertTrue(layout.shoulderCenterY < layout.actionCenterY);
		assertTrue(layout.bottomRowY > layout.actionCenterY);
		assertTrue(layout.movementCenterX < layout.bottomLeftX);
	}

	@Test
	public void portraitPlacesShouldersAtTopAndMovementAtBottom() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 864.0f, 1820.0f,
				0.0f, 0.0f, 864.0f, 1110.0f,
				0.20f * 864.0f);

		assertTrue(layout.shoulderCenterY < 1820.0f * 0.25f);
		assertTrue(layout.shoulderLeftX < 864.0f * 0.35f);
		assertTrue(layout.shoulderRightX > 864.0f * 0.65f);
		assertTrue(layout.movementCenterY > 1820.0f * 0.55f);
		assertTrue(layout.actionCenterY > 1820.0f * 0.45f);
		assertTrue(layout.bottomRowY > layout.actionCenterY);
	}

	@Test
	public void fullScreenLandscapeStaysInsideHostBounds() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 1920.0f, 1080.0f,
				0.0f, 0.0f, 1920.0f, 1080.0f,
				0.20f * 1080.0f);

		assertFalse(layout.movementUsesLeftGutter);
		assertFalse(layout.actionsUseRightGutter);
		assertTrue(layout.movementCenterX - 216.0f > 0.0f);
		assertTrue(layout.movementCenterX + 216.0f < 1920.0f);
		assertTrue(layout.shoulderLeftX - layout.shoulderWidth * 0.5f > 0.0f);
		assertTrue(layout.shoulderRightX + layout.shoulderWidth * 0.5f < 1920.0f);
		assertTrue(layout.bottomLeftX > 0.0f);
		assertTrue(layout.bottomRightX < 1920.0f);
	}
}
