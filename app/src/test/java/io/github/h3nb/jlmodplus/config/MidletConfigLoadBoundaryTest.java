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
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MidletConfigLoadBoundaryTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;
	private static final String SYNC_ROLLBACK_DIR = ".preset-sync.rollback";
	private static final String SYNC_STAGING_DIR = ".preset-sync.tmp";
	private static final String SYNC_READY_MARKER = ".ready";

	@Test
	public void unlinkedReadyTransactionIsRecoveredBeforeLoad() throws Exception {
		File profiles = tempDir("profiles-unlinked-ready");
		File target = tempDir("target-unlinked-ready");
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] oldLayout = readLayout(target);
		createInterruptedSyncRollback(target, oldConfig, oldLayout, true);
		writeConfig(target, 999, 1);
		writeLayout(target, 5);

		assertTrue(MidletConfigLoadBoundary.prepare(new FakePreferences(), target, profiles));

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(syncRollbackDir(target).exists());
	}

	@Test
	public void unlinkedPreReadyTransactionIsDiscardedWithoutChangingDestination() throws Exception {
		File profiles = tempDir("profiles-unlinked-preready");
		File target = tempDir("target-unlinked-preready");
		File stale = tempDir("stale-preready");
		writeConfig(target, 222, 1);
		writeLayout(target, 2);
		byte[] currentConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] currentLayout = readLayout(target);
		writeConfig(stale, 111, 1);
		writeLayout(stale, 1);
		createInterruptedSyncRollback(
				target,
				Files.readAllBytes(configFile(stale).toPath()),
				readLayout(stale),
				false);
		File staging = new File(target, SYNC_STAGING_DIR);
		assertTrue(staging.mkdir());
		Files.write(new File(staging, "unused").toPath(), new byte[] {1});

		assertTrue(MidletConfigLoadBoundary.prepare(new FakePreferences(), target, profiles));

		assertArrayEquals(currentConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(currentLayout, readLayout(target));
		assertFalse(syncRollbackDir(target).exists());
		assertFalse(staging.exists());
	}

	@Test
	public void recoveryCompletesBeforeLinkageIsInspected() throws Exception {
		File profiles = tempDir("profiles-order");
		File target = tempDir("target-order");
		writeConfig(target, 176, 1);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		createInterruptedSyncRollback(target, oldConfig, null, true);
		writeConfig(target, 999, 1);
		FakePreferences preferences = linkedPreferences(target, "MissingPreset");
		preferences.beforeRead = () -> assertFalse(
				"linkage must not be inspected before local recovery",
				syncRollbackDir(target).exists());

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));
		assertEquals(176, readConfig(target).screenWidth);
	}

	@Test
	public void linkedValidSourceIsMaterializedBeforeLoad() throws Exception {
		File profiles = tempDir("profiles-valid");
		File source = new File(profiles, "K800i");
		File target = tempDir("target-valid");
		assertTrue(source.mkdir());
		writeConfig(source, 360, 1);
		writeLayout(source, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		FakePreferences preferences = linkedPreferences(target, "K800i");

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void linkedMissingSourceKeepsLastKnownGoodAndLinkage() throws Exception {
		File profiles = tempDir("profiles-missing");
		File target = tempDir("target-missing");
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] oldLayout = readLayout(target);
		FakePreferences preferences = linkedPreferences(target, "K800i");

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(oldLayout, readLayout(target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void linkedMalformedSourceKeepsLastKnownGoodAndLinkage() throws Exception {
		File profiles = tempDir("profiles-malformed");
		File source = new File(profiles, "K800i");
		File target = tempDir("target-malformed");
		assertTrue(source.mkdir());
		Files.write(configFile(source).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] oldLayout = readLayout(target);
		FakePreferences preferences = linkedPreferences(target, "K800i");

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(oldLayout, readLayout(target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void syncFailureFollowedBySuccessfulRecoveryAllowsLocalLoad() throws Exception {
		File profiles = tempDir("profiles-sync-failure");
		File source = new File(profiles, "K800i");
		File target = tempDir("target-sync-failure");
		assertTrue(source.mkdir());
		writeConfig(source, 360, javax.microedition.lcdui.keyboard.VirtualKeyboard.TYPE_CUSTOM);
		writeConfig(target, 176, 1);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = linkedPreferences(target, "K800i");

		// The source is an invalid complete snapshot (Custom without a layout). syncSnapshot fails,
		// then the boundary's source-independent recovery succeeds and permits the known-good local load.
		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertFalse(syncRollbackDir(target).exists());
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void recoveryFailureFailsClosed() throws Exception {
		File profiles = tempDir("profiles-recovery-failure");
		File target = tempDir("target-recovery-failure");
		writeConfig(target, 999, 1);
		File rollback = syncRollbackDir(target);
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		assertTrue(new File(rollback, SYNC_READY_MARKER).createNewFile());
		// Deliberately omit rollback/config.json: recovery must fail instead of exposing target 999.

		assertFalse(MidletConfigLoadBoundary.prepare(new FakePreferences(), target, profiles));
		assertEquals(999, readConfig(target).screenWidth);
	}

	@Test
	public void originWithoutLinkMarkerDoesNotSyncPreset() throws Exception {
		File profiles = tempDir("profiles-origin-only");
		File source = new File(profiles, "K800i");
		File target = tempDir("target-origin-only");
		assertTrue(source.mkdir());
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(target), "K800i")
				.remove(PresetLinkage.linkedPreferenceKey(target))
				.commit();

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profiles));

		assertEquals(176, readConfig(target).screenWidth);
		assertFalse(preferences.getBoolean(PresetLinkage.linkedPreferenceKey(target), false));
	}

	private static FakePreferences linkedPreferences(File target, String origin) {
		FakePreferences preferences = new FakePreferences();
		preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(target), origin)
				.putBoolean(PresetLinkage.linkedPreferenceKey(target), true)
				.commit();
		return preferences;
	}

	private static void assertLinked(FakePreferences preferences, File target, String origin) {
		assertEquals(origin, preferences.getString(PresetLinkage.originPreferenceKey(target), null));
		assertTrue(preferences.getBoolean(PresetLinkage.linkedPreferenceKey(target), false));
	}

	private static File tempDir(String name) throws IOException {
		File dir = Files.createTempDirectory("jlmod-" + name).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static void writeConfig(File dir, int screenWidth, int vkType) throws IOException {
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = screenWidth;
		model.vkType = vkType;
		model.systemProperties = "";
		Files.write(configFile(dir).toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static ProfileModel readConfig(File dir) throws IOException {
		return new Gson().fromJson(
				new String(Files.readAllBytes(configFile(dir).toPath()), StandardCharsets.UTF_8),
				ProfileModel.class);
	}

	private static void writeLayout(File dir, int type) throws IOException {
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

	private static byte[] readLayout(File dir) throws IOException {
		return Files.readAllBytes(layoutFile(dir).toPath());
	}

	private static void createInterruptedSyncRollback(
			File target, byte[] previousConfig, byte[] previousLayout, boolean ready)
			throws IOException {
		File rollback = syncRollbackDir(target);
		assertTrue(rollback.mkdir());
		Files.write(new File(rollback, "config.json").toPath(), previousConfig);
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		if (previousLayout != null) {
			Files.write(new File(rollback, "VirtualKeyboardLayout").toPath(), previousLayout);
			assertTrue(new File(rollback, "VirtualKeyboardLayout.present").createNewFile());
		}
		if (ready) {
			assertTrue(new File(rollback, SYNC_READY_MARKER).createNewFile());
		}
	}

	private static File configFile(File dir) {
		return new File(dir, "config.json");
	}

	private static File layoutFile(File dir) {
		return new File(dir, "VirtualKeyboardLayout");
	}

	private static File syncRollbackDir(File dir) {
		return new File(dir, SYNC_ROLLBACK_DIR);
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();
		Runnable beforeRead;

		private void beforeRead() {
			if (beforeRead != null) {
				Runnable check = beforeRead;
				beforeRead = null;
				check.run();
			}
		}

		@Override
		public Map<String, ?> getAll() {
			beforeRead();
			return Collections.unmodifiableMap(new HashMap<>(values));
		}

		@Override
		public String getString(String key, String defValue) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof String ? (String) value : defValue;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<String> getStringSet(String key, Set<String> defValues) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
		}

		@Override
		public int getInt(String key, int defValue) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof Integer ? (Integer) value : defValue;
		}

		@Override
		public long getLong(String key, long defValue) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof Long ? (Long) value : defValue;
		}

		@Override
		public float getFloat(String key, float defValue) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof Float ? (Float) value : defValue;
		}

		@Override
		public boolean getBoolean(String key, boolean defValue) {
			beforeRead();
			Object value = values.get(key);
			return value instanceof Boolean ? (Boolean) value : defValue;
		}

		@Override
		public boolean contains(String key) {
			beforeRead();
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
