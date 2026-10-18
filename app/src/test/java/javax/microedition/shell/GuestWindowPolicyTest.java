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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuestWindowPolicyTest {
	@Test
	public void fullscreenAndGuestCutoutAreIndependentCanvasChoices() {
		GuestWindowPolicy.Chrome withToolbar = GuestWindowPolicy.resolve(true, false, true);
		assertTrue(withToolbar.toolbarVisible);
		assertTrue(withToolbar.cutoutAllowed);
		assertFalse(withToolbar.statusBarVisible);
		assertFalse(withToolbar.navigationBarVisible);
		GuestWindowPolicy.Chrome fullscreen = GuestWindowPolicy.resolve(true, true, false);
		assertFalse(fullscreen.toolbarVisible);
		assertFalse(fullscreen.cutoutAllowed);
	}

	@Test
	public void formRestoresHostBarsWithoutChangingStoredFullscreenChoice() {
		GuestWindowPolicy.Chrome form = GuestWindowPolicy.resolve(false, true, true);
		assertTrue(form.toolbarVisible);
		assertTrue(form.statusBarVisible);
		assertTrue(form.navigationBarVisible);
		assertFalse(form.cutoutAllowed);
		assertFalse(GuestWindowPolicy.resolve(true, true, true).toolbarVisible);
	}

	@Test
	public void gameReservesOnlyCutoutRemainingBelowCompactToolbar() {
		assertPadding(GuestWindowPolicy.paddingForBounds(
				1080, 2400, 0, 48, 1080, 2352, 0, 32, 0, 0), 0, 0, 0, 0);
		assertPadding(GuestWindowPolicy.paddingForBounds(
				1080, 2400, 0, 48, 1080, 2352, 0, 80, 0, 0), 0, 32, 0, 0);
	}

	@Test
	public void sideCutoutStillAffectsLandscapeGameBelowToolbar() {
		GuestWindowPolicy.Padding padding = GuestWindowPolicy.paddingForBounds(
				2400, 1080, 0, 48, 2400, 1032, 80, 0, 0, 0);
		assertPadding(padding, 80, 0, 0, 0);
		// A nested guest already inside its host's safe bounds needs no extra padding.
		assertPadding(GuestWindowPolicy.paddingForBounds(
				2400, 1080, 80, 48, 2320, 1032, 80, 0, 0, 0), 0, 0, 0, 0);
	}

	@Test
	public void windowOffsetsAndBottomImeAreAppliedOnlyWhereTheyOverlap() {
		assertPadding(GuestWindowPolicy.paddingForBounds(
				1000, 800, 20, 100, 950, 700, 30, 80, 40, 300), 10, 0, 10, 300);
	}

	@Test
	public void tinyWindowsRetainAPositiveDrawableArea() {
		assertPadding(GuestWindowPolicy.paddingForBounds(
				10, 10, 0, 0, 10, 10, 40, 50, 60, 70), 9, 9, 0, 0);
	}

	@Test
	public void roundedClearanceDependsOnWhereControlsMeetTheCorner() {
		assertEquals(40, GuestWindowPolicy.roundedCornerClearance(40, 40, 0, 48, true));
		assertEquals(0, GuestWindowPolicy.roundedCornerClearance(40, 40, 40, 88, true));
		assertEquals(6, GuestWindowPolicy.roundedCornerClearance(40, 40, 20, 68, true));
		assertEquals(0, GuestWindowPolicy.roundedCornerClearance(40, 760, 0, 48, false));
		assertEquals(40, GuestWindowPolicy.roundedCornerClearance(40, 760, 752, 800, false));
	}

	private static void assertPadding(GuestWindowPolicy.Padding padding,
			int left, int top, int right, int bottom) {
		assertEquals(left, padding.left);
		assertEquals(top, padding.top);
		assertEquals(right, padding.right);
		assertEquals(bottom, padding.bottom);
	}
}
