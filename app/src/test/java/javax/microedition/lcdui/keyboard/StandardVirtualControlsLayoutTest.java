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
	private static final float EPS = 2.0f;

	@Test
	public void referenceCanvasMatchesApprovedComposition() {
		float width = 1275.0f;
		float height = 1056.0f;
		float radius = 0.238f * height;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, radius);

		assertEquals(width * 0.248f, layout.movementCenterX, EPS);
		assertEquals(height * 0.552f, layout.movementCenterY, EPS);
		assertEquals(width * 0.164f, layout.shoulderLeftX, EPS);
		assertEquals(width * 0.837f, layout.shoulderRightX, EPS);
		assertEquals(height * 0.122f, layout.shoulderCenterY, EPS);
		assertEquals(width * 0.740f, layout.actionCenterX, EPS);
		assertEquals(height * 0.509f, layout.actionCenterY, EPS);
		assertEquals(width * 0.592f, layout.bottomLeftX, EPS);
		assertEquals(width * 0.885f, layout.bottomRightX, EPS);
		assertEquals(height * 0.690f, layout.bottomRowY, EPS);
		assertEquals(height * 0.178f, layout.keySize, EPS);
		assertEquals(layout.keySize * 1.66f, layout.shoulderWidth, EPS);
		assertEquals(layout.keySize * 0.73f, layout.shoulderHeight, EPS);
		assertEquals(layout.keySize * 1.25f, layout.fireSize, EPS);
	}

	@Test
	public void portraitUsesTheSameScreenSpaceComposition() {
		float width = 945.0f;
		float height = 2048.0f;
		float radius = 0.238f * width;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, radius);

		assertEquals(width * 0.248f, layout.movementCenterX, 15.0f);
		assertEquals(height * 0.552f, layout.movementCenterY, EPS);
		assertEquals(width * 0.740f, layout.actionCenterX, EPS);
		assertEquals(height * 0.509f, layout.actionCenterY, EPS);
		assertEquals(width * 0.592f, layout.bottomLeftX, EPS);
		assertEquals(width * 0.885f, layout.bottomRightX, EPS);
		assertEquals(height * 0.690f, layout.bottomRowY, EPS);

		float movementRight = layout.movementCenterX + radius;
		float starLeft = layout.bottomLeftX - layout.keySize * 0.5f;
		assertTrue(movementRight < starLeft);
	}

	@Test
	public void compactScreenClampsControlsInsideSafeBounds() {
		float width = 480.0f;
		float height = 800.0f;
		float radius = 0.238f * width;
		StandardVirtualControlsLayout layout = StandardVirtualControlsLayout.resolve(
				0.0f, 0.0f, width, height, radius);

		assertTrue(layout.movementCenterX - radius >= 0.0f);
		assertTrue(layout.movementCenterY - radius >= 0.0f);
		assertTrue(layout.movementCenterX + radius <= width);
		assertTrue(layout.movementCenterY + radius <= height);
		assertTrue(layout.shoulderLeftX - layout.shoulderWidth * 0.5f >= 0.0f);
		assertTrue(layout.shoulderRightX + layout.shoulderWidth * 0.5f <= width);
		assertTrue(layout.bottomLeftX - layout.keySize * 0.5f >= 0.0f);
		assertTrue(layout.bottomRightX + layout.keySize * 0.5f <= width);
	}
}
