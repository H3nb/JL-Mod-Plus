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
	private static final float EPS = 1.5f;

	@Test
	public void landscapeMatchesReferenceComposition() {
		float width = 1275.0f;
		float height = 1056.0f;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, 0.20f * height);

		assertTrue(layout.landscape);
		assertEquals(width * 0.25f, layout.movementCenterX, EPS);
		assertEquals(height * 0.55f, layout.movementCenterY, EPS);
		assertEquals(width * 0.165f, layout.shoulderLeftX, EPS);
		assertEquals(width * 0.835f, layout.shoulderRightX, EPS);
		assertEquals(height * 0.125f, layout.shoulderCenterY, EPS);
		assertEquals(width * 0.74f, layout.actionCenterX, EPS);
		assertEquals(height * 0.51f, layout.actionCenterY, EPS);
		assertEquals(height * 0.69f, layout.bottomRowY, EPS);
		assertTrue(layout.shoulderWidth > layout.fireSize);
		assertTrue(layout.shoulderHeight < layout.keySize);
		assertTrue(layout.fireSize > layout.keySize);
	}

	@Test
	public void portraitKeepsSeparatedThumbZones() {
		float width = 1080.0f;
		float height = 2400.0f;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, 0.20f * width);

		assertFalse(layout.landscape);
		assertTrue(layout.shoulderCenterY < layout.actionCenterY);
		assertTrue(layout.actionCenterY < layout.bottomRowY);
		assertTrue(layout.movementCenterX < layout.actionCenterX);
		assertTrue(layout.movementCenterY > layout.actionCenterY);
		assertTrue(layout.bottomLeftX < layout.actionCenterX);
		assertTrue(layout.bottomRightX > layout.actionCenterX);
		assertTrue(layout.movementCenterX + 0.20f * width < layout.bottomLeftX);
	}

	@Test
	public void allCentersStayInsideCompactLandscape() {
		float width = 960.0f;
		float height = 720.0f;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, 0.20f * height);

		assertTrue(layout.movementCenterX > 0.0f);
		assertTrue(layout.movementCenterY > 0.0f);
		assertTrue(layout.shoulderLeftX > 0.0f);
		assertTrue(layout.shoulderRightX < width);
		assertTrue(layout.actionCenterX < width);
		assertTrue(layout.bottomLeftX > 0.0f);
		assertTrue(layout.bottomRightX < width);
	}
}
