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
	public void cutoutRequiresCanvasSkinAndBothBarsDisabled() {
		assertTrue(GuestWindowPolicy.canUseDisplayCutout(true, true, false, false));
		assertFalse(GuestWindowPolicy.canUseDisplayCutout(false, true, false, false));
		assertFalse(GuestWindowPolicy.canUseDisplayCutout(true, false, false, false));
		assertFalse(GuestWindowPolicy.canUseDisplayCutout(true, true, true, false));
		assertFalse(GuestWindowPolicy.canUseDisplayCutout(true, true, false, true));
	}

	@Test
	public void immersiveCanvasWithAllowedCutoutKeepsGuestGeometryUnpadded() {
		GuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
				true, true, false, false,
				30, 40, 50, 60,
				7, 8, 9, 100);

		assertPadding(padding, 0, 0, 0, 0);
	}

	@Test
	public void canvasReservesCutoutButNeverNavigationBarWhenCutoutIsDisallowed() {
		GuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
				true, false, false, true,
				30, 40, 50, 60,
				7, 8, 9, 100);

		assertPadding(padding, 7, 8, 9, 0);
	}

	@Test
	public void visibleStatusBarIsIncludedForCanvasButCutoutPolicyStillApplies() {
		GuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
				true, true, true, false,
				30, 40, 50, 60,
				7, 8, 9, 100);

		assertPadding(padding, 7, 40, 9, 0);
	}

	@Test
	public void hostDisplayableReservesSystemCutoutAndImeInsets() {
		GuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
				false, true, false, false,
				30, 40, 50, 60,
				7, 8, 9, 100);

		assertPadding(padding, 30, 40, 50, 100);
	}

	private static void assertPadding(GuestWindowPolicy.Padding padding,
			int left, int top, int right, int bottom) {
		assertEquals(left, padding.left);
		assertEquals(top, padding.top);
		assertEquals(right, padding.right);
		assertEquals(bottom, padding.bottom);
	}
}
