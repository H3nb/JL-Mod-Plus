/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class PresetEditorIdentityInstrumentationTest {
	@Test
	public void workdirSwitchCannotRetargetOpenedEditor() throws Exception {
		Context context = ApplicationProvider.getApplicationContext();
		File fixture = new File(context.getCacheDir(), "preset-workdir-" + UUID.randomUUID());
		File workdirA = new File(fixture, "A");
		File workdirB = new File(fixture, "B");
		File sourceA = new File(workdirA, "templates/K800i");
		File sourceB = new File(workdirB, "templates/K800i");
		File draft = new File(fixture, "draft");
		if (!sourceA.mkdirs() || !sourceB.mkdirs() || !draft.mkdir()) {
			throw new IOException("Unable to prepare workdir test");
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		String initialWorkdir = Config.getEmulatorDir();
		String initialPreference = preferences.getString(PREF_EMULATOR_DIR, null);
		try {
			writeConfig(sourceA, 176);
			writeConfig(sourceB, 240);
			ProfilesManager.PresetEditSession session =
					ProfilesManager.beginPresetEditSession(sourceA, draft);
			writeConfig(draft, 640);
			byte[] originalB = Files.readAllBytes(new File(sourceB, "config.json").toPath());
			if (!preferences.edit().putString(PREF_EMULATOR_DIR, workdirB.getPath()).commit()) {
				throw new IOException("Unable to switch workdir");
			}
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(new File(workdirB, "templates").getCanonicalFile(),
					new File(Config.getProfilesDir()).getCanonicalFile());

			ProfilesManager.saveEditedSnapshot(session, draft);

			ProfileModel savedA = new Gson().fromJson(new String(
					Files.readAllBytes(new File(sourceA, "config.json").toPath()),
					StandardCharsets.UTF_8), ProfileModel.class);
			assertEquals(640, savedA.screenWidth);
			assertArrayEquals(originalB, Files.readAllBytes(new File(sourceB, "config.json").toPath()));
		} finally {
			preferences.edit().putString(PREF_EMULATOR_DIR, initialWorkdir).commit();
			if (initialPreference == null) preferences.edit().remove(PREF_EMULATOR_DIR).commit();
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			deleteRecursively(fixture);
		}
	}

	@Test
	public void staleEditorCannotOverwriteRecreatedNameAfterRenameOrDelete() throws Exception {
		verifyRecreatedName(false);
		verifyRecreatedName(true);
	}

	private static void writeConfig(File dir, int width) throws Exception {
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = width;
		model.screenHeight = 320;
		model.vkType = 1;
		model.systemProperties = "";
		Files.write(new File(dir, "config.json").toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static void verifyRecreatedName(boolean delete) throws Exception {
		Context context = ApplicationProvider.getApplicationContext();
		File workdir = new File(context.getCacheDir(), "preset-identity-" + UUID.randomUUID());
		File root = new File(workdir, "templates");
		File source = new File(root, "K800i");
		File draft = new File(workdir, "draft");
		if (!source.mkdirs() || !draft.mkdir()) throw new IOException("Unable to prepare preset test");
		String preferencesName = "preset-identity-" + UUID.randomUUID();
		SharedPreferences preferences = context.getSharedPreferences(
				preferencesName, Context.MODE_PRIVATE);
		try {
			Files.write(new File(source, "config.json").toPath(), "old".getBytes(StandardCharsets.UTF_8));
			ProfilesManager.PresetEditSession session =
					ProfilesManager.beginPresetEditSession(source, draft);
			assertEquals(ProfilesManager.ProfileEditMode.EDIT_EXISTING, session.mode);
			PresetLifecycle.Result result = delete
					? PresetLifecycle.delete(preferences, root, "K800i")
					: PresetLifecycle.rename(preferences, root, "K800i", "Sony");
			assertEquals(PresetLifecycle.Result.SUCCESS, result);
			if (!source.mkdir()) throw new IOException("Unable to recreate preset name");
			File replacement = new File(source, "config.json");
			byte[] original = "new preset".getBytes(StandardCharsets.UTF_8);
			Files.write(replacement.toPath(), original);

			try {
				ProfilesManager.saveEditedSnapshot(session, draft);
				fail("Stale editor session accepted a recreated name");
			} catch (IOException expected) {
				assertArrayEquals(original, Files.readAllBytes(replacement.toPath()));
			}
		} finally {
			deleteRecursively(workdir);
			context.deleteSharedPreferences(preferencesName);
		}
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) for (File child : children) deleteRecursively(child);
		file.delete();
	}
}
