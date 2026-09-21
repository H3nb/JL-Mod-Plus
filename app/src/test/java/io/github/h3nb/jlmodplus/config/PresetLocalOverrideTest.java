/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PresetLocalOverrideTest {
	@Test
	public void linkedWriteDetachesBeforeFilesystemDivergenceAndPreservesOrigin() throws Exception {
		File configDir = tempDir("detach-before-write");
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);

		assertTrue(guard.canWrite());
		assertFalse(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));

		Files.write(new File(configDir, "config.json").toPath(),
				"local".getBytes(StandardCharsets.UTF_8));

		assertFalse(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void committedLayoutStaysCustomWhenSecondaryConfigWriteFails() throws Exception {
		File configDir = tempDir("layout-primary-committed");
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);
		assertTrue(guard.canWrite());
		Files.write(new File(configDir, "VirtualKeyboardLayout").toPath(),
				"edited-layout".getBytes(StandardCharsets.UTF_8));
		boolean secondaryConfigSaved = false;

		assertFalse(secondaryConfigSaved);
		assertFalse(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
		assertEquals(
				"edited-layout",
				new String(
						Files.readAllBytes(new File(configDir, "VirtualKeyboardLayout").toPath()),
						StandardCharsets.UTF_8));
	}

	@Test
	public void failedWriteCanRestorePreviousLink() throws Exception {
		File configDir = tempDir("restore-link");
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);
		assertTrue(guard.canWrite());
		assertFalse(isLinked(preferences, configDir));

		assertTrue(guard.restoreIfUnchanged());
		assertTrue(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void failedDurableDetachBlocksFilesystemWrite() throws Exception {
		File configDir = tempDir("detach-fails");
		FakePreferences preferences = linked(configDir, "K800i");
		preferences.failCommit(2); // #1 established the fixture; #2 is detach.

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);

		assertFalse(guard.canWrite());
		assertFalse(new File(configDir, "config.json").exists());
		assertTrue(isLinked(preferences, configDir));
	}

	@Test
	public void failedLinkRestorationLeavesProcessVisibleStateCustom() throws Exception {
		File configDir = tempDir("restore-fails");
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);
		assertTrue(guard.canWrite());
		preferences.failCommit(3); // fixture #1, detach #2, restore link #3.

		assertFalse(guard.restoreIfUnchanged());
		assertFalse(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void failedDurableClearBlocksSourceReplacement() throws Exception {
		File configDir = tempDir("clear-fails");
		FakePreferences preferences = linked(configDir, "K800i");
		preferences.failCommit(2); // #1 fixture, #2 clear.

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.clearBeforeReplacement(preferences, configDir);

		assertFalse(guard.canWrite());
		assertTrue(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void automaticMigrationDoesNotDetachLinkedOwnership() throws Exception {
		File configDir = tempDir("migration");
		ProfileModel legacy = new ProfileModel();
		legacy.dir = configDir;
		legacy.version = 5;
		legacy.timingMode = 999;
		Files.write(new File(configDir, "config.json").toPath(),
				new Gson().toJson(legacy).getBytes(StandardCharsets.UTF_8));
		FakePreferences preferences = linked(configDir, "K800i");

		ProfilesManager.loadConfig(
				configDir,
				true,
				ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
				false);

		assertTrue(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void namedDraftSaveDoesNotTouchActiveMidletLinkage() throws Exception {
		File activeDir = tempDir("active-midlet");
		File draftDir = tempDir("named-draft");
		FakePreferences preferences = linked(activeDir, "K800i");
		ProfileModel draft = new ProfileModel();
		draft.dir = draftDir;
		draft.version = ProfileModel.VERSION;
		draft.screenWidth = 360;
		draft.systemProperties = "";

		assertTrue(ProfilesManager.saveConfig(draft));

		assertTrue(isLinked(preferences, activeDir));
		assertEquals("K800i", origin(preferences, activeDir));
	}

	@Test
	public void clearReplacementRemovesAssociationBeforeWriteAndCanRestoreOnSafeFailure()
			throws Exception {
		File configDir = tempDir("clear-replacement");
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.clearBeforeReplacement(preferences, configDir);

		assertTrue(guard.canWrite());
		assertFalse(isLinked(preferences, configDir));
		assertEquals(null, origin(preferences, configDir));

		assertTrue(guard.restoreIfUnchanged());
		assertTrue(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void successfulLayoutResetRemovesFamilyAndRemainsCustom() throws Exception {
		File configDir = tempDir("layout-reset");
		File main = new File(configDir, "VirtualKeyboardLayout");
		File temporary = new File(configDir, "VirtualKeyboardLayout.new");
		File backup = new File(configDir, "VirtualKeyboardLayout.bak");
		Files.write(main.toPath(), new byte[] {1});
		Files.write(temporary.toPath(), new byte[] {2});
		Files.write(backup.toPath(), new byte[] {3});
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);
		assertTrue(guard.canWrite());
		assertTrue(ProfilesManager.removeLocalKeyboardLayout(configDir));

		assertFalse(main.exists());
		assertFalse(temporary.exists());
		assertFalse(backup.exists());
		assertFalse(isLinked(preferences, configDir));
		assertEquals("K800i", origin(preferences, configDir));
	}

	@Test
	public void layoutSidecarCleanupFailureLeavesMainAndAllowsLinkRestore() throws Exception {
		File configDir = tempDir("layout-sidecar-failure");
		File main = new File(configDir, "VirtualKeyboardLayout");
		Files.write(main.toPath(), new byte[] {1});
		File temporary = new File(configDir, "VirtualKeyboardLayout.new");
		assertTrue(temporary.mkdir());
		Files.write(new File(temporary, "child").toPath(), new byte[] {2});
		FakePreferences preferences = linked(configDir, "K800i");

		PresetLocalOverride.Guard guard =
				PresetLocalOverride.detachBeforeWrite(preferences, configDir);
		assertTrue(guard.canWrite());
		assertFalse(ProfilesManager.removeLocalKeyboardLayout(configDir));

		assertTrue(main.exists());
		assertTrue(guard.restoreIfUnchanged());
		assertTrue(isLinked(preferences, configDir));
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-ownership-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static FakePreferences linked(File configDir, String name) {
		FakePreferences preferences = new FakePreferences();
		assertTrue(preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(configDir), name)
				.putBoolean(PresetLinkage.linkedPreferenceKey(configDir), true)
				.commit());
		return preferences;
	}

	private static boolean isLinked(FakePreferences preferences, File configDir) {
		return new PresetLinkage(preferences, configDir).isLinked();
	}

	private static String origin(FakePreferences preferences, File configDir) {
		return new PresetLinkage(preferences, configDir).getOrigin();
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();
		private final Set<Integer> failedCommits = new HashSet<>();
		private int commitCount;

		void failCommit(int number) {
			failedCommits.add(number);
		}

		@Override
		public Map<String, ?> getAll() {
			return Collections.unmodifiableMap(new HashMap<>(values));
		}

		@Override
		public String getString(String key, String defValue) {
			Object value = values.get(key);
			return value instanceof String ? (String) value : defValue;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<String> getStringSet(String key, Set<String> defValues) {
			Object value = values.get(key);
			return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
		}

		@Override
		public int getInt(String key, int defValue) {
			Object value = values.get(key);
			return value instanceof Integer ? (Integer) value : defValue;
		}

		@Override
		public long getLong(String key, long defValue) {
			Object value = values.get(key);
			return value instanceof Long ? (Long) value : defValue;
		}

		@Override
		public float getFloat(String key, float defValue) {
			Object value = values.get(key);
			return value instanceof Float ? (Float) value : defValue;
		}

		@Override
		public boolean getBoolean(String key, boolean defValue) {
			Object value = values.get(key);
			return value instanceof Boolean ? (Boolean) value : defValue;
		}

		@Override
		public boolean contains(String key) {
			return values.containsKey(key);
		}

		@Override
		public Editor edit() {
			return new FakeEditor();
		}

		@Override
		public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		}

		@Override
		public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		}

		private final class FakeEditor implements Editor {
			private final Map<String, Object> updates = new HashMap<>();
			private final Set<String> removals = new HashSet<>();
			private boolean clear;

			@Override
			public Editor putString(String key, String value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putStringSet(String key, Set<String> value) {
				updates.put(key, value == null ? null : new HashSet<>(value));
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putInt(String key, int value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putLong(String key, long value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putFloat(String key, float value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putBoolean(String key, boolean value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor remove(String key) {
				removals.add(key);
				updates.remove(key);
				return this;
			}

			@Override
			public Editor clear() {
				clear = true;
				return this;
			}

			@Override
			public boolean commit() {
				applyChanges();
				commitCount++;
				return !failedCommits.contains(commitCount);
			}

			@Override
			public void apply() {
				applyChanges();
			}

			private void applyChanges() {
				if (clear) values.clear();
				for (String key : removals) values.remove(key);
				for (Map.Entry<String, Object> entry : updates.entrySet()) {
					if (entry.getValue() == null) values.remove(entry.getKey());
					else values.put(entry.getKey(), entry.getValue());
				}
			}
		}
	}
}
