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
		assertEquals(0x000000, VirtualControlAppearance.mixRgb(0x000000, 0xFFFFFF, 0.0f));
		assertEquals(0xFFFFFF, VirtualControlAppearance.mixRgb(0x000000, 0xFFFFFF, 1.0f));
		assertEquals(0xFF0000, VirtualControlAppearance.analogThumb(profile, true));
	}

	@Test
	public void analogColorsRespectBackgroundAndPressedState() {
		ProfileModel profile = new ProfileModel();
		profile.vkBgColor = 0x000000;
		profile.vkBgColorSelected = 0xFF0000;
		profile.vkOutlineColor = 0xFFFFFF;

		assertEquals(0x000000, VirtualControlAppearance.analogShell(profile.vkBgColor));
		assertEquals(0x111111, VirtualControlAppearance.analogShellTop(profile.vkBgColor));
		assertEquals(0x000000, VirtualControlAppearance.analogShellBottom(profile.vkBgColor));
		assertEquals(0x1A1A1A, VirtualControlAppearance.analogThumb(profile, false));
		assertEquals(0xFF0000, VirtualControlAppearance.analogThumb(profile, true));
		assertEquals(0x090909, VirtualControlAppearance.analogBowlCenter(profile.vkBgColor));
		assertEquals(0x040404, VirtualControlAppearance.analogBowlEdge(profile.vkBgColor));

		// Outline changes must never tint the material surfaces.
		profile.vkOutlineColor = 0x00FF00;
		assertEquals(0x000000, VirtualControlAppearance.analogShell(profile.vkBgColor));
		assertEquals(0x1A1A1A, VirtualControlAppearance.analogThumb(profile, false));

		profile.vkBgColor = 0xFFFFFF;
		assertEquals(0xFFFFFF, VirtualControlAppearance.analogShell(profile.vkBgColor));
		assertEquals(0xE6E6E6, VirtualControlAppearance.analogThumb(profile, false));
		assertEquals(0xEBEBEB, VirtualControlAppearance.analogBowlCenter(profile.vkBgColor));
		assertEquals(0xBABABA, VirtualControlAppearance.analogBowlEdge(profile.vkBgColor));

		profile.vkBgColor = 0xFF0000;
		assertEquals(0xFF0000, VirtualControlAppearance.analogShell(profile.vkBgColor));
		assertEquals(0xFF1A1A, VirtualControlAppearance.analogThumb(profile, false));
		assertEquals(0xFF0000, VirtualControlAppearance.analogThumb(profile, true));
		assertEquals(0xE90000, VirtualControlAppearance.analogShellBottom(profile.vkBgColor));
		assertEquals(0xEB0000, VirtualControlAppearance.analogBowlCenter(profile.vkBgColor));
		assertEquals(0xBA0000, VirtualControlAppearance.analogBowlEdge(profile.vkBgColor));

		// Recess and shadow retain the hue of the configured color.
		profile.vkBgColor = 0x967879;
		assertEquals(0x967879, VirtualControlAppearance.analogShell(profile.vkBgColor));
		assertEquals(0x8A6E6F, VirtualControlAppearance.analogBowlCenter(profile.vkBgColor));
		assertEquals(0x6E5858, VirtualControlAppearance.analogBowlEdge(profile.vkBgColor));
		assertEquals(0x574646, VirtualControlAppearance.analogThumbShadow(profile.vkBgColor));
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
