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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.FileUtils;

@RunWith(AndroidJUnit4.class)
public class ProfileLinksTest {
	private String suffix;
	private File configDir;
	private Profile settingsProfile;
	private Profile keyboardProfile;

	@Before
	public void setUp() {
		suffix = "profile-links-test-" + System.nanoTime();
		configDir = new File(Config.getConfigsDir(), suffix);
		assertTrue(configDir.mkdirs() || configDir.isDirectory());
		settingsProfile = new Profile(suffix + "-settings");
		keyboardProfile = new Profile(suffix + "-keyboard");
		settingsProfile.create();
		keyboardProfile.create();
	}

	@After
	public void tearDown() {
		preferences().edit().remove(legacyOriginKey()).apply();
		ProfileLinks.detachSettings(configDir);
		ProfileLinks.detachKeyboard(configDir);
		ProfileLinks.detachBuiltInSettings(configDir);
		settingsProfile.delete();
		keyboardProfile.delete();
		FileUtils.deleteDirectory(configDir);
	}

	@Test
	public void linkedComponentsRefreshIndependentlyFromDifferentProfiles() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{1, 2, 3});

		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);
		ProfilesManager.load(keyboardProfile, configDir.getPath(), false, true);
		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(keyboardProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));

		writeConfig(settingsProfile, 320);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{4, 5, 6});
		ProfileLinks.resolve(configDir);

		assertEquals(320, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{4, 5, 6}, readBytes(localKeyboard()));
		assertFalse(ProfileLinks.isSettingsModified(configDir));
		assertFalse(ProfileLinks.isKeyboardModified(configDir));
	}

	@Test
	public void localChangesStayLinkedAndBecomeModified() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{1});
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);
		ProfilesManager.load(keyboardProfile, configDir.getPath(), false, true);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 176;
		assertTrue(ProfilesManager.saveConfig(local));
		writeBytes(localKeyboard(), new byte[]{9});

		writeConfig(settingsProfile, 320);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{2});
		ProfileLinks.resolve(configDir);

		assertEquals(176, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{9}, readBytes(localKeyboard()));
		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(keyboardProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertTrue(ProfileLinks.isSettingsModified(configDir));
		assertTrue(ProfileLinks.isKeyboardModified(configDir));
	}

	@Test
	public void legacyOriginMigratesModifiedComponentsWithoutOverwritingThem() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{1});
		writeConfig(configDir, 176);
		writeBytes(localKeyboard(), new byte[]{9});
		preferences().edit().putString(legacyOriginKey(), settingsProfile.getName()).apply();

		ProfileLinks.resolve(configDir);

		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertTrue(ProfileLinks.isSettingsModified(configDir));
		assertTrue(ProfileLinks.isKeyboardModified(configDir));
		assertEquals(176, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{9}, readBytes(localKeyboard()));
		assertNull(preferences().getString(legacyOriginKey(), null));
	}

	@Test
	public void updatingLinkedProfileKeepsTheLinkAndRefreshesItsBaseline() throws Exception {
		writeConfig(settingsProfile, 240);
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 360;
		assertTrue(ProfilesManager.saveConfig(local));
		assertTrue(ProfileLinks.isSettingsModified(configDir));
		ProfilesManager.save(settingsProfile, configDir.getPath(), true, false);

		ProfileLinks.resolve(configDir);
		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(360, readConfig(configDir).screenWidth);
		assertEquals(360, readConfig(settingsProfile.getDir()).screenWidth);
		assertFalse(ProfileLinks.isSettingsModified(configDir));
	}

	@Test
	public void updatingSettingsDoesNotAdoptUnsavedKeyboardChanges() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{1});
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, true);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 360;
		assertTrue(ProfilesManager.saveConfig(local));
		writeBytes(localKeyboard(), new byte[]{9});

		ProfilesManager.save(settingsProfile, configDir.getPath(), true, false);
		ProfileLinks.resolve(configDir);

		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertEquals(360, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{9}, readBytes(localKeyboard()));
		assertArrayEquals(new byte[]{1}, readBytes(settingsProfile.getKeyLayout()));
		assertFalse(ProfileLinks.isSettingsModified(configDir));
		assertTrue(ProfileLinks.isKeyboardModified(configDir));
	}

	@Test
	public void snapshotUpdateTouchesOnlyComponentsLinkedToThatProfile() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{1});
		writeConfig(keyboardProfile, 176);

		ProfilesManager.load(keyboardProfile, configDir.getPath(), true, false);
		ProfilesManager.load(settingsProfile, configDir.getPath(), false, true);
		writeBytes(localKeyboard(), new byte[]{9});

		ProfilesManager.saveSnapshot(settingsProfile, configDir.getPath());
		ProfileLinks.resolve(configDir);

		assertEquals(240, readConfig(settingsProfile.getDir()).screenWidth);
		assertArrayEquals(new byte[]{9}, readBytes(settingsProfile.getKeyLayout()));
		assertEquals(176, readConfig(configDir).screenWidth);
		assertEquals(keyboardProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertFalse(ProfileLinks.isKeyboardModified(configDir));
	}

	@Test
	public void snapshotUpdateDoesNotRewriteUnchangedSiblingComponent() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{1});
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, true);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 360;
		assertTrue(ProfilesManager.saveConfig(local));
		// Simulate the shared keyboard component having already changed elsewhere. This game's
		// keyboard is still unmodified relative to its own baseline and must not overwrite it.
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{7});

		ProfilesManager.saveSnapshot(settingsProfile, configDir.getPath());
		assertArrayEquals(new byte[]{7}, readBytes(settingsProfile.getKeyLayout()));
		assertEquals(360, readConfig(settingsProfile.getDir()).screenWidth);

		ProfileLinks.resolve(configDir);
		assertArrayEquals(new byte[]{7}, readBytes(localKeyboard()));
		assertFalse(ProfileLinks.isSettingsModified(configDir));
		assertFalse(ProfileLinks.isKeyboardModified(configDir));
	}

	@Test
	public void corruptSettingsProfileCannotOverwriteGameSettings() throws Exception {
		writeConfig(configDir, 176);
		writeBytes(settingsProfile.getConfig(), "{broken".getBytes(StandardCharsets.UTF_8));
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{7, 8});

		ProfilesManager.load(settingsProfile, configDir.getPath(), true, true);

		assertEquals(176, readConfig(configDir).screenWidth);
		assertNull(ProfileLinks.getSettingsProfile(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertArrayEquals(new byte[]{7, 8}, readBytes(localKeyboard()));
	}

	@Test
	public void staleModifiedSettingsCannotOverwriteNewerSharedProfile() throws Exception {
		writeConfig(settingsProfile, 240);
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 360;
		assertTrue(ProfilesManager.saveConfig(local));
		writeConfig(settingsProfile, 320);

		try {
			ProfilesManager.saveSnapshot(settingsProfile, configDir.getPath());
			fail("Expected stale profile conflict");
		} catch (ProfilesManager.ProfileUpdateConflictException expected) {
			// Expected: the newer shared profile must win until the user explicitly resolves it.
		}

		assertEquals(320, readConfig(settingsProfile.getDir()).screenWidth);
		assertEquals(360, readConfig(configDir).screenWidth);
		assertTrue(ProfileLinks.isSettingsModified(configDir));
	}

	@Test
	public void matchingExternalUpdateCanSafelyRebaselineModifiedSettings() throws Exception {
		writeConfig(settingsProfile, 240);
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);

		ProfileModel local = readConfig(configDir);
		local.screenWidth = 360;
		assertTrue(ProfilesManager.saveConfig(local));
		writeConfig(settingsProfile, 360);

		ProfilesManager.saveSnapshot(settingsProfile, configDir.getPath());

		assertEquals(360, readConfig(settingsProfile.getDir()).screenWidth);
		assertFalse(ProfileLinks.isSettingsModified(configDir));
	}

	@Test
	public void explicitSettingsDetachIsPermanentAndKeepsSiblingLink() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{1, 2});
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, true);
		preferences().edit().putString(legacyOriginKey(), settingsProfile.getName()).apply();

		ProfileLinks.detachSettings(configDir);
		writeConfig(settingsProfile, 320);
		writeBytes(settingsProfile.getKeyLayout(), new byte[]{7, 8});
		ProfileLinks.resolve(configDir);

		assertNull(ProfileLinks.getSettingsProfile(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getKeyboardProfile(configDir));
		assertEquals(240, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{7, 8}, readBytes(localKeyboard()));
		assertNull(preferences().getString(legacyOriginKey(), null));
	}

	@Test
	public void explicitDetachKeepsTheLastMaterializedCopy() throws Exception {
		writeConfig(settingsProfile, 240);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{1, 2});
		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);
		ProfilesManager.load(keyboardProfile, configDir.getPath(), false, true);

		ProfileLinks.detachSettings(configDir);
		ProfileLinks.detachKeyboard(configDir);
		writeConfig(settingsProfile, 320);
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{7, 8});
		ProfileLinks.resolve(configDir);

		assertNull(ProfileLinks.getSettingsProfile(configDir));
		assertNull(ProfileLinks.getKeyboardProfile(configDir));
		assertEquals(240, readConfig(configDir).screenWidth);
		assertArrayEquals(new byte[]{1, 2}, readBytes(localKeyboard()));
	}

	@Test
	public void namedSettingsReplaceBuiltInSource() throws Exception {
		ProfileLinks.linkBuiltInSettings(configDir);
		writeConfig(settingsProfile, 240);

		ProfilesManager.load(settingsProfile, configDir.getPath(), true, false);

		assertFalse(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertEquals(settingsProfile.getName(), ProfileLinks.getSettingsProfile(configDir));
	}

	@Test
	public void builtInModifiedStateKeepsItsSourceProvenance() {
		ProfileLinks.linkBuiltInSettings(configDir);
		ProfileLinks.setBuiltInSettingsModified(configDir, true);

		assertTrue(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertTrue(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	@Test
	public void directGameLoadRefreshesUnmodifiedBuiltInThemeSource() {
		boolean dark = ProfileModel.isDarkTheme(ContextHolder.getAppContext());
		ProfileModel stale = ProfileModel.createBuiltIn(configDir, !dark);
		assertTrue(ProfilesManager.saveConfig(stale));
		ProfileLinks.linkBuiltInSettings(configDir);

		ProfileModel resolved = ProfilesManager.loadGameConfig(configDir);

		assertNotNull(resolved);
		assertEquals(dark ? 0x000000 : 0xFFFFFF, resolved.screenBackgroundColor);
		assertTrue(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertFalse(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	@Test
	public void modifiedBuiltInSettingsAreNotOverwrittenBySourceRefresh() {
		ProfileModel local = ProfileModel.createBuiltIn(configDir,
				ProfileModel.isDarkTheme(ContextHolder.getAppContext()));
		local.screenWidth = 176;
		assertTrue(ProfilesManager.saveConfig(local));
		ProfileLinks.linkBuiltInSettings(configDir);
		ProfileLinks.setBuiltInSettingsModified(configDir, true);

		ProfileModel resolved = ProfilesManager.loadGameConfig(configDir);

		assertNotNull(resolved);
		assertEquals(176, resolved.screenWidth);
		assertTrue(ProfileLinks.isBuiltInSettingsLinked(configDir));
		assertTrue(ProfileLinks.isBuiltInSettingsModified(configDir));
	}

	@Test
	public void keyboardOnlyProfileRemainsAReusableCandidate() throws Exception {
		writeBytes(keyboardProfile.getKeyLayout(), new byte[]{7, 8});

		List<ProfileConfigMatcher.Candidate> candidates = ProfileConfigMatcher.loadCandidates(
				Collections.singletonList(keyboardProfile));

		assertEquals(1, candidates.size());
		ProfileConfigMatcher.Candidate candidate = candidates.get(0);
		assertEquals(keyboardProfile, candidate.profile);
		assertNull(candidate.config);
		assertNotNull(candidate.keyboard);
		assertArrayEquals(new byte[]{7, 8}, candidate.keyboard);
	}

	private void writeConfig(Profile profile, int width) {
		writeConfig(profile.getDir(), width);
	}

	private void writeConfig(File dir, int width) {
		ProfileModel model = new ProfileModel(dir);
		model.screenWidth = width;
		assertTrue(ProfilesManager.saveConfig(model));
	}

	private ProfileModel readConfig(File dir) {
		ProfileModel model = ProfilesManager.loadConfig(dir, false);
		if (model == null) throw new AssertionError("Missing config in " + dir);
		return model;
	}

	private File localKeyboard() {
		return new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
	}

	private String legacyOriginKey() {
		return "config_profile_origin:" + configDir.getAbsolutePath();
	}

	private static SharedPreferences preferences() {
		return PreferenceManager.getDefaultSharedPreferences(ContextHolder.getAppContext());
	}

	private static void writeBytes(File file, byte[] data) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) assertTrue(parent.mkdirs() || parent.isDirectory());
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(data);
		}
	}

	private static byte[] readBytes(File file) throws IOException {
		byte[] result = new byte[(int) file.length()];
		try (FileInputStream input = new FileInputStream(file)) {
			int offset = 0;
			while (offset < result.length) {
				int read = input.read(result, offset, result.length - offset);
				if (read < 0) break;
				offset += read;
			}
			if (offset != result.length) throw new IOException("Unexpected EOF");
		}
		return result;
	}
}
