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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ProfilesManagerPresetSourceConcurrencyTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;

	@Test
	public void sourceWriterUsesSharedPresetMonitor() throws Exception {
		File target = tempDir("writer-lock-target");
		File source = tempDir("writer-lock-source");
		writeConfig(target, 176);
		writeLayout(target, 1);
		writeConfig(source, 360);
		writeLayout(source, 2);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread writer;
		synchronized (ProfilesManager.presetSourceLock()) {
			writer = thread("preset-writer", failure,
					() -> ProfilesManager.saveSnapshot(target, source, true));
			writer.start();
			awaitBlocked(writer);
			assertEquals(176, readConfig(target).screenWidth);
			assertFalse(saveRollback(target).exists());
		}
		join(writer, failure);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
	}

	@Test
	public void activeSaveTransactionBlocksInspectionUntilCommit() throws Exception {
		File target = tempDir("inspect-live-target");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		AtomicReference<ProfilesManager.ProfileInfo> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread inspector;
		synchronized (ProfilesManager.presetSourceLock()) {
			createLiveRollback(target, oldConfig, oldLayout, false);
			writeConfig(target, 360);

			inspector = thread("preset-inspector", failure,
					() -> result.set(ProfilesManager.inspectProfile(new Profile("K800i"), target)));
			inspector.start();
			awaitBlocked(inspector);

			assertTrue(readyMarker(target).isFile());
			assertArrayEquals(oldLayout, readLayout(target));
			writeLayout(target, 2);
			commitLiveTransaction(target);
		}
		join(inspector, failure);

		assertEquals(360, result.get().config.screenWidth);
		assertTrue(result.get().keyboardLayout.isReady());
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void inspectorCannotRollbackAfterConfigPublishedBeforeLayout() throws Exception {
		File target = tempDir("inspect-between-artifacts");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		byte[] newLayout;
		AtomicReference<ProfilesManager.ProfileInfo> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread inspector;
		synchronized (ProfilesManager.presetSourceLock()) {
			createLiveRollback(target, oldConfig, oldLayout, false);
			writeConfig(target, 640);
			assertArrayEquals(oldLayout, readLayout(target));

			inspector = thread("mid-publication-inspector", failure,
					() -> result.set(ProfilesManager.inspectProfile(new Profile("K800i"), target)));
			inspector.start();
			awaitBlocked(inspector);

			assertEquals(640, readConfig(target).screenWidth);
			assertArrayEquals(oldLayout, readLayout(target));
			writeLayout(target, 5);
			newLayout = readLayout(target);
			commitLiveTransaction(target);
		}
		join(inspector, failure);

		assertEquals(640, result.get().config.screenWidth);
		assertArrayEquals(newLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void activeNewProfileTransactionBlocksEnumerationUntilCommit() throws Exception {
		File root = tempDir("enumeration-root");
		File target = new File(root, "NewPreset");
		assertTrue(target.mkdir());
		AtomicReference<ArrayList<Profile>> profiles = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread scanner;
		synchronized (ProfilesManager.presetSourceLock()) {
			createLiveRollback(target, null, null, true);
			writeConfig(target, 360);

			scanner = thread("preset-enumerator", failure,
					() -> profiles.set(ProfilesManager.getList(root)));
			scanner.start();
			awaitBlocked(scanner);

			assertTrue(target.isDirectory());
			assertTrue(new File(saveRollback(target),
					ProfilesManager.PRESET_SAVE_NEW_PROFILE_MARKER).isFile());
			assertTrue(readyMarker(target).isFile());
			commitLiveTransaction(target);
		}
		join(scanner, failure);

		assertTrue(target.isDirectory());
		assertTrue(containsProfile(profiles.get(), "NewPreset"));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void followerSyncWaitsForCoherentCommittedSource() throws Exception {
		File source = tempDir("sync-live-source");
		File target = tempDir("sync-live-target");
		writeConfig(source, 176);
		writeLayout(source, 1);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		writeConfig(target, 111);
		writeLayout(target, 4);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		byte[] committedLayout;

		Thread follower;
		synchronized (ProfilesManager.presetSourceLock()) {
			createLiveRollback(source, oldConfig, oldLayout, false);
			writeConfig(source, 720);

			follower = thread("preset-follower-sync", failure,
					() -> ProfilesManager.syncSnapshot(source, target));
			follower.start();
			awaitBlocked(follower);

			assertEquals(111, readConfig(target).screenWidth);
			writeLayout(source, 6);
			committedLayout = readLayout(source);
			commitLiveTransaction(source);
		}
		join(follower, failure);

		assertEquals(720, readConfig(target).screenWidth);
		assertArrayEquals(committedLayout, readLayout(target));
	}

	@Test
	public void failedWriterRollsBackBeforeWaitingInspectorRuns() throws Exception {
		File target = tempDir("failed-writer-target");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		AtomicReference<ProfilesManager.ProfileInfo> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread inspector;
		synchronized (ProfilesManager.presetSourceLock()) {
			createLiveRollback(target, oldConfig, oldLayout, false);
			writeConfig(target, 999);

			inspector = thread("failed-writer-inspector", failure,
					() -> result.set(ProfilesManager.inspectProfile(new Profile("K800i"), target)));
			inspector.start();
			awaitBlocked(inspector);

			ProfilesManager.recoverInterruptedPresetSave(target);
			assertArrayEquals(oldConfig, readConfigBytes(target));
			assertArrayEquals(oldLayout, readLayout(target));
			assertFalse(saveRollback(target).exists());
		}
		join(inspector, failure);

		assertEquals(176, result.get().config.screenWidth);
		assertTrue(result.get().keyboardLayout.isReady());
	}

	@Test
	public void renameWaitsForSourceWriterAndCopiesCommittedVersion() throws Exception {
		File root = tempDir("rename-root");
		File oldSource = new File(root, "K800i");
		assertTrue(oldSource.mkdir());
		writeConfig(oldSource, 176);
		writeLayout(oldSource, 1);
		File update = tempDir("rename-update");
		writeConfig(update, 480);
		writeLayout(update, 5);
		File midlet = tempDir("rename-midlet");
		FakePreferences preferences = linkedPreferences(midlet, "K800i");
		CountDownLatch writerCommitted = new CountDownLatch(1);
		CountDownLatch releaseWriter = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();
		AtomicReference<PresetLifecycle.Result> renameResult = new AtomicReference<>();

		Thread writer = thread("rename-source-writer", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				ProfilesManager.saveSnapshot(oldSource, update, true);
				writerCommitted.countDown();
				if (!releaseWriter.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to release source writer");
				}
			}
		});
		writer.start();
		assertTrue(writerCommitted.await(5, TimeUnit.SECONDS));

		Thread rename = thread("preset-rename", renameFailure,
				() -> renameResult.set(PresetLifecycle.rename(
						preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);

		assertEquals(480, readConfig(oldSource).screenWidth);
		releaseWriter.countDown();
		join(writer, writerFailure);
		join(rename, renameFailure);

		assertEquals(PresetLifecycle.Result.SUCCESS, renameResult.get());
		File renamed = new File(root, "Sony K800i");
		assertEquals(480, readConfig(renamed).screenWidth);
		assertArrayEquals(readLayout(update), readLayout(renamed));
		assertFalse(oldSource.exists());
		assertEquals("Sony K800i", new PresetLinkage(preferences, midlet).getOrigin());
		assertTrue(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void deleteWaitsForSourceWriterBeforeDeletingCommittedSource() throws Exception {
		File root = tempDir("delete-root");
		File source = new File(root, "K800i");
		assertTrue(source.mkdir());
		writeConfig(source, 176);
		writeLayout(source, 1);
		File update = tempDir("delete-update");
		writeConfig(update, 480);
		writeLayout(update, 5);
		File midlet = tempDir("delete-midlet");
		FakePreferences preferences = linkedPreferences(midlet, "K800i");
		CountDownLatch writerCommitted = new CountDownLatch(1);
		CountDownLatch releaseWriter = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
		AtomicReference<PresetLifecycle.Result> deleteResult = new AtomicReference<>();

		Thread writer = thread("delete-source-writer", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				ProfilesManager.saveSnapshot(source, update, true);
				writerCommitted.countDown();
				if (!releaseWriter.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to release source writer");
				}
			}
		});
		writer.start();
		assertTrue(writerCommitted.await(5, TimeUnit.SECONDS));

		Thread delete = thread("preset-delete", deleteFailure,
				() -> deleteResult.set(PresetLifecycle.delete(preferences, root, "K800i")));
		delete.start();
		awaitBlocked(delete);

		assertEquals(480, readConfig(source).screenWidth);
		releaseWriter.countDown();
		join(writer, writerFailure);
		join(delete, deleteFailure);

		assertEquals(PresetLifecycle.Result.SUCCESS, deleteResult.get());
		assertFalse(source.exists());
		assertNull(new PresetLinkage(preferences, midlet).getOrigin());
		assertFalse(new PresetLinkage(preferences, midlet).isLinked());
	}

	@Test
	public void deadProcessReadyStateStillRecoversWhenNoLiveHolderExists() throws Exception {
		File target = tempDir("dead-process-ready");
		writeConfig(target, 176);
		writeLayout(target, 1);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);
		createLiveRollback(target, oldConfig, oldLayout, false);
		writeConfig(target, 999);
		writeLayout(target, 6);

		ProfilesManager.ProfileInfo info =
				ProfilesManager.inspectProfile(new Profile("K800i"), target);

		assertEquals(176, info.config.screenWidth);
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void activePresetWriterBlocksEditorSourceCopyUntilCommit() throws Exception {
		File source = tempDir("editor-live-source");
		File draft = tempDir("editor-live-draft");
		writeConfig(source, 176);
		writeLayout(source, 1);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		CountDownLatch writerReady = new CountDownLatch(1);
		CountDownLatch finishWriter = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> editorFailure = new AtomicReference<>();

		Thread writer = thread("editor-source-writer", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				createLiveRollback(source, oldConfig, oldLayout, false);
				writerReady.countDown();
				if (!finishWriter.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to finish preset writer");
				}
				writeConfig(source, 360);
				writeLayout(source, 2);
				commitLiveTransaction(source);
			}
		});
		writer.start();
		assertTrue(writerReady.await(5, TimeUnit.SECONDS));

		Thread editor = thread("preset-editor-copy", editorFailure,
				() -> ProfilesManager.copyPresetSourceForEdit(source, draft));
		editor.start();
		awaitBlocked(editor);

		assertTrue(readyMarker(source).isFile());
		assertFalse(configFile(draft).exists());
		assertFalse(layoutFile(draft).exists());

		finishWriter.countDown();
		join(writer, writerFailure);
		join(editor, editorFailure);

		assertEquals(360, readConfig(draft).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(draft));
	}

	@Test
	public void editorCopyCannotObserveConfigBeforeLaterLayoutPublication() throws Exception {
		File source = tempDir("editor-between-source");
		File draft = tempDir("editor-between-draft");
		writeConfig(source, 176);
		writeLayout(source, 1);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		CountDownLatch configPublished = new CountDownLatch(1);
		CountDownLatch finishWriter = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> editorFailure = new AtomicReference<>();

		Thread writer = thread("editor-mid-publication-writer", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				createLiveRollback(source, oldConfig, oldLayout, false);
				writeConfig(source, 640);
				configPublished.countDown();
				if (!finishWriter.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to finish preset writer");
				}
				writeLayout(source, 5);
				commitLiveTransaction(source);
			}
		});
		writer.start();
		assertTrue(configPublished.await(5, TimeUnit.SECONDS));

		Thread editor = thread("preset-editor-mid-publication", editorFailure,
				() -> ProfilesManager.copyPresetSourceForEdit(source, draft));
		editor.start();
		awaitBlocked(editor);

		assertEquals(640, readConfig(source).screenWidth);
		assertArrayEquals(oldLayout, readLayout(source));
		assertFalse(configFile(draft).exists());
		assertFalse(layoutFile(draft).exists());

		finishWriter.countDown();
		join(writer, writerFailure);
		join(editor, editorFailure);

		assertEquals(640, readConfig(draft).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(draft));
	}

	@Test
	public void editorCopyRecoversDeadProcessReadyBeforeCopying() throws Exception {
		File source = tempDir("editor-dead-process-source");
		File draft = tempDir("editor-dead-process-draft");
		writeConfig(source, 176);
		writeLayout(source, 1);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		createLiveRollback(source, oldConfig, oldLayout, false);
		writeConfig(source, 999);
		writeLayout(source, 6);

		ProfilesManager.copyPresetSourceForEdit(source, draft);

		assertArrayEquals(oldConfig, readConfigBytes(draft));
		assertArrayEquals(oldLayout, readLayout(draft));
		assertFalse(saveRollback(source).exists());
	}

	@Test
	public void editorCopyRecoveryFailureCopiesNothing() throws Exception {
		File source = tempDir("editor-recovery-fail-source");
		File draft = tempDir("editor-recovery-fail-draft");
		writeConfig(source, 999);
		writeLayout(source, 6);
		File rollback = saveRollback(source);
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		assertTrue(readyMarker(source).createNewFile());

		try {
			ProfilesManager.copyPresetSourceForEdit(source, draft);
			fail("Expected source recovery failure");
		} catch (IOException expected) {
			// Recovery failed before any source artifact was copied.
		}

		assertFalse(configFile(draft).exists());
		assertFalse(new File(draft, "config.xml").exists());
		assertFalse(layoutFile(draft).exists());
		assertTrue(saveRollback(source).exists());
	}

	@Test
	public void editorCopyPreservesConfigOnlyPresetWithoutFabricatingLayout() throws Exception {
		File source = tempDir("editor-config-only-source");
		File draft = tempDir("editor-config-only-draft");
		writeConfig(source, 240);
		byte[] config = readConfigBytes(source);

		ProfilesManager.copyPresetSourceForEdit(source, draft);

		assertArrayEquals(config, readConfigBytes(draft));
		assertFalse(new File(draft, "config.xml").exists());
		assertFalse(layoutFile(draft).exists());
	}

	@Test
	public void editorCopyPreservesLegacyConfigBytes() throws Exception {
		File source = tempDir("editor-legacy-source");
		File draft = tempDir("editor-legacy-draft");
		byte[] legacy = new byte[] {0, 1, 2, 3, 42, -1, 10};
		Files.write(new File(source, "config.xml").toPath(), legacy);

		ProfilesManager.copyPresetSourceForEdit(source, draft);

		assertFalse(configFile(draft).exists());
		assertArrayEquals(legacy, Files.readAllBytes(new File(draft, "config.xml").toPath()));
		assertFalse(layoutFile(draft).exists());
	}

	@Test
	public void editorSaveStillPublishesEditedWorkingCopy() throws Exception {
		File source = tempDir("editor-save-source");
		File draft = tempDir("editor-save-draft");
		writeConfig(source, 176);
		writeLayout(source, 1);
		ProfilesManager.copyPresetSourceForEdit(source, draft);

		writeConfig(draft, 480);
		writeLayout(draft, 5);
		ProfilesManager.saveEditedSnapshot(source, draft);

		assertEquals(480, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(draft), readLayout(source));
	}

	@Test
	public void editorDiscardLeavesPresetSourceUnchanged() throws Exception {
		File source = tempDir("editor-discard-source");
		File draft = tempDir("editor-discard-draft");
		writeConfig(source, 176);
		writeLayout(source, 1);
		byte[] originalConfig = readConfigBytes(source);
		byte[] originalLayout = readLayout(source);
		ProfilesManager.copyPresetSourceForEdit(source, draft);

		writeConfig(draft, 480);
		writeLayout(draft, 5);
		deleteTree(draft);

		assertArrayEquals(originalConfig, readConfigBytes(source));
		assertArrayEquals(originalLayout, readLayout(source));
	}

	private static Thread thread(
			String name, AtomicReference<Throwable> failure, ThrowingRunnable action) {
		return new Thread(() -> {
			try {
				action.run();
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, name);
	}

	private static void awaitBlocked(Thread thread) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			if (!thread.isAlive()) break;
			Thread.yield();
		}
		assertEquals("Expected thread to block on preset source monitor",
				Thread.State.BLOCKED, thread.getState());
	}

	private static void join(Thread thread, AtomicReference<Throwable> failure) throws Exception {
		thread.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse("Thread did not finish", thread.isAlive());
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	private static void createLiveRollback(
			File profileDir, byte[] previousConfig, byte[] previousLayout, boolean newProfile)
			throws Exception {
		File rollback = saveRollback(profileDir);
		assertTrue(rollback.mkdir());
		if (previousConfig != null) {
			Files.write(new File(rollback, "config.json").toPath(), previousConfig);
			assertTrue(new File(rollback, "config.json.present").createNewFile());
		}
		if (previousLayout != null) {
			Files.write(new File(rollback, "VirtualKeyboardLayout").toPath(), previousLayout);
			assertTrue(new File(rollback, "VirtualKeyboardLayout.present").createNewFile());
		}
		if (newProfile) {
			assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_NEW_PROFILE_MARKER).createNewFile());
		}
		assertTrue(readyMarker(profileDir).createNewFile());
	}

	private static void commitLiveTransaction(File profileDir) throws Exception {
		assertTrue(readyMarker(profileDir).delete());
		deleteTree(saveRollback(profileDir));
		assertFalse(saveRollback(profileDir).exists());
	}

	private static void deleteTree(File file) throws Exception {
		if (!file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) fail("Unable to enumerate " + file);
			for (File child : children) deleteTree(child);
		}
		assertTrue("Unable to delete " + file, file.delete());
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
		File dir = Files.createTempDirectory("jlmod-preset-source-lock-" + suffix).toFile();
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
		return new File(dir, Config.MIDLET_CONFIG_FILE);
	}

	private static File layoutFile(File dir) {
		return new File(dir, Config.MIDLET_KEY_LAYOUT_FILE);
	}

	private static File saveRollback(File dir) {
		return new File(dir, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR);
	}

	private static File readyMarker(File dir) {
		return new File(saveRollback(dir), ProfilesManager.PRESET_SAVE_READY_MARKER);
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
