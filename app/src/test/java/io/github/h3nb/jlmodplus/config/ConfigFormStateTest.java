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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.microedition.shell.timing.TimingMode;

public class ConfigFormStateTest {
	@Test
	public void cameraPreferencesSurviveUnrelatedDraftEditsAndApply() {
		ProfileModel profile = new ProfileModel();
		profile.cameraDefaultFacing = 0;
		profile.cameraResolution = 2;
		ConfigFormState state = ConfigFormState.fromProfile(profile, "")
				.toBuilder().screenWidth("320").build();
		ProfileModel saved = state.applyTo(new ProfileModel());
		assertEquals(0, saved.cameraDefaultFacing);
		assertEquals(2, saved.cameraResolution);
		assertEquals(320, saved.screenWidth);
	}

	@Test
	public void cameraDraftDefaultsAreRearAndAutomatic() {
		ConfigFormState state = ConfigFormState.builder().build();
		assertEquals(1, state.cameraDefaultFacing);
		assertEquals(0, state.cameraResolution);
	}

	@Test
	public void invalidCameraDraftValuesFallBackToRearAndAutomatic() {
		ConfigFormState state = ConfigFormState.builder()
				.cameraDefaultFacing(27).cameraResolution(-4).build();
		ProfileModel saved = state.applyTo(new ProfileModel());
		assertEquals(1, saved.cameraDefaultFacing);
		assertEquals(0, saved.cameraResolution);
	}

	@Test
	public void applyToKeepsLegacyParsingAndFallbackRules() {
		ProfileModel model = new ProfileModel();
		model.screenBackgroundColor = 0x112233;
		model.screenBackgroundMode = BackgroundMode.IMMERSIVE;
		model.vkBgColor = 0x445566;
		model.vkFgColor = 0x778899;
		model.vkBgColorSelected = 0xAABBCC;
		model.vkFgColorSelected = 0xDDEEFF;
		model.vkOutlineColor = 0x010203;
		model.shader = ShaderSelection.fromInfo(new ShaderInfo("existing", "owner"));

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
				.screenBackgroundMode(BackgroundMode.IMMERSIVE)
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
		assertEquals(BackgroundMode.IMMERSIVE, model.screenBackgroundMode);
		assertEquals(100, model.screenScaleRatio);
		assertEquals(0, model.screenPadding);
		assertEquals(0, model.fpsLimit);
		assertEquals(0, model.fontSizeSmall);
		assertEquals(22, model.fontSizeMedium);
		assertEquals(26, model.fontSizeLarge);
		assertEquals(0, model.vkHideDelay);
		assertEquals(TimingMode.FULL_GUEST_TIME, model.timingMode);
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
		model.screenBackgroundMode = BackgroundMode.THEME;
		model.screenScaleRatio = 125;
		model.fpsLimit = 60;
		model.timingMode = TimingMode.REAL_WALL_CLOCK;
		model.vkHideDelay = 250;
		model.vkBgColor = 0x010203;
		model.showKeyboard = true;
		model.vkAlpha = 64;

		ConfigFormState state = ConfigFormState.fromProfile(model, "microedition.locale: en\n");

		assertEquals("240", state.screenWidth);
		assertEquals("320", state.screenHeight);
		assertEquals("00AB0C", state.screenBackground);
		assertEquals(BackgroundMode.THEME, state.screenBackgroundMode);
		assertEquals("125", state.screenScaleRatio);
		assertEquals("60", state.fpsLimit);
		assertEquals(TimingMode.REAL_WALL_CLOCK, state.timingMode);
		assertEquals("250", state.vkHideDelay);
		assertEquals("010203", state.vkBackground);
		assertEquals("64", Integer.toString(state.vkAlpha));
		assertTrue(state.showKeyboard);
		assertEquals("microedition.locale: en\n", state.systemProperties);
	}

	@Test
	public void automaticScalingDropsLegacyRatiosAndResetsManualRatio() {
		ProfileModel model = new ProfileModel();
		model.screenScaleType = 1;
		model.screenScaleRatio = 80;
		ConfigFormState fit = ConfigFormState.fromProfile(model, "");
		assertEquals("100", fit.screenScaleRatio);
		fit.applyTo(model);
		assertEquals(100, model.screenScaleRatio);

		model.screenScaleType = 2;
		model.screenScaleRatio = 200;
		ConfigFormState fill = ConfigFormState.fromProfile(model, "");
		assertEquals("100", fill.screenScaleRatio);
		fill.applyTo(model);
		assertEquals(100, model.screenScaleRatio);

		ConfigFormState manual = fill.toBuilder().screenScaleType(0).screenScaleRatio("400").build();
		assertEquals("400", manual.screenScaleRatio);
		ConfigFormState fitted = manual.toBuilder().screenScaleType(1).build();
		assertEquals("100", fitted.screenScaleRatio);
		assertEquals("100", fitted.toBuilder().screenScaleType(0).build().screenScaleRatio);
	}

	@Test
	public void manualScaleRejectsInvalidValuesWithoutAffectingAutomaticModes() {
		assertEquals(100, ProfileModel.effectiveScreenScaleRatio(0, 0));
		assertEquals(100, ProfileModel.effectiveScreenScaleRatio(0, -50));
		assertEquals(1000, ProfileModel.effectiveScreenScaleRatio(0, 2000));
		assertEquals(1, ProfileModel.effectiveScreenScaleRatio(0, 1));
		assertEquals(100, ProfileModel.effectiveScreenScaleRatio(1, 50));
		assertEquals(100, ProfileModel.effectiveScreenScaleRatio(2, 400));

		ConfigFormState state = ConfigFormState.builder()
				.screenScaleType(0).screenScaleRatio("0").build();
		assertEquals("100", state.screenScaleRatio);
		state = state.toBuilder().screenScaleRatio("5000").build();
		assertEquals("1000", state.screenScaleRatio);
	}

	@Test
	public void systemPropertiesKeepLastValueAndSortKeys() {
		assertEquals(
				"a: second\nb: value\n",
				ConfigFormState.normalizeSystemProperties("b: value\na: first\na: second"));
		assertEquals("", ConfigFormState.normalizeSystemProperties(null));
		assertFalse(ConfigFormState.normalizeSystemProperties("malformed").contains("malformed"));
	}

	@Test
	public void presentationDraftCanChangeOneFieldWithoutResettingOthers() {
		ConfigFormState original = ConfigFormState.builder()
				.screenWidth("240")
				.screenHeight("320")
				.screenBackground("D0D0D0")
				.graphicsMode(1)
				.showKeyboard(true)
				.vkAlpha(64)
				.systemProperties("microedition.platform: test\n")
				.build();

		ConfigFormState changed = original.toBuilder().screenWidth("360").build();

		assertEquals("360", changed.screenWidth);
		assertEquals("320", changed.screenHeight);
		assertEquals("D0D0D0", changed.screenBackground);
		assertEquals(1, changed.graphicsMode);
		assertTrue(changed.showKeyboard);
		assertEquals(64, changed.vkAlpha);
		assertEquals("microedition.platform: test\n", changed.systemProperties);
	}
}
