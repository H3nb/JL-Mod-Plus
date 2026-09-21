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

import android.content.res.Configuration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import javax.microedition.shell.timing.TimingMode;

public class ProfileConfigMatcherTest {
	@Test
	public void effectiveDraftAppliesFormButPreservesUnexposedFields() {
		ProfileModel current = new ProfileModel();
		current.version = ProfileModel.VERSION;
		current.screenWidth = 240;
		current.screenHeight = 320;
		current.screenBackgroundColor = 0xD0D0D0;
		current.systemProperties = "platform: test\nprofiles: MIDP2.0\n";
		current.customKeys = Collections.emptyList();

		ConfigFormState draft = ConfigFormState.fromProfile(current, current.systemProperties)
				.toBuilder().screenWidth("360").build();
		ProfileModel candidate = new Gson().fromJson(new Gson().toJson(current), ProfileModel.class);
		candidate.screenWidth = 360;
		candidate.systemProperties = "profiles: MIDP2.0\nplatform: test\n";

		assertTrue(ProfileConfigMatcher.sameEffectiveConfig(current, draft, candidate));

		candidate.version = 2;
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(current, draft, candidate));
	}

	@Test
	public void changedThenRestoredDraftMatchesPersistedBaseline() {
		ProfileModel persisted = new ProfileModel();
		persisted.version = ProfileModel.VERSION;
		persisted.screenWidth = 240;
		persisted.screenHeight = 320;
		persisted.fpsLimit = 60;
		persisted.systemProperties = "platform: test\n";
		ProfileModel baseline = ProfileConfigMatcher.copyConfig(persisted);

		ConfigFormState changed = ConfigFormState.fromProfile(persisted, persisted.systemProperties)
				.toBuilder().fpsLimit("120").build();
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(persisted, changed, baseline));

		ConfigFormState restored = changed.toBuilder().fpsLimit("60").build();
		assertTrue(ProfileConfigMatcher.sameEffectiveConfig(persisted, restored, baseline));
	}

	@Test
	public void persistedBaselineIsDeepAndDetectsDirectNestedShaderMutation() {
		ProfileModel working = new ProfileModel();
		working.version = ProfileModel.VERSION;
		working.screenWidth = 240;
		working.screenHeight = 320;
		working.systemProperties = "platform: test\n";
		working.shader = new ShaderInfo("CRT", "tester");
		working.shader.values = new float[] {1.0f, 2.0f};
		ProfileModel baseline = ProfileConfigMatcher.copyConfig(working);
		ConfigFormState form = ConfigFormState.fromProfile(working, working.systemProperties);

		working.shader.values[0] = 9.0f;
		form.shader.values[0] = 9.0f;

		assertEquals(1.0f, baseline.shader.values[0], 0.0f);
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(working, form, baseline));
	}

	@Test
	public void candidateMatchingTreatsKeyboardAsPartOfProfilesThatOwnIt() {
		ProfileModel current = new ProfileModel();
		current.version = ProfileModel.VERSION;
		current.screenWidth = 240;
		current.screenHeight = 320;
		current.systemProperties = "platform: test\n";
		ConfigFormState draft = ConfigFormState.fromProfile(current, current.systemProperties);

		Profile withKeyboard = new Profile("with-keyboard");
		Profile configOnly = new Profile("config-only");
		ProfileConfigMatcher.Candidate keyboardCandidate = new ProfileConfigMatcher.Candidate(
				withKeyboard, current, true, "keys".getBytes(StandardCharsets.UTF_8));
		ProfileConfigMatcher.Candidate configOnlyCandidate = new ProfileConfigMatcher.Candidate(
				configOnly, current, false, null);

		assertTrue(ProfileConfigMatcher.matchesCandidate(
				current,
				draft,
				keyboardCandidate,
				"keys".getBytes(StandardCharsets.UTF_8)));
		assertFalse(ProfileConfigMatcher.matchesCandidate(
				current,
				draft,
				keyboardCandidate,
				"different".getBytes(StandardCharsets.UTF_8)));
		assertTrue(ProfileConfigMatcher.matchesCandidate(
				current,
				draft,
				configOnlyCandidate,
				"different".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	public void renderTimeLoadDoesNotPersistProfileMigration() throws Exception {
		File directory = Files.createTempDirectory("jlmod-profile").toFile();
		directory.deleteOnExit();
		ProfileModel legacy = new ProfileModel();
		legacy.version = 2;
		legacy.screenWidth = 240;
		legacy.screenHeight = 320;
		File config = new File(directory, "config.json");
		String json = new Gson().toJson(legacy);
		Files.write(config.toPath(), json.getBytes(StandardCharsets.UTF_8));

		ProfilesManager.loadConfig(directory, false);

		assertEquals(json, new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void legacyBackgroundContextIsExplicitAndMalformedModeIsLocal() throws Exception {
		File namedDirectory = Files.createTempDirectory("jlmod-named-background").toFile();
		namedDirectory.deleteOnExit();
		File midletDirectory = Files.createTempDirectory("jlmod-midlet-background").toFile();
		midletDirectory.deleteOnExit();
		Gson gson = new Gson();
		ProfileModel legacy = new ProfileModel();
		legacy.version = 6;
		legacy.screenBackgroundColor = 0x123456;
		Files.write(new File(namedDirectory, "config.json").toPath(),
				gson.toJson(legacy).getBytes(StandardCharsets.UTF_8));
		Files.write(new File(midletDirectory, "config.json").toPath(),
				gson.toJson(legacy).getBytes(StandardCharsets.UTF_8));

		ProfileModel named = ProfilesManager.loadConfig(namedDirectory, false,
				ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, true);
		ProfileModel midlet = ProfilesManager.loadConfig(midletDirectory, false,
				ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG, true);
		assertEquals(BackgroundMode.CUSTOM, named.screenBackgroundMode);
		assertEquals(BackgroundMode.THEME, midlet.screenBackgroundMode);
		assertEquals(0x123456, named.screenBackgroundColor);
		assertEquals(0x123456, midlet.screenBackgroundColor);

		JsonObject malformed = gson.toJsonTree(midlet).getAsJsonObject();
		malformed.add("ScreenBackgroundMode", new com.google.gson.JsonObject());
		Files.write(new File(namedDirectory, "config.json").toPath(),
				gson.toJson(malformed).getBytes(StandardCharsets.UTF_8));
		ProfileModel malformedLoaded = ProfilesManager.loadConfig(namedDirectory, false,
				ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false);
		assertEquals(BackgroundMode.CUSTOM, malformedLoaded.screenBackgroundMode);
		assertEquals(0x123456, malformedLoaded.screenBackgroundColor);
	}

	@Test
	public void legacyProfileDropsRuntimeSpeedFieldsButPreservesTimingMode() throws Exception {
		File directory = Files.createTempDirectory("jlmod-profile-timing").toFile();
		directory.deleteOnExit();
		ProfileModel legacy = new ProfileModel();
		legacy.dir = directory;
		legacy.version = 5;
		JsonObject json = new Gson().toJsonTree(legacy).getAsJsonObject();
		json.addProperty("EmulationSpeedPercent", 800);
		json.addProperty("ShowEmulationSpeed", true);
		json.addProperty("TimingMode", 1);
		Files.write(
				new File(directory, "config.json").toPath(),
				new Gson().toJson(json).getBytes(StandardCharsets.UTF_8));

		ProfileModel loaded = ProfilesManager.loadConfig(directory);

		assertEquals(ProfileModel.VERSION, loaded.version);
		assertEquals(TimingMode.REAL_WALL_CLOCK, loaded.timingMode);
		String migrated = new String(
				Files.readAllBytes(new File(directory, "config.json").toPath()),
				StandardCharsets.UTF_8);
		assertTrue(migrated.contains("\"Version\": 7"));
		assertFalse(migrated.contains("EmulationSpeedPercent"));
		assertFalse(migrated.contains("ShowEmulationSpeed"));
		assertTrue(migrated.contains("\"TimingMode\": 1"));
	}

	@Test
	public void builtInThemeSwitchUpdatesOnlyThemeOwnedPalette() {
		ProfileModel profile = new ProfileModel();
		profile.screenWidth = 360;
		profile.screenBackgroundColor = 0x123456;

		ProfileModel.applyBuiltInTheme(profile, true);
		assertEquals(360, profile.screenWidth);
		assertEquals(0x123456, profile.screenBackgroundColor);
		assertEquals(0xFFFFFF, profile.vkFgColor);

		ProfileModel.applyBuiltInTheme(profile, false);
		assertEquals(360, profile.screenWidth);
		assertEquals(0x123456, profile.screenBackgroundColor);
		assertEquals(0x000000, profile.vkFgColor);
	}

	@Test
	public void explicitThemePreferenceWinsOverApplicationContextUiMode() {
		assertTrue(ProfileModel.isDarkTheme("dark", Configuration.UI_MODE_NIGHT_NO));
		assertFalse(ProfileModel.isDarkTheme("light", Configuration.UI_MODE_NIGHT_YES));
	}
}
