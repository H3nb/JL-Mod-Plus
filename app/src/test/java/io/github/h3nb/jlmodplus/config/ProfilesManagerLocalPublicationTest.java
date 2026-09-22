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

public class ProfilesManagerLocalPublicationTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;
	private static final String ROLLBACK_DIR = ".preset-sync.rollback";
	private static final String READY = ".ready";
	private static final String OWNED = ".owned";

	@Test
	public void settingsOnlySuccessLeavesLayoutByteForByteUnchanged() throws Exception {
		File source = tempDir("settings-success-source");
		File target = tempDir("settings-success-target");
		writeConfig(source, 360);
		writeLayout(source, 1);
		writeConfig(target, 176);
		writeLayout(target, 5);
		byte[] oldLayout = readLayout(target);

		ProfilesManager.load(source, target, true, false, null);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(rollback(target).exists());
	}

	@Test
	public void settingsOnlyExceptionRollsBackConfigAndLeavesLayoutUntouched() throws Exception {
		File source = tempDir("settings-failure-source");
		File target = tempDir("settings-failure-target");
		writeConfig(source, 360);
		writeConfig(target, 176);
		writeLayout(target, 5);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);

		try {
			ProfilesManager.load(source, target, true, false,
					artifact -> { throw new IOException("injected after publication"); });
			fail("Expected publication failure");
		} catch (IOException expected) {
			// Deterministic failure after the selected artifact was actually published.
		}

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(rollback(target).exists());
	}

	@Test
	public void settingsOnlyCrashRecoveryRestoresConfigWithoutTouchingLayout() throws Exception {
		File target = tempDir("settings-crash");
		File previous = tempDir("settings-crash-previous");
		writeConfig(target, 999);
		writeLayout(target, 5);
		writeConfig(previous, 176);
		byte[] oldConfig = readConfigBytes(previous);
		byte[] currentLayout = readLayout(target);
		File rollback = createRollback(target);
		markOwned(rollback, "config.json");
		backup(rollback, "config.json", oldConfig);
		// Deliberately leave unrelated layout-looking evidence without an ownership marker.
		writeLayoutFile(new File(rollback, "VirtualKeyboardLayout"), 2);
		assertTrue(new File(rollback, "VirtualKeyboardLayout.present").createNewFile());
		assertTrue(new File(rollback, READY).createNewFile());

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(currentLayout, readLayout(target));
		assertFalse(rollback.exists());
	}

	@Test
	public void layoutOnlySuccessLeavesConfigByteForByteUnchanged() throws Exception {
		File source = tempDir("layout-success-source");
		File target = tempDir("layout-success-target");
		writeLayout(source, 1);
		writeConfig(target, 176);
		writeLayout(target, 5);
		byte[] oldConfig = readConfigBytes(target);
		byte[] sourceLayout = readLayout(source);

		ProfilesManager.load(source, target, false, true, null);

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(sourceLayout, readLayout(target));
		assertFalse(rollback(target).exists());
	}

	@Test
	public void layoutOnlyExceptionRollsBackLayoutAndLeavesConfigUntouched() throws Exception {
		File source = tempDir("layout-failure-source");
		File target = tempDir("layout-failure-target");
		writeLayout(source, 1);
		writeConfig(target, 176);
		writeLayout(target, 5);
		byte[] oldConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(target);

		try {
			ProfilesManager.load(source, target, false, true,
					artifact -> { throw new IOException("injected after publication"); });
			fail("Expected publication failure");
		} catch (IOException expected) {
			// Deterministic failure after the selected artifact was actually published.
		}

		assertArrayEquals(oldConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(rollback(target).exists());
	}

	@Test
	public void layoutOnlyCrashRecoveryRestoresLayoutWithoutTouchingConfig() throws Exception {
		File target = tempDir("layout-crash");
		File previous = tempDir("layout-crash-previous");
		writeConfig(target, 640);
		writeLayout(target, 5);
		writeLayout(previous, 2);
		byte[] currentConfig = readConfigBytes(target);
		byte[] oldLayout = readLayout(previous);
		File rollback = createRollback(target);
		markOwned(rollback, "VirtualKeyboardLayout");
		backup(rollback, "VirtualKeyboardLayout", oldLayout);
		// Deliberately leave unrelated config-looking evidence without an ownership marker.
		Files.write(new File(rollback, "config.json").toPath(),
				"{\"screenWidth\":111}".getBytes(StandardCharsets.UTF_8));
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		assertTrue(new File(rollback, READY).createNewFile());

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(currentConfig, readConfigBytes(target));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(rollback.exists());
	}

	@Test
	public void layoutOnlyRecoveryRemovesPublishedLayoutWhenOriginalWasAbsent() throws Exception {
		File target = tempDir("layout-originally-absent");
		writeConfig(target, 176);
		writeLayout(target, 5);
		byte[] currentConfig = readConfigBytes(target);
		File rollback = createRollback(target);
		markOwned(rollback, "VirtualKeyboardLayout");
		assertTrue(new File(rollback, READY).createNewFile());

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(currentConfig, readConfigBytes(target));
		assertFalse(layoutFile(target).exists());
		assertFalse(rollback.exists());
	}

	@Test
	public void disarmedSelectedArtifactRollbackNeverChangesEitherDestination() throws Exception {
		File target = tempDir("partial-disarmed");
		writeConfig(target, 640);
		writeLayout(target, 5);
		byte[] currentConfig = readConfigBytes(target);
		byte[] currentLayout = readLayout(target);
		File rollback = createRollback(target);
		markOwned(rollback, "config.json");
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		// No .ready and backup bytes intentionally absent: this is stale cleanup evidence.

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(currentConfig, readConfigBytes(target));
		assertArrayEquals(currentLayout, readLayout(target));
		assertFalse(rollback.exists());
	}

	private static File createRollback(File target) {
		File rollback = rollback(target);
		assertTrue(rollback.mkdir());
		return rollback;
	}

	private static void markOwned(File rollback, String name) throws IOException {
		assertTrue(new File(rollback, name + OWNED).createNewFile());
	}

	private static void backup(File rollback, String name, byte[] bytes) throws IOException {
		Files.write(new File(rollback, name).toPath(), bytes);
		assertTrue(new File(rollback, name + ".present").createNewFile());
	}

	private static File tempDir(String suffix) throws IOException {
		File dir = Files.createTempDirectory("jlmod-local-publication-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static void writeConfig(File dir, int screenWidth) throws IOException {
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = screenWidth;
		model.vkType = 1;
		model.systemProperties = "";
		Files.write(configFile(dir).toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static ProfileModel readConfig(File dir) throws IOException {
		return new Gson().fromJson(
				new String(readConfigBytes(dir), StandardCharsets.UTF_8), ProfileModel.class);
	}

	private static byte[] readConfigBytes(File dir) throws IOException {
		return Files.readAllBytes(configFile(dir).toPath());
	}

	private static void writeLayout(File dir, int type) throws IOException {
		writeLayoutFile(layoutFile(dir), type);
	}

	private static void writeLayoutFile(File file, int type) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
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

	private static File configFile(File dir) {
		return new File(dir, Config.MIDLET_CONFIG_FILE);
	}

	private static File layoutFile(File dir) {
		return new File(dir, Config.MIDLET_KEY_LAYOUT_FILE);
	}

	private static File rollback(File dir) {
		return new File(dir, ROLLBACK_DIR);
	}
}
