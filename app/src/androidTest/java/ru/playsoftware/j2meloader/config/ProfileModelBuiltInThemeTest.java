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

package ru.playsoftware.j2meloader.config;

import static org.junit.Assert.assertEquals;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;

public class ProfileModelBuiltInThemeTest {
	@Test
	public void lightTemplateUsesOpaqueLightPalette() {
		File dir = new File(
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
				"built-in-light-profile");
		ProfileModel profile = ProfileModel.createBuiltIn(dir, false);

		assertEquals(0xFFFFFF, profile.screenBackgroundColor);
		assertEquals(255, profile.vkAlpha);
		assertEquals(0xFFFFFF, profile.vkBgColor);
		assertEquals(0x000000, profile.vkFgColor);
		assertEquals(0x000000, profile.vkBgColorSelected);
		assertEquals(0xFFFFFF, profile.vkFgColorSelected);
		assertEquals(0x000000, profile.vkOutlineColor);
	}

	@Test
	public void darkTemplateUsesOpaqueDarkPalette() {
		File dir = new File(
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
				"built-in-dark-profile");
		ProfileModel profile = ProfileModel.createBuiltIn(dir, true);

		assertEquals(0x000000, profile.screenBackgroundColor);
		assertEquals(255, profile.vkAlpha);
		assertEquals(0x000000, profile.vkBgColor);
		assertEquals(0xFFFFFF, profile.vkFgColor);
		assertEquals(0xFFFFFF, profile.vkBgColorSelected);
		assertEquals(0x000000, profile.vkFgColorSelected);
		assertEquals(0xFFFFFF, profile.vkOutlineColor);
	}
}
