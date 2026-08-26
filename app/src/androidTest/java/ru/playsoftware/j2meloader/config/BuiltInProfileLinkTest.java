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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.FileUtils;

@RunWith(AndroidJUnit4.class)
public class BuiltInProfileLinkTest {
	private File configDir;

	@Before
	public void setUp() {
		configDir = new File(Config.getConfigsDir(), "built-in-link-test-" + System.nanoTime());
		assertTrue(configDir.mkdirs() || configDir.isDirectory());
	}

	@After
	public void tearDown() {
		ProfileLinks.detachBuiltInSettings(configDir);
		ProfileLinks.detachSettings(configDir);
		FileUtils.deleteDirectory(configDir);
	}

	@Test
	public void pendingBuiltInLinkDoesNotAdoptFirstModifiedMaterialization() {
		ProfileLinks.linkBuiltInSettings(configDir);

		ProfileModel local = currentBuiltIn();
		local.screenWidth = 176;
		assertTrue(ProfilesManager.saveConfig(local));

		ProfileModel resolved = ProfilesManager.loadGameConfig(configDir);

		assertNotNull(resolved);
		assertEquals(176, resolved.screenWidth);
		assertTrue(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertTrue(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	@Test
	public void unmodifiedFirstSaveCanEstablishBuiltInBaseline() {
		ProfileLinks.linkBuiltInSettings(configDir);
		assertTrue(ProfilesManager.saveConfig(currentBuiltIn()));
		ProfileLinks.refreshBuiltInBaseline(configDir);

		ProfileModel resolved = ProfilesManager.loadGameConfig(configDir);

		assertNotNull(resolved);
		assertFalse(resolved.isNew);
		assertFalse(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	@Test
	public void legacyBuiltInLinkWithoutBaselineTrustsExplicitProvenance() {
		// Simulate an older app-provided Built-In template whose defaults differed from the current
		// release. The historical Built-In link itself is sufficient provenance because older code
		// removed that link as soon as a game became app-specific.
		ProfileModel legacyMaterialization = currentBuiltIn();
		legacyMaterialization.screenWidth = 176;
		assertTrue(ProfilesManager.saveConfig(legacyMaterialization));
		preferences().edit()
				.putBoolean(ProfileModel.builtInThemePreferenceKey(configDir), true)
				.remove(builtInHashKey())
				.apply();

		ProfileModel resolved = ProfilesManager.loadGameConfig(configDir);

		assertNotNull(resolved);
		assertEquals(240, resolved.screenWidth);
		assertFalse(resolved.isNew);
		assertTrue(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertFalse(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	private ProfileModel currentBuiltIn() {
		return ProfileModel.createBuiltIn(
				configDir, ProfileModel.isDarkTheme(ContextHolder.getAppContext()));
	}

	private String builtInHashKey() {
		return "config_profile_builtin_hash:" + configDir.getAbsolutePath();
	}

	private static SharedPreferences preferences() {
		return PreferenceManager.getDefaultSharedPreferences(ContextHolder.getAppContext());
	}
}
