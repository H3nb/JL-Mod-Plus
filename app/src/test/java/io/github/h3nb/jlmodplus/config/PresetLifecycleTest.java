/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PresetLifecycleTest {
	@Test
	public void renameOnlyRewritesOriginsInManagedWorkdir() throws Exception {
		File rootA = tempDir("scope-rename-a");
		File rootB = tempDir("scope-rename-b");
		source(rootA, "K800i");
		source(rootB, "K800i");
		File customA = configDir(rootA, "custom-a");
		File linkedA = configDir(rootA, "linked-a");
		File customB = configDir(rootB, "custom-b");
		File linkedB = configDir(rootB, "linked-b");
		FakePreferences preferences = origin(preferences(), customA, "K800i", false);
		origin(preferences, linkedA, "K800i", true);
		origin(preferences, customB, "K800i", false);
		origin(preferences, linkedB, "K800i", true);
		String malformed = PresetLinkage.originPreferencePrefix() + "relative/config";
		assertTrue(preferences.edit().putString(malformed, "K800i").commit());

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, rootB, "K800i", "Sony"));

		assertEquals("K800i", new PresetLinkage(preferences, customA).getOrigin());
		assertEquals("K800i", new PresetLinkage(preferences, linkedA).getOrigin());
		assertTrue(new PresetLinkage(preferences, linkedA).isLinked());
		assertEquals("Sony", new PresetLinkage(preferences, customB).getOrigin());
		assertFalse(new PresetLinkage(preferences, customB).isLinked());
		assertEquals("Sony", new PresetLinkage(preferences, linkedB).getOrigin());
		assertTrue(new PresetLinkage(preferences, linkedB).isLinked());
		assertEquals("K800i", preferences.getString(malformed, null));
	}

	@Test
	public void similarlyPrefixedWorkdirsDoNotShareOrigins() throws Exception {
		File parent = Files.createTempDirectory("jlmod-preset-prefix").toFile();
		File root = new File(parent, "work/templates");
		File foreignRoot = new File(parent, "work2/templates");
		assertTrue(root.mkdirs());
		assertTrue(foreignRoot.mkdirs());
		source(root, "K800i");
		File foreignGame = configDir(foreignRoot, "game");
		FakePreferences preferences = origin(preferences(), foreignGame, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony"));

		assertEquals("K800i", new PresetLinkage(preferences, foreignGame).getOrigin());
		assertTrue(new PresetLinkage(preferences, foreignGame).isLinked());
	}

	@Test
	public void deleteOnlyClearsOriginsInManagedWorkdir() throws Exception {
		File rootA = tempDir("scope-delete-a");
		File rootB = tempDir("scope-delete-b");
		source(rootA, "K800i");
		source(rootB, "K800i");
		File customA = configDir(rootA, "custom-a");
		File linkedA = configDir(rootA, "linked-a");
		File customB = configDir(rootB, "custom-b");
		File linkedB = configDir(rootB, "linked-b");
		FakePreferences preferences = origin(preferences(), customA, "K800i", false);
		origin(preferences, linkedA, "K800i", true);
		origin(preferences, customB, "K800i", false);
		origin(preferences, linkedB, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, rootB, "K800i"));

		assertEquals("K800i", new PresetLinkage(preferences, customA).getOrigin());
		assertEquals("K800i", new PresetLinkage(preferences, linkedA).getOrigin());
		assertTrue(new PresetLinkage(preferences, linkedA).isLinked());
		assertNull(new PresetLinkage(preferences, customB).getOrigin());
		assertNull(new PresetLinkage(preferences, linkedB).getOrigin());
		assertFalse(new PresetLinkage(preferences, linkedB).isLinked());
	}

	@Test
	public void failedMetadataCommitRestoresOnlyManagedWorkdirReferences() throws Exception {
		File rootA = tempDir("scope-failure-a");
		File rootB = tempDir("scope-failure-b");
		source(rootB, "K800i");
		File followerA = configDir(rootA, "game-a");
		File followerB = configDir(rootB, "game-b");
		FakePreferences preferences = origin(preferences(), followerA, "K800i", true);
		origin(preferences, followerB, "K800i", true);
		preferences.failNextCommit();

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.delete(preferences, rootB, "K800i"));

		assertEquals("K800i", new PresetLinkage(preferences, followerA).getOrigin());
		assertTrue(new PresetLinkage(preferences, followerA).isLinked());
		assertEquals("K800i", new PresetLinkage(preferences, followerB).getOrigin());
		assertTrue(new PresetLinkage(preferences, followerB).isLinked());
	}
	@Test
	public void renameCompletePresetPreservesBytesAndUpdatesOrigin() throws Exception {
		File root = tempDir("rename-complete");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "config-bytes");
		write(oldSource, "VirtualKeyboardLayout", "layout-bytes");
		File midlet = configDir(root, "rename-complete-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", false);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		File renamed = new File(root, "Sony K800i");
		assertFalse(oldSource.exists());
		assertArrayEquals(bytes("config-bytes"), read(renamed, "config.json"));
		assertArrayEquals(bytes("layout-bytes"), read(renamed, "VirtualKeyboardLayout"));
		assertEquals("Sony K800i", new PresetLinkage(preferences, midlet).getOrigin());
	}

	@Test
	public void linkedFollowerRenameKeepsLinkMarker() throws Exception {
		File root = tempDir("rename-linked");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "x");
		File midlet = configDir(root, "rename-linked-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		PresetLinkage linkage = new PresetLinkage(preferences, midlet);
		assertEquals("Sony K800i", linkage.getOrigin());
		assertTrue(linkage.isLinked());
	}

	@Test
	public void provenanceOnlyRenameStaysCustom() throws Exception {
		File root = tempDir("rename-custom");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "x");
		File midlet = configDir(root, "rename-custom-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", false);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		PresetLinkage linkage = new PresetLinkage(preferences, midlet);
		assertEquals("Sony K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void defaultPresetRenameUpdatesDefault() throws Exception {
		File root = tempDir("rename-default");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "x");
		FakePreferences preferences = preferences();
		assertTrue(preferences.edit().putString(PREF_DEFAULT_PROFILE, "K800i").commit());

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertEquals("Sony K800i", preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void renameRewritesDefaultAndMultipleOriginsInOneCommit() throws Exception {
		File root = tempDir("rename-one-commit");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "x");
		File first = configDir(root, "rename-one-first");
		File second = configDir(root, "rename-one-second");
		FakePreferences preferences = preferences();
		assertTrue(preferences.edit()
				.putString(PREF_DEFAULT_PROFILE, "K800i")
				.putString(PresetLinkage.originPreferenceKey(first), "K800i")
				.putBoolean(PresetLinkage.linkedPreferenceKey(first), true)
				.putString(PresetLinkage.originPreferenceKey(second), "K800i")
				.commit());
		int before = preferences.commitCount();

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertEquals(before + 1, preferences.commitCount());
		assertEquals("Sony K800i", preferences.getString(PREF_DEFAULT_PROFILE, null));
		assertEquals("Sony K800i", new PresetLinkage(preferences, first).getOrigin());
		assertEquals("Sony K800i", new PresetLinkage(preferences, second).getOrigin());
		assertTrue(new PresetLinkage(preferences, first).isLinked());
		assertFalse(new PresetLinkage(preferences, second).isLinked());
	}

	@Test
	public void renamePublicationFailureLeavesOldReferences() throws Exception {
		File root = tempDir("rename-publish-fail");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "x");
		File midlet = configDir(root, "rename-publish-fail-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		PresetLifecycle.Result result = PresetLifecycle.rename(
				preferences, root, "K800i", "Sony K800i", new PresetLifecycle.FileActions() {
					@Override
					public boolean publish(File staging, File published) {
						return false;
					}

					@Override
					public boolean deleteSource(File source) {
						return true;
					}
				});

		assertEquals(PresetLifecycle.Result.FAILED, result);
		assertTrue(oldSource.isDirectory());
		assertFalse(new File(root, "Sony K800i").exists());
		assertEquals("K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void renameReferenceCommitFailureKeepsResolvableOldSource() throws Exception {
		File root = tempDir("rename-ref-fail");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "same");
		File midlet = configDir(root, "rename-ref-fail-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);
		preferences.failNextCommit();

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertTrue(oldSource.isDirectory());
		assertEquals("K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new File(root, new PresetLinkage(preferences, midlet).getOrigin()).isDirectory());
		assertFalse(new File(root, "Sony K800i").exists());
	}

	@Test
	public void renameReferenceRestoreFailureRetainsBothSources() throws Exception {
		File root = tempDir("rename-restore-fail");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "same");
		File midlet = configDir(root, "rename-restore-fail-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);
		preferences.failNextCommits(2);

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertTrue(oldSource.isDirectory());
		assertTrue(new File(root, "Sony K800i").isDirectory());
	}

	@Test
	public void referenceCommitBeginsOnlyAfterNewSourceIsPublished() throws Exception {
		File root = tempDir("rename-crash-before-ref");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "same");
		File midlet = configDir(root, "rename-crash-before-ref-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);
		boolean[] observedSafeCrashState = {false};
		preferences.beforeNextCommit(() -> {
			assertTrue(oldSource.isDirectory());
			assertTrue(new File(root, "Sony K800i").isDirectory());
			assertEquals("K800i", new PresetLinkage(preferences, midlet).getOrigin());
			observedSafeCrashState[0] = true;
		});

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertTrue(observedSafeCrashState[0]);
	}

	@Test
	public void crashEquivalentAfterReferenceCommitKeepsNewReferenceResolvable() throws Exception {
		File root = tempDir("rename-crash-after-ref");
		File oldSource = source(root, "K800i");
		write(oldSource, "config.json", "same");
		File midlet = configDir(root, "rename-crash-after-ref-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		PresetLifecycle.Result result = PresetLifecycle.rename(
				preferences, root, "K800i", "Sony K800i", new PresetLifecycle.FileActions() {
					@Override
					public boolean publish(File staging, File published) {
						return staging.renameTo(published);
					}

					@Override
					public boolean deleteSource(File source) {
						return false;
					}
				});

		assertEquals(PresetLifecycle.Result.CLEANUP_FAILED, result);
		assertTrue(oldSource.isDirectory());
		assertTrue(new File(root, "Sony K800i").isDirectory());
		assertEquals("Sony K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void layoutOnlyRenamePreservesArtifactBytes() throws Exception {
		File root = tempDir("rename-layout-only");
		File oldSource = source(root, "Layout");
		byte[] layout = new byte[] {0, 1, 2, 3, 4, -1};
		Files.write(new File(oldSource, "VirtualKeyboardLayout").toPath(), layout);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences(), root, "Layout", "Layout New"));

		assertArrayEquals(layout, read(new File(root, "Layout New"), "VirtualKeyboardLayout"));
	}

	@Test
	public void corruptPresetRenamePreservesRawBytes() throws Exception {
		File root = tempDir("rename-corrupt");
		File oldSource = source(root, "Broken");
		byte[] corrupt = new byte[] {(byte) 0xff, 0, 7, 11, 42};
		Files.write(new File(oldSource, "config.json").toPath(), corrupt);
		File nested = new File(oldSource, "opaque");
		assertTrue(nested.mkdir());
		write(nested, "payload.bin", "opaque-data");

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences(), root, "Broken", "Broken Renamed"));

		File renamed = new File(root, "Broken Renamed");
		assertArrayEquals(corrupt, read(renamed, "config.json"));
		assertArrayEquals(bytes("opaque-data"), read(new File(renamed, "opaque"), "payload.bin"));
	}

	@Test
	public void deleteLinkedPresetClearsOwnershipWithoutTouchingLocalSnapshot() throws Exception {
		File root = tempDir("delete-linked");
		File source = source(root, "K800i");
		write(source, "config.json", "source");
		File midlet = configDir(root, "delete-linked-midlet");
		write(midlet, "config.json", "local-snapshot");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertFalse(source.exists());
		assertNull(new PresetLinkage(preferences, midlet).getOrigin());
		assertFalse(new PresetLinkage(preferences, midlet).isLinked());
		assertArrayEquals(bytes("local-snapshot"), read(midlet, "config.json"));
	}

	@Test
	public void deleteProvenanceOnlyPresetClearsOrigin() throws Exception {
		File root = tempDir("delete-custom");
		source(root, "K800i");
		File midlet = configDir(root, "delete-custom-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", false);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertNull(new PresetLinkage(preferences, midlet).getOrigin());
		assertFalse(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void deleteDefaultPresetClearsDefault() throws Exception {
		File root = tempDir("delete-default");
		source(root, "K800i");
		FakePreferences preferences = preferences();
		assertTrue(preferences.edit().putString(PREF_DEFAULT_PROFILE, "K800i").commit());

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertNull(preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void deleteOnlyClearsExactMatchingOrigins() throws Exception {
		File root = tempDir("delete-exact");
		source(root, "K800i");
		File matching = configDir(root, "delete-exact-match");
		File other = configDir(root, "delete-exact-other");
		FakePreferences preferences = origin(preferences(), matching, "K800i", true);
		origin(preferences, other, "Sony K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertNull(new PresetLinkage(preferences, matching).getOrigin());
		assertEquals("Sony K800i", new PresetLinkage(preferences, other).getOrigin());
		assertTrue(new PresetLinkage(preferences, other).isLinked());
	}

	@Test
	public void deleteMetadataCommitFailureDoesNotDeleteSource() throws Exception {
		File root = tempDir("delete-metadata-fail");
		File source = source(root, "K800i");
		File midlet = configDir(root, "delete-metadata-fail-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);
		preferences.failNextCommit();

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertTrue(source.isDirectory());
		assertEquals("K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void deleteFilesystemFailureDoesNotRestoreReferences() throws Exception {
		File root = tempDir("delete-filesystem-fail");
		File source = source(root, "K800i");
		File midlet = configDir(root, "delete-filesystem-fail-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);
		assertTrue(preferences.edit().putString(PREF_DEFAULT_PROFILE, "K800i").commit());

		PresetLifecycle.Result result = PresetLifecycle.delete(
				preferences, root, "K800i", new PresetLifecycle.FileActions() {
					@Override
					public boolean publish(File staging, File published) {
						return false;
					}

					@Override
					public boolean deleteSource(File source) {
						return false;
					}
				});

		assertEquals(PresetLifecycle.Result.CLEANUP_FAILED, result);
		assertTrue(source.isDirectory());
		assertNull(new PresetLinkage(preferences, midlet).getOrigin());
		assertFalse(new PresetLinkage(preferences, midlet).isLinked());
		assertNull(preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void internalRenameStagingDirectoryIsNotEnumerated() throws Exception {
		File root = tempDir("enumeration");
		assertTrue(new File(root, PresetLifecycle.RENAME_STAGING_PREFIX + "pending").mkdir());
		assertTrue(new File(root, ".historical-profile").mkdir());
		assertTrue(new File(root, "K800i").mkdir());

		ArrayList<Profile> profiles = ProfilesManager.getList(root);
		Set<String> names = new HashSet<>();
		for (Profile profile : profiles) names.add(profile.getName());

		assertFalse(names.contains(PresetLifecycle.RENAME_STAGING_PREFIX + "pending"));
		assertTrue(names.contains(".historical-profile"));
		assertTrue(names.contains("K800i"));
	}

	@Test
	public void refreshedActiveMetadataAfterRenameResolvesNewOrigin() throws Exception {
		File root = tempDir("refresh-rename");
		File source = source(root, "K800i");
		write(source, "config.json", "x");
		File midlet = configDir(root, "refresh-rename-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));

		assertEquals("Sony K800i", ConfigActivity.profileOriginFromMetadata(preferences, midlet));
	}

	@Test
	public void refreshedActiveMetadataAfterDeleteResolvesNullOrigin() throws Exception {
		File root = tempDir("refresh-delete");
		source(root, "K800i");
		File midlet = configDir(root, "refresh-delete-midlet");
		FakePreferences preferences = origin(preferences(), midlet, "K800i", true);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));

		assertNull(ConfigActivity.profileOriginFromMetadata(preferences, midlet));
	}

	private static FakePreferences preferences() {
		return new FakePreferences();
	}

	private static FakePreferences origin(
			FakePreferences preferences, File configDir, String name, boolean linked) {
		SharedPreferences.Editor editor = preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(configDir), name);
		if (linked) editor.putBoolean(PresetLinkage.linkedPreferenceKey(configDir), true);
		assertTrue(editor.commit());
		return preferences;
	}

	private static File tempDir(String suffix) throws Exception {
		File workdir = Files.createTempDirectory("jlmod-preset-lifecycle-" + suffix).toFile();
		File root = new File(workdir, "templates");
		assertTrue(root.mkdir());
		return root;
	}

	private static File configDir(File root, String name) {
		File configs = new File(root.getParentFile(), "configs");
		assertTrue(configs.isDirectory() || configs.mkdir());
		File dir = new File(configs, name);
		assertTrue(dir.mkdir());
		return dir;
	}

	private static File source(File root, String name) {
		File dir = new File(root, name);
		assertTrue(dir.mkdir());
		return dir;
	}

	private static void write(File dir, String name, String value) throws Exception {
		Files.write(new File(dir, name).toPath(), bytes(value));
	}

	private static byte[] read(File dir, String name) throws Exception {
		return Files.readAllBytes(new File(dir, name).toPath());
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();
		private final Set<Integer> failedCommits = new HashSet<>();
		private int commitCount;
		private Runnable nextCommitHook;

		void failNextCommit() {
			failedCommits.add(commitCount + 1);
		}

		void failNextCommits(int count) {
			for (int i = 1; i <= count; i++) {
				failedCommits.add(commitCount + i);
			}
		}

		int commitCount() {
			return commitCount;
		}

		void beforeNextCommit(Runnable hook) {
			nextCommitHook = hook;
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
				if (nextCommitHook != null) {
					Runnable hook = nextCommitHook;
					nextCommitHook = null;
					hook.run();
				}
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
