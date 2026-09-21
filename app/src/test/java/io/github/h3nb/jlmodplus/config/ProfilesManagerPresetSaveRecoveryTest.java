/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ProfilesManagerPresetSaveRecoveryTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;

	@Test
	public void saveSnapshotSuccessfulUpdateCommitsNewSourceAndRemovesRollback() throws Exception {
		File target = tempDir("save-success-target");
		File source = tempDir("save-success-source");
		writeConfig(target, 176);
		writeLayout(target, 1);
		writeConfig(source, 360);
		writeLayout(source, 2);

		ProfilesManager.saveSnapshot(target, source, true);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void readyCrashAfterOnlyConfigPublicationRestoresOldConfigAndLayout() throws Exception {
		File target = tempDir("ready-config-only");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createRollback(target, oldConfig, null, oldLayout, true, false);

		writeConfig(target, 999);

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void readyCrashAfterLayoutDeletionRestoresExistingLayout() throws Exception {
		File target = tempDir("ready-layout-delete");
		writeConfig(target, 176);
		writeLayout(target, 3);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createRollback(target, oldConfig, null, oldLayout, true, false);
		assertTrue(layoutFile(target).delete());

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
	}

	@Test
	public void readyConfigOnlyToCombinedTransitionRestoresCompleteOldSource() throws Exception {
		File target = tempDir("ready-config-to-combined");
		writeConfig(target, 176);
		byte[] oldConfig = readConfigBytes(target);
		createRollback(target, oldConfig, null, null, true, false);

		writeConfig(target, 999);
		writeLayout(target, 4);

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertFalse(layoutFile(target).exists());
	}

	@Test
	public void readyCrashAfterAllPublicationStillRollsBackBeforeCommitPoint() throws Exception {
		File target = tempDir("ready-all-published");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createRollback(target, oldConfig, null, oldLayout, true, false);

		writeConfig(target, 640);
		writeLayout(target, 5);

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
	}

	@Test
	public void noReadyAfterCommitKeepsNewSourceAndDiscardsRollback() throws Exception {
		File target = tempDir("committed-stale-rollback");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createRollback(target, oldConfig, null, oldLayout, false, false);
		writeConfig(target, 640);
		writeLayout(target, 5);
		byte[] newConfig = readConfigBytes(target);
		byte[] newLayout = readLayout(target);

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(newConfig, readConfigBytes(target));
		assertArrayEquals(newLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void noReadyBeforePublicationKeepsExistingSourceAndDiscardsRollback() throws Exception {
		File target = tempDir("preready-existing");
		writeConfig(target, 176);
		writeLayout(target, 2);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createRollback(target, oldConfig, null, oldLayout, false, false);

		ProfilesManager.recoverInterruptedPresetSave(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void interruptedNewProfileWithReadyIsRemovedAndNotEnumerated() throws Exception {
		File root = tempDir("new-ready-root");
		File target = new File(root, "NewPreset");
		assertTrue(target.mkdir());
		createRollback(target, null, null, null, true, true);
		writeConfig(target, 999);

		ArrayList<Profile> profiles = ProfilesManager.getList(root);

		assertFalse(target.exists());
		assertFalse(containsProfile(profiles, "NewPreset"));
	}

	@Test
	public void interruptedNewProfileBeforeReadyDoesNotLeaveEmptyVisibleEntry() throws Exception {
		File root = tempDir("new-preready-root");
		File target = new File(root, "NewPreset");
		assertTrue(target.mkdir());
		createRollback(target, null, null, null, false, true);

		ArrayList<Profile> profiles = ProfilesManager.getList(root);

		assertFalse(target.exists());
		assertFalse(containsProfile(profiles, "NewPreset"));
	}

	@Test
	public void committedNewProfileWithStaleNoReadyRollbackRemainsVisible() throws Exception {
		File root = tempDir("new-committed-root");
		File target = new File(root, "NewPreset");
		File source = tempDir("new-committed-source");
		writeConfig(source, 360);
		writeLayout(source, 2);
		ProfilesManager.saveSnapshot(target, source, true);
		byte[] committedConfig = readConfigBytes(target);
		byte[] committedLayout = readLayout(target);
		createRollback(target, null, null, null, false, true);

		ArrayList<Profile> profiles = ProfilesManager.getList(root);

		assertTrue(target.isDirectory());
		assertTrue(containsProfile(profiles, "NewPreset"));
		assertArrayEquals(committedConfig, readConfigBytes(target));
		assertArrayEquals(committedLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void recoveryFailureLeavesEvidenceAndInspectionFailsClosed() throws Exception {
		File target = tempDir("recovery-failure");
		writeConfig(target, 999);
		createBrokenReadyRollback(target, "config.json");
		byte[] partial = readConfigBytes(target);

		ProfilesManager.ProfileInfo info =
				ProfilesManager.inspectProfile(new Profile("Broken"), target);

		assertEquals(ProfilesManager.CapabilityStatus.UNAVAILABLE, info.settings.status);
		assertEquals(ProfilesManager.CapabilityStatus.UNAVAILABLE, info.keyboardLayout.status);
		assertArrayEquals(partial, readConfigBytes(target));
		assertTrue(saveRollback(target).isDirectory());
		assertTrue(new File(saveRollback(target), ProfilesManager.PRESET_SAVE_READY_MARKER).isFile());
	}

	@Test
	public void inspectProfileRecoversBeforeParsingSource() throws Exception {
		File target = tempDir("inspect-recovers");
		writeConfig(target, 176);
		byte[] oldConfig = readConfigBytes(target);
		createRollback(target, oldConfig, null, null, true, false);
		writeConfig(target, 999);

		ProfilesManager.ProfileInfo info =
				ProfilesManager.inspectProfile(new Profile("K800i"), target);

		assertEquals(ProfilesManager.CapabilityStatus.READY, info.settings.status);
		assertEquals(176, info.config.screenWidth);
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void syncSnapshotRecoversSourceBeforeMirroringFollower() throws Exception {
		File source = tempDir("sync-source-recovery");
		File target = tempDir("sync-source-target");
		writeConfig(source, 360);
		writeLayout(source, 2);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		createRollback(source, oldConfig, null, oldLayout, true, false);
		writeConfig(source, 999);
		writeConfig(target, 176);
		writeLayout(target, 1);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(saveRollback(source).exists());
	}

	@Test
	public void saveSnapshotRefusesToStartOverUnrecoverableTransaction() throws Exception {
		File target = tempDir("writer-refuses-target");
		File source = tempDir("writer-refuses-source");
		writeConfig(target, 999);
		byte[] partial = readConfigBytes(target);
		createBrokenReadyRollback(target, "config.json");
		writeConfig(source, 360);
		writeLayout(source, 2);

		expectIOException(() -> ProfilesManager.saveSnapshot(target, source, true));

		assertArrayEquals(partial, readConfigBytes(target));
		assertTrue(saveRollback(target).isDirectory());
	}

	@Test
	public void saveEditedSnapshotKeepsExistingLayoutWhenDraftLayoutIsUnreadable() throws Exception {
		File target = tempDir("edited-invalid-layout-target");
		File draft = tempDir("edited-invalid-layout-draft");
		writeConfig(target, 176);
		writeLayout(target, 2);
		byte[] oldLayout = readLayout(target);
		writeConfig(draft, 360);
		Files.write(layoutFile(draft).toPath(), "broken".getBytes(StandardCharsets.UTF_8));

		ProfilesManager.saveEditedSnapshot(target, draft);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void saveLayoutSnapshotRemainsLayoutOnlyAfterCommit() throws Exception {
		File target = tempDir("layout-only-target");
		File source = tempDir("layout-only-source");
		writeConfig(target, 176);
		Files.write(new File(target, "config.xml").toPath(), "legacy".getBytes(StandardCharsets.UTF_8));
		writeLayout(target, 1);
		writeLayout(source, 5);
		byte[] newLayout = readLayout(source);

		ProfilesManager.saveLayoutSnapshot(target, source);

		assertArrayEquals(newLayout, readLayout(target));
		assertFalse(configFile(target).exists());
		assertFalse(new File(target, "config.xml").exists());
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void renameRecoversOldSourceBeforeBytePreservingCopy() throws Exception {
		File root = tempDir("rename-recovers-root");
		File oldSource = new File(root, "K800i");
		assertTrue(oldSource.mkdir());
		writeConfig(oldSource, 360);
		byte[] oldConfig = readConfigBytes(oldSource);
		createRollback(oldSource, oldConfig, null, null, true, false);
		writeConfig(oldSource, 999);
		File midlet = tempDir("rename-recovers-midlet");
		FakePreferences preferences = linkedPreferences(midlet, "K800i");

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		File renamed = new File(root, "Sony K800i");
		assertEquals(360, readConfig(renamed).screenWidth);
		assertFalse(oldSource.exists());
		assertEquals("Sony K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void renameRecoveryFailurePublishesNothingAndKeepsReferences() throws Exception {
		File root = tempDir("rename-recovery-fail-root");
		File oldSource = new File(root, "K800i");
		assertTrue(oldSource.mkdir());
		writeConfig(oldSource, 999);
		createBrokenReadyRollback(oldSource, "config.json");
		File midlet = tempDir("rename-recovery-fail-midlet");
		FakePreferences preferences = linkedPreferences(midlet, "K800i");

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertTrue(oldSource.isDirectory());
		assertFalse(new File(root, "Sony K800i").exists());
		assertEquals("K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
		assertTrue(saveRollback(oldSource).isDirectory());
	}

	@Test
	public void linkedSourceRecoveryFailureKeepsLastKnownGoodFollowerAndLink() throws Exception {
		File profiles = tempDir("linked-fail-profiles");
		File source = new File(profiles, "K800i");
		assertTrue(source.mkdir());
		writeConfig(source, 999);
		createBrokenReadyRollback(source, "config.json");
		File target = tempDir("linked-fail-target");
		writeConfig(target, 176);
		writeLayout(target, 2);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		FakePreferences preferences = linkedPreferences(target, "K800i");

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertEquals("K800i", new PresetLinkage(preferences, target).getOrigin());
		assertTrue(new PresetLinkage(preferences, target).isLinked());
		assertTrue(saveRollback(source).isDirectory());
	}

	private static void createRollback(
			File profileDir,
			byte[] previousConfig,
			byte[] previousLegacy,
			byte[] previousLayout,
			boolean ready,
			boolean newProfile) throws Exception {
		File rollback = saveRollback(profileDir);
		assertTrue(rollback.mkdir());
		if (previousConfig != null) {
			Files.write(new File(rollback, "config.json").toPath(), previousConfig);
			assertTrue(new File(rollback, "config.json.present").createNewFile());
		}
		if (previousLegacy != null) {
			Files.write(new File(rollback, "config.xml").toPath(), previousLegacy);
			assertTrue(new File(rollback, "config.xml.present").createNewFile());
		}
		if (previousLayout != null) {
			Files.write(new File(rollback, "VirtualKeyboardLayout").toPath(), previousLayout);
			assertTrue(new File(rollback, "VirtualKeyboardLayout.present").createNewFile());
		}
		if (newProfile) {
			assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_NEW_PROFILE_MARKER).createNewFile());
		}
		if (ready) {
			assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_READY_MARKER).createNewFile());
		}
	}

	private static void createBrokenReadyRollback(File profileDir, String missingBackupName)
			throws Exception {
		File rollback = saveRollback(profileDir);
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, missingBackupName + ".present").createNewFile());
		assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_READY_MARKER).createNewFile());
	}

	private static boolean containsProfile(ArrayList<Profile> profiles, String name) {
		for (Profile profile : profiles) {
			if (name.equals(profile.getName())) return true;
		}
		return false;
	}

	private static FakePreferences linkedPreferences(File configDir, String origin) {
		FakePreferences preferences = new FakePreferences();
		assertTrue(preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(configDir), origin)
				.putBoolean(PresetLinkage.linkedPreferenceKey(configDir), true)
				.commit());
		return preferences;
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-preset-save-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static void writeConfig(File dir, int screenWidth) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = screenWidth;
		model.vkType = 1;
		model.systemProperties = "";
		Files.write(configFile(dir).toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static ProfileModel readConfig(File dir) throws Exception {
		return new Gson().fromJson(
				new String(readConfigBytes(dir), StandardCharsets.UTF_8),
				ProfileModel.class);
	}

	private static byte[] readConfigBytes(File dir) throws Exception {
		return Files.readAllBytes(configFile(dir).toPath());
	}

	private static void writeLayout(File dir, int type) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile(dir)))) {
			out.writeInt(LAYOUT_SIGNATURE);
			out.writeInt(1);
			out.writeInt(LAYOUT_TYPE);
			out.writeInt(1);
			out.writeByte(type);
			out.writeInt(LAYOUT_EOF);
			out.writeInt(0);
		}
	}

	private static byte[] readLayout(File dir) throws Exception {
		return Files.readAllBytes(layoutFile(dir).toPath());
	}

	private static File configFile(File dir) {
		return new File(dir, "config.json");
	}

	private static File layoutFile(File dir) {
		return new File(dir, "VirtualKeyboardLayout");
	}

	private static File saveRollback(File dir) {
		return new File(dir, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR);
	}

	private static void expectIOException(ThrowingRunnable action) throws Exception {
		try {
			action.run();
			fail("Expected IOException");
		} catch (IOException expected) {
			// Expected.
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();

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
				return true;
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
