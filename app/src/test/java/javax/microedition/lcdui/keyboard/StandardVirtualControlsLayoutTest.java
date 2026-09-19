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
	public void landscapeUsesSideGuttersWhenTheyFit() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 1820.0f, 864.0f,
				590.0f, 0.0f, 1190.0f, 785.0f,
				0.18f * 864.0f);

		assertTrue(layout.movementUsesLeftGutter);
		assertTrue(layout.actionsUseRightGutter);
		assertTrue(layout.movementCenterX < 590.0f);
		assertTrue(layout.leftColumnX > 1190.0f);
		assertTrue(layout.rightColumnX < 1820.0f);
		assertTrue(layout.movementCenterY < layout.actionCenterY);
		assertEquals(layout.keySize * 0.15f,
				layout.actionCenterY - layout.movementCenterY, EPS);
		assertEquals(layout.keySize, layout.actionCenterX - layout.leftColumnX, EPS);
		assertEquals(layout.keySize, layout.rightColumnX - layout.actionCenterX, EPS);
		assertEquals(layout.keySize, layout.actionCenterY - layout.topRowY, EPS);
		assertEquals(layout.keySize, layout.bottomRowY - layout.actionCenterY, EPS);
		assertEquals(layout.actionCenterX,
				(layout.leftColumnX + layout.rightColumnX) * 0.5f, EPS);
	}

	@Test
	public void portraitUsesBottomDeckAndKeepsRowsBalanced() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 864.0f, 1820.0f,
				0.0f, 0.0f, 864.0f, 1110.0f,
				0.18f * 864.0f);

		assertFalse(layout.movementUsesLeftGutter);
		assertFalse(layout.actionsUseRightGutter);
		assertTrue(layout.actionCenterY > 1110.0f);
		assertEquals(layout.actionCenterY, layout.movementCenterY, EPS);
		assertEquals(layout.actionCenterY - layout.topRowY,
				layout.bottomRowY - layout.actionCenterY, EPS);
		assertTrue(layout.movementCenterX < layout.leftColumnX);
	}

	@Test
	public void fullScreenGuestFallsBackWithoutLeavingHostBounds() {
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, 1920.0f, 1080.0f,
				0.0f, 0.0f, 1920.0f, 1080.0f,
				0.18f * 1080.0f);

		assertFalse(layout.movementUsesLeftGutter);
		assertFalse(layout.actionsUseRightGutter);
		assertTrue(layout.movementCenterX > 0.0f);
		assertTrue(layout.movementCenterX < 1920.0f);
		assertTrue(layout.topRowY > 0.0f);
		assertTrue(layout.bottomRowY < 1080.0f);
		assertTrue(layout.rightColumnX < 1920.0f);
	}
}
