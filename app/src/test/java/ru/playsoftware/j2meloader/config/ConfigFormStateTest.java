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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConfigFormStateTest {
	@Test
	public void applyToKeepsLegacyParsingAndFallbackRules() {
		ProfileModel model = new ProfileModel();
		model.screenBackgroundColor = 0x112233;
		model.vkBgColor = 0x445566;
		model.vkFgColor = 0x778899;
		model.vkBgColorSelected = 0xAABBCC;
		model.vkFgColorSelected = 0xDDEEFF;
		model.vkOutlineColor = 0x010203;
		model.shader = new ShaderInfo("existing", "owner");

		ConfigFormState state = ConfigFormState.builder()
				.screenWidth("not-a-number")
				.screenHeight("320")
				.screenBackground("not-a-color")
				.screenScaleRatio("invalid")
				.screenPadding("invalid")
				.fpsLimit("invalid")
				.fontSizeSmall("invalid")
				.fontSizeMedium("22")
				.fontSizeLarge("26")
				.vkHideDelay("invalid")
				.vkBackground("invalid")
				.vkForeground("778899")
				.vkSelectedBackground("invalid")
				.vkSelectedForeground("DDEEFF")
				.vkOutline("invalid")
				.graphicsMode(1)
				.screenFilter(true)
				.showKeyboard(true)
				.vkFeedback(true)
				.systemProperties("z: old\na: one\nz: new\nmalformed")
				.build();

		state.applyTo(model);

		assertEquals(0, model.screenWidth);
		assertEquals(320, model.screenHeight);
		assertEquals(0x112233, model.screenBackgroundColor);
		assertEquals(100, model.screenScaleRatio);
		assertEquals(0, model.screenPadding);
		assertEquals(0, model.fpsLimit);
		assertEquals(0, model.fontSizeSmall);
		assertEquals(22, model.fontSizeMedium);
		assertEquals(26, model.fontSizeLarge);
		assertEquals(0, model.vkHideDelay);
		assertEquals(0x445566, model.vkBgColor);
		assertEquals(0x778899, model.vkFgColor);
		assertEquals(0xAABBCC, model.vkBgColorSelected);
		assertEquals(0xDDEEFF, model.vkFgColorSelected);
		assertEquals(0x010203, model.vkOutlineColor);
		assertTrue(model.screenFilter);
		assertTrue(model.showKeyboard);
		assertTrue(model.vkFeedback);
		assertNull(model.shader);
		assertEquals("a: one\nz: new\n", model.systemProperties);
	}

	@Test
	public void profileMappingUsesStableDisplayValuesAndDefaults() {
		ProfileModel model = new ProfileModel();
		model.screenWidth = 240;
		model.screenHeight = 320;
		model.screenBackgroundColor = 0x00AB0C;
		model.screenScaleRatio = 125;
		model.fpsLimit = 60;
		model.vkHideDelay = 250;
		model.vkBgColor = 0x010203;
		model.showKeyboard = true;
		model.vkAlpha = 64;

		ConfigFormState state = ConfigFormState.fromProfile(model, "microedition.locale: en\n");

		assertEquals("240", state.screenWidth);
		assertEquals("320", state.screenHeight);
		assertEquals("00AB0C", state.screenBackground);
		assertEquals("125", state.screenScaleRatio);
		assertEquals("60", state.fpsLimit);
		assertEquals("250", state.vkHideDelay);
		assertEquals("010203", state.vkBackground);
		assertEquals("64", Integer.toString(state.vkAlpha));
		assertTrue(state.showKeyboard);
		assertEquals("microedition.locale: en\n", state.systemProperties);
	}

	@Test
	public void systemPropertiesKeepLastValueAndSortKeys() {
		assertEquals(
				"a: second\nb: value\n",
				ConfigFormState.normalizeSystemProperties("b: value\na: first\na: second"));
		assertEquals("", ConfigFormState.normalizeSystemProperties(null));
		assertFalse(ConfigFormState.normalizeSystemProperties("malformed").contains("malformed"));
	}
}
