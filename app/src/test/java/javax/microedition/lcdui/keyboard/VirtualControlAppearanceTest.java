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

import org.junit.Test;

import io.github.h3nb.jlmodplus.config.ProfileModel;

public class VirtualControlAppearanceTest {
	@Test
	public void selectedAndNormalColorsPreserveExactConfiguredRgb() {
		ProfileModel profile = new ProfileModel();
		profile.vkBgColor = 0x000000;
		profile.vkFgColor = 0xFFFFFF;
		profile.vkBgColorSelected = 0xFF0000;
		profile.vkFgColorSelected = 0x000000;
		profile.vkOutlineColor = 0x18A0F0;

		assertEquals(0x000000, VirtualControlAppearance.background(profile, false));
		assertEquals(0xFF0000, VirtualControlAppearance.background(profile, true));
		assertEquals(0xFFFFFF, VirtualControlAppearance.foreground(profile, false));
		assertEquals(0x000000, VirtualControlAppearance.foreground(profile, true));
		assertEquals(0x18A0F0, VirtualControlAppearance.outline(profile));
		assertEquals(0xFF0000, VirtualControlAppearance.analogThumb(profile, true));
	}

	@Test
	public void idleAnalogThumbDerivesFromConfiguredColors() {
		ProfileModel profile = new ProfileModel();
		profile.vkBgColor = 0x000000;
		profile.vkOutlineColor = 0xFFFFFF;
		profile.vkBgColorSelected = 0x369ABC;
		assertEquals(0x2E2E2E, VirtualControlAppearance.analogThumb(profile, false));
		assertEquals(0x369ABC, VirtualControlAppearance.analogThumb(profile, true));
	}

	@Test
	public void opacityHonorsEditorAndForceOpaqueOverrides() {
		assertEquals(0, VirtualControlAppearance.opacity(0, false, false));
		assertEquals(64, VirtualControlAppearance.opacity(64, false, false));
		assertEquals(255, VirtualControlAppearance.opacity(64, false, true));
		assertEquals(255, VirtualControlAppearance.opacity(0, true, false));
		assertEquals(255, VirtualControlAppearance.opacity(999, false, false));
		assertEquals(0, VirtualControlAppearance.opacity(-5, false, false));
	}
}
