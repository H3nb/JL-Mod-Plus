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
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class ProfileConfigMatcherTest {
	@Test
	public void effectiveDraftAppliesFormButPreservesUnexposedFields() {
		ProfileModel current = new ProfileModel();
		current.version = ProfileModel.VERSION;
		current.screenWidth = 240;
		current.screenHeight = 320;
		current.screenBackgroundColor = 0xD0D0D0;
		current.systemProperties = "platform: test\nprofiles: MIDP2.0\n";
		current.customKeys = java.util.Collections.emptyList();

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
	public void duplicateMatchesPreferDefaultThenStableNameOrder() {
		Profile defaultProfile = new Profile("zeta");
		Profile caseInsensitiveFirst = new Profile("Alpha");
		Profile caseInsensitiveTie = new Profile("alpha");

		assertEquals("zeta", ProfileConfigMatcher.selectMatch(
				Arrays.asList(caseInsensitiveTie, defaultProfile, caseInsensitiveFirst), "zeta").getName());
		assertEquals("Alpha", ProfileConfigMatcher.selectMatch(
				Arrays.asList(caseInsensitiveTie, defaultProfile, caseInsensitiveFirst), null).getName());
	}

	@Test
	public void keyboardArtifactComparisonIsExactAndOptionalAtCaller() throws Exception {
		File first = File.createTempFile("jlmod-keyboard-a", ".bin");
		File second = File.createTempFile("jlmod-keyboard-b", ".bin");
		first.deleteOnExit();
		second.deleteOnExit();
		Files.write(first.toPath(), "keyboard\n".getBytes(StandardCharsets.UTF_8));
		Files.write(second.toPath(), "keyboard\n".getBytes(StandardCharsets.UTF_8));

		assertTrue(ProfileConfigMatcher.sameKeyboardFile(first, second));
		Files.write(second.toPath(), "different\n".getBytes(StandardCharsets.UTF_8));
		assertFalse(ProfileConfigMatcher.sameKeyboardFile(first, second));
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
}
