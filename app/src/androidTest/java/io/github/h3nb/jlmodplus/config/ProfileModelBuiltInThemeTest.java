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

package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;

public class ProfileModelBuiltInThemeTest {
	@Test
	public void lightTemplateUsesOpaqueLightPalette() {
		File dir = new File(
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
				"built-in-light-profile");
		ProfileModel profile = ProfileModel.createBuiltIn(dir, false);

		assertEquals(0xFAFBFC, profile.screenBackgroundColor);
		assertEquals(BackgroundMode.THEME, profile.screenBackgroundMode);
		assertEquals(255, profile.vkAlpha);
		assertEquals(0xFFFFFF, profile.vkBgColor);
		assertEquals(0x000000, profile.vkFgColor);
		assertEquals(0x000000, profile.vkBgColorSelected);
		assertEquals(0xFFFFFF, profile.vkFgColorSelected);
		assertEquals(0x000000, profile.vkOutlineColor);
		assertEquals(VirtualKeyboard.TYPE_NUMBERS_ARROWS, profile.vkType);
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

	@Test
	public void applyingBuiltInReplacesMaterializedLayoutWithNumbersAndArrows() throws Exception {
		File dir = new File(
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
				"built-in-layout-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		ProfileModel profile = ProfileModel.createBuiltIn(dir, false);
		VirtualKeyboard keyboard = null;
		try {
			keyboard = new VirtualKeyboard(profile);
			keyboard.setLayoutForEditing(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
			ProfilesManager.publishRuntimeLayout(dir, keyboard.encodeCurrentLayoutForPersistence());
			stopKeyboard(keyboard);
			keyboard = new VirtualKeyboard(profile);
			assertEquals(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, keyboard.getLayout());
			stopKeyboard(keyboard);
			keyboard = null;

			assertTrue(ProfilesManager.publishBuiltInSnapshot(profile));
			assertFalse(new File(dir, Config.MIDLET_KEY_LAYOUT_FILE).exists());
			ProfileModel saved = ProfilesManager.loadConfig(dir);
			assertEquals(VirtualKeyboard.TYPE_NUMBERS_ARROWS, saved.vkType);
			keyboard = new VirtualKeyboard(saved);
			assertEquals(VirtualKeyboard.TYPE_NUMBERS_ARROWS, keyboard.getLayout());
		} finally {
			if (keyboard != null) stopKeyboard(keyboard);
			File[] files = dir.listFiles();
			if (files != null) for (File file : files) file.delete();
			dir.delete();
		}
	}

	private static void stopKeyboard(VirtualKeyboard keyboard) throws Exception {
		keyboard.cancel();
		Field field = VirtualKeyboard.class.getDeclaredField("handler");
		field.setAccessible(true);
		((Handler) field.get(keyboard)).getLooper().quitSafely();
	}
}
