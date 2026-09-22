/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;

public class ProfilesManagerCompleteSnapshotSaveTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;

	@Test
	public void saveNewCompleteSnapshotCopiesConfigAndLayout() throws Exception {
		File root = tempDir("new-combined-root");
		File local = tempDir("new-combined-local");
		writeConfig(local, 360, 1);
		writeLayout(local, 2);

		ProfilesManager.saveNewCompleteSnapshot(root, "K800i", local);

		File target = new File(root, "K800i");
		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(local), readLayout(target));
		assertFalse(saveRollback(target).exists());
	}

	@Test
	public void saveNewCompleteSnapshotPreservesConfigOnlyAbsence() throws Exception {
		File root = tempDir("new-config-root");
		File local = tempDir("new-config-local");
		writeConfig(local, 240, 1);

		ProfilesManager.saveNewCompleteSnapshot(root, "N95", local);

		File target = new File(root, "N95");
		assertEquals(240, readConfig(target).screenWidth);
		assertFalse(layoutFile(target).exists());
	}

	@Test
	public void recoverableLocalLayoutBackupIsNormalizedAndCaptured() throws Exception {
		File root = tempDir("bak-root");
		File local = tempDir("bak-local");
		writeConfig(local, 360, 1);
		writeLayout(local, 4);
		byte[] layout = readLayout(local);
		File backup = new File(local, Config.MIDLET_KEY_LAYOUT_FILE + ".bak");
		assertTrue(layoutFile(local).renameTo(backup));

		ProfilesManager.saveNewCompleteSnapshot(root, "Backup", local);

		assertTrue(layoutFile(local).isFile());
		assertFalse(backup.exists());
		assertArrayEquals(layout, readLayout(new File(root, "Backup")));
	}

	@Test
	public void abandonedLocalNewSidecarIsDiscardedAndNotCaptured() throws Exception {
		File root = tempDir("new-sidecar-root");
		File local = tempDir("new-sidecar-local");
		writeConfig(local, 240, 1);
		File temporary = new File(local, Config.MIDLET_KEY_LAYOUT_FILE + ".new");
		Files.write(temporary.toPath(), "abandoned".getBytes(StandardCharsets.UTF_8));

		ProfilesManager.saveNewCompleteSnapshot(root, "ConfigOnly", local);

		assertFalse(temporary.exists());
		assertFalse(layoutFile(new File(root, "ConfigOnly")).exists());
	}

	@Test
	public void customWithoutUsableLayoutRejectsCreateWithoutTarget() throws Exception {
		File root = tempDir("custom-missing-root");
		File local = tempDir("custom-missing-local");
		writeConfig(local, 360, VirtualKeyboard.TYPE_CUSTOM);

		try {
			ProfilesManager.saveNewCompleteSnapshot(root, "Custom", local);
			fail("Expected incomplete custom snapshot to fail");
		} catch (IOException expected) {
		}

		assertFalse(new File(root, "Custom").exists());
	}

	@Test
	public void createRefusesExistingTargetWithoutOverwrite() throws Exception {
		File root = tempDir("existing-root");
		File target = new File(root, "K800i");
		assertTrue(target.mkdir());
		writeConfig(target, 176, 1);
		byte[] oldConfig = readConfigBytes(target);
		File local = tempDir("existing-local");
		writeConfig(local, 360, 1);

		try {
			ProfilesManager.saveNewCompleteSnapshot(root, "K800i", local);
			fail("Expected occupied name to fail");
		} catch (IOException expected) {
		}

		assertArrayEquals(oldConfig, readConfigBytes(target));
	}

	@Test
	public void createRefusesCaseInsensitiveDuplicate() throws Exception {
		File root = tempDir("case-root");
		File target = new File(root, "K800i");
		assertTrue(target.mkdir());
		writeConfig(target, 176, 1);
		File local = tempDir("case-local");
		writeConfig(local, 360, 1);

		try {
			ProfilesManager.saveNewCompleteSnapshot(root, "k800I", local);
			fail("Expected case-insensitive duplicate to fail");
		} catch (IOException expected) {
		}

		assertEquals(176, readConfig(target).screenWidth);
		assertFalse(new File(root, "k800I").exists());
	}

	@Test
	public void createRechecksOccupancyAfterWaitingForSourceMonitor() throws Exception {
		File root = tempDir("race-root");
		File local = tempDir("race-local");
		writeConfig(local, 360, 1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread creator;
		synchronized (ProfilesManager.presetSourceLock()) {
			creator = new Thread(() -> {
				try {
					ProfilesManager.saveNewCompleteSnapshot(root, "N95", local);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}, "preset-create-race");
			creator.start();
			awaitBlocked(creator);

			File occupied = new File(root, "N95");
			assertTrue(occupied.mkdir());
			writeConfig(occupied, 176, 1);
		}
		join(creator);

		assertTrue(failure.get() instanceof IOException);
		assertEquals(176, readConfig(new File(root, "N95")).screenWidth);
	}

	@Test
	public void updateExistingExactlyReplacesConfigAndLayout() throws Exception {
		File target = tempDir("update-combined-target");
		File local = tempDir("update-combined-local");
		writeConfig(target, 176, 1);
		writeLayout(target, 1);
		writeConfig(local, 640, 1);
		writeLayout(local, 5);

		ProfilesManager.updateCompleteSnapshot(target, local);

		assertEquals(640, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(local), readLayout(target));
	}

	@Test
	public void updateConfigOnlyRemovesStaleLayoutAndLegacyConfig() throws Exception {
		File target = tempDir("update-config-only-target");
		File local = tempDir("update-config-only-local");
		writeConfig(target, 176, 1);
		writeLayout(target, 1);
		Files.write(new File(target, "config.xml").toPath(), "<legacy/>".getBytes(StandardCharsets.UTF_8));
		writeConfig(local, 480, 1);

		ProfilesManager.updateCompleteSnapshot(target, local);

		assertEquals(480, readConfig(target).screenWidth);
		assertFalse(layoutFile(target).exists());
		assertFalse(new File(target, "config.xml").exists());
	}

	@Test
	public void updateMissingTargetFailsWithoutRecreatingIt() throws Exception {
		File parent = tempDir("missing-parent");
		File target = new File(parent, "K800i");
		File local = tempDir("missing-local");
		writeConfig(local, 360, 1);

		try {
			ProfilesManager.updateCompleteSnapshot(target, local);
			fail("Expected missing update target to fail");
		} catch (IOException expected) {
		}

		assertFalse(target.exists());
	}

	@Test
	public void updateCanRepairMalformedExistingSource() throws Exception {
		File target = tempDir("repair-target");
		File local = tempDir("repair-local");
		Files.write(configFile(target).toPath(), "malformed".getBytes(StandardCharsets.UTF_8));
		Files.write(layoutFile(target).toPath(), "malformed-layout".getBytes(StandardCharsets.UTF_8));
		writeConfig(local, 360, 1);
		writeLayout(local, 3);

		ProfilesManager.updateCompleteSnapshot(target, local);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(local), readLayout(target));
	}

	@Test
	public void updateDoesNotRecreateNameRenamedBeforeLockAcquisition() throws Exception {
		File root = tempDir("rename-race-root");
		File target = new File(root, "K800i");
		File renamed = new File(root, "Sony K800i");
		assertTrue(target.mkdir());
		writeConfig(target, 176, 1);
		File local = tempDir("rename-race-local");
		writeConfig(local, 360, 1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread updater;
		synchronized (ProfilesManager.presetSourceLock()) {
			updater = new Thread(() -> {
				try {
					ProfilesManager.updateCompleteSnapshot(target, local);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}, "preset-update-race");
			updater.start();
			awaitBlocked(updater);
			assertTrue(target.renameTo(renamed));
		}
		join(updater);

		assertTrue(failure.get() instanceof IOException);
		assertFalse(target.exists());
		assertEquals(176, readConfig(renamed).screenWidth);
	}

	private static void awaitBlocked(Thread thread) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			if (!thread.isAlive()) break;
			Thread.yield();
		}
		assertEquals(Thread.State.BLOCKED, thread.getState());
	}

	private static void join(Thread thread) throws Exception {
		thread.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse("Thread did not finish", thread.isAlive());
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-complete-save-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static void writeConfig(File dir, int width, int vkType) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = width;
		model.screenHeight = 320;
		model.vkType = vkType;
		model.systemProperties = "";
		Files.write(configFile(dir).toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static ProfileModel readConfig(File dir) throws Exception {
		return new Gson().fromJson(
				new String(readConfigBytes(dir), StandardCharsets.UTF_8), ProfileModel.class);
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
}
