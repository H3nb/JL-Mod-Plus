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

import javax.microedition.lcdui.keyboard.VirtualKeyboard;

public class ProfilesManagerSnapshotSyncTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;
	private static final String LAYOUT_NEW_SUFFIX = ".new";
	private static final String LAYOUT_BACKUP_SUFFIX = ".bak";
	private static final String SYNC_ROLLBACK_DIR = ".preset-sync.rollback";
	private static final String SYNC_READY_MARKER = ".ready";

	@Test
	public void configAndValidLayoutAreMirrored() throws Exception {
		File source = tempDir("source-both");
		File target = tempDir("target-both");
		writeConfig(source, 320, 1);
		writeLayout(source, 1);
		writeConfig(target, 111, 1);
		writeLayout(target, 2);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(320, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
	}

	@Test
	public void configOnlySnapshotRemovesStaleDestinationLayout() throws Exception {
		File source = tempDir("source-config-only");
		File target = tempDir("target-config-only");
		writeConfig(source, 360, 1);
		writeConfig(target, 120, 1);
		writeLayout(target, 2);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(360, readConfig(target).screenWidth);
		assertFalse(layoutFile(target).exists());
	}

	@Test
	public void configOnlySnapshotConsumesBackupOnlyLayoutBeforeRemovingFamily() throws Exception {
		File source = tempDir("source-no-layout-backup-only");
		File target = tempDir("target-backup-only");
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		File backup = layoutSidecar(target, LAYOUT_BACKUP_SUFFIX);
		assertTrue(layoutFile(target).renameTo(backup));

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(360, readConfig(target).screenWidth);
		assertLayoutFamilyAbsent(target);
	}

	@Test
	public void configOnlySnapshotRemovesCompleteStaleLayoutFamily() throws Exception {
		File source = tempDir("source-no-layout-family");
		File target = tempDir("target-layout-family");
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		writeLayoutFile(layoutSidecar(target, LAYOUT_NEW_SUFFIX), 3);
		writeLayoutFile(layoutSidecar(target, LAYOUT_BACKUP_SUFFIX), 4);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(360, readConfig(target).screenWidth);
		assertLayoutFamilyAbsent(target);
	}

	@Test
	public void presetLayoutBecomesOnlyRecoverableLayoutWhenDestinationHasSidecars() throws Exception {
		File source = tempDir("source-layout-sidecars");
		File target = tempDir("target-layout-sidecars");
		writeConfig(source, 360, 1);
		writeLayout(source, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		writeLayoutFile(layoutSidecar(target, LAYOUT_NEW_SUFFIX), 3);
		writeLayoutFile(layoutSidecar(target, LAYOUT_BACKUP_SUFFIX), 4);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertFalse(layoutSidecar(target, LAYOUT_NEW_SUFFIX).exists());
		assertFalse(layoutSidecar(target, LAYOUT_BACKUP_SUFFIX).exists());
	}

	@Test
	public void readyInterruptedSyncRecoversOldSnapshotThenAppliesCurrentPreset() throws Exception {
		File source = tempDir("source-after-interruption");
		File target = tempDir("target-after-interruption");
		writeConfig(source, 640, 1);
		writeLayout(source, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] oldLayout = readLayout(target);
		createInterruptedSyncRollback(target, oldConfig, oldLayout, true);

		// Simulate process death after config publication but before layout publication.
		writeConfig(target, 999, 1);

		ProfilesManager.syncSnapshot(source, target);

		assertEquals(640, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertFalse(syncRollbackDir(target).exists());
	}

	@Test
	public void rollbackWithoutReadyMarkerDoesNotReplaceValidDestination() throws Exception {
		File source = tempDir("source-invalid-before-ready");
		File target = tempDir("target-before-ready");
		File unrelatedBackup = tempDir("unpublished-backup");
		writeConfig(target, 222, 1);
		writeLayout(target, 2);
		byte[] currentConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] currentLayout = readLayout(target);
		writeConfig(unrelatedBackup, 111, 1);
		writeLayout(unrelatedBackup, 1);
		createInterruptedSyncRollback(
				target,
				Files.readAllBytes(configFile(unrelatedBackup).toPath()),
				readLayout(unrelatedBackup),
				false);
		Files.write(configFile(source).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));

		expectSyncFailure(source, target);

		assertArrayEquals(currentConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(currentLayout, readLayout(target));
		assertFalse(syncRollbackDir(target).exists());
	}

	@Test
	public void readyInterruptedSyncRecoversOldSnapshotBeforeRejectingMalformedSource() throws Exception {
		File source = tempDir("source-invalid-after-ready");
		File target = tempDir("target-ready-invalid-source");
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] oldLayout = readLayout(target);
		createInterruptedSyncRollback(target, oldConfig, oldLayout, true);

		// Simulate one already-published destination artifact from the interrupted sync.
		writeConfig(target, 999, 1);
		Files.write(configFile(source).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));

		expectSyncFailure(source, target);

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(syncRollbackDir(target).exists());
	}

	@Test
	public void corruptSourceLayoutLeavesDestinationUnchanged() throws Exception {
		File source = tempDir("source-corrupt-layout");
		File target = tempDir("target-corrupt-layout");
		writeConfig(source, 360, 1);
		Files.write(layoutFile(source).toPath(), "broken".getBytes(StandardCharsets.UTF_8));
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] previousConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] previousLayout = readLayout(target);

		expectSyncFailure(source, target);

		assertArrayEquals(previousConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(previousLayout, readLayout(target));
	}

	@Test
	public void destinationCommitPreflightFailureLeavesExistingStateUntouched() throws Exception {
		File source = tempDir("source-commit-preflight");
		File target = tempDir("target-commit-preflight");
		writeConfig(source, 360, 1);
		writeLayout(source, 1);
		writeConfig(target, 176, 1);
		byte[] previousConfig = Files.readAllBytes(configFile(target).toPath());
		File blockedLayout = layoutFile(target);
		assertTrue(blockedLayout.mkdir());
		File sentinel = new File(blockedLayout, "keep");
		Files.write(sentinel.toPath(), new byte[] {7});

		expectSyncFailure(source, target);

		assertArrayEquals(previousConfig, Files.readAllBytes(configFile(target).toPath()));
		assertTrue(blockedLayout.isDirectory());
		assertTrue(sentinel.isFile());
	}

	@Test
	public void customConfigWithoutUsableLayoutIsRejected() throws Exception {
		File source = tempDir("source-custom-missing-layout");
		File target = tempDir("target-custom-missing-layout");
		writeConfig(source, 360, VirtualKeyboard.TYPE_CUSTOM);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] previousConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] previousLayout = readLayout(target);

		expectSyncFailure(source, target);

		assertArrayEquals(previousConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(previousLayout, readLayout(target));
	}

	@Test
	public void malformedSourceConfigLeavesDestinationUnchanged() throws Exception {
		File source = tempDir("source-bad-config");
		File target = tempDir("target-bad-config");
		Files.write(configFile(source).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		byte[] previousConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] previousLayout = readLayout(target);

		expectSyncFailure(source, target);

		assertArrayEquals(previousConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(previousLayout, readLayout(target));
	}

	@Test
	public void armedRecoveryDisarmsBeforeCleanupAndIsIdempotent() throws Exception {
		File target = tempDir("direct-armed-idempotent");
		File previous = tempDir("direct-armed-previous");
		writeConfig(target, 999, 1);
		writeLayout(target, 5);
		writeConfig(previous, 176, 1);
		writeLayout(previous, 2);
		byte[] oldConfig = Files.readAllBytes(configFile(previous).toPath());
		byte[] oldLayout = readLayout(previous);
		createInterruptedSyncRollback(target, oldConfig, oldLayout, true);

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(oldLayout, readLayout(target));
		assertFalse(syncRollbackDir(target).exists());

		byte[] restoredConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] restoredLayout = readLayout(target);
		ProfilesManager.recoverInterruptedSnapshotSync(target);
		assertArrayEquals(restoredConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(restoredLayout, readLayout(target));
	}

	@Test
	public void disarmedPartiallyCleanedRollbackNeverChangesDestination() throws Exception {
		File target = tempDir("direct-disarmed-partial");
		writeConfig(target, 360, 1);
		writeLayout(target, 4);
		byte[] currentConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] currentLayout = readLayout(target);
		File rollback = syncRollbackDir(target);
		assertTrue(rollback.mkdir());
		// Simulate cleanup after disarm having already removed some backup bytes.
		assertTrue(new File(rollback, "config.json.present").createNewFile());

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(currentConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(currentLayout, readLayout(target));
		assertFalse(rollback.exists());
	}

	@Test
	public void armedConfigOnlyOldSnapshotRecoversWithoutLayout() throws Exception {
		File target = tempDir("direct-config-only-old");
		File previous = tempDir("direct-config-only-previous");
		writeConfig(previous, 176, 1);
		byte[] oldConfig = Files.readAllBytes(configFile(previous).toPath());
		createInterruptedSyncRollback(target, oldConfig, null, true);
		writeConfig(target, 999, 1);
		writeLayout(target, 5);

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertArrayEquals(oldConfig, Files.readAllBytes(configFile(target).toPath()));
		assertFalse(layoutFile(target).exists());
		assertFalse(syncRollbackDir(target).exists());
	}

	private static void expectSyncFailure(File source, File target) throws Exception {
		try {
			ProfilesManager.syncSnapshot(source, target);
			fail("Expected preset snapshot sync to fail");
		} catch (IOException expected) {
			// Expected.
		}
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
		String json = new String(Files.readAllBytes(configFile(dir).toPath()),
				StandardCharsets.UTF_8);
		return new Gson().fromJson(json, ProfileModel.class);
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

	private static void assertLayoutFamilyAbsent(File dir) {
		assertFalse(layoutFile(dir).exists());
		assertFalse(layoutSidecar(dir, LAYOUT_NEW_SUFFIX).exists());
		assertFalse(layoutSidecar(dir, LAYOUT_BACKUP_SUFFIX).exists());
	}

	private static byte[] readLayout(File dir) throws IOException {
		return Files.readAllBytes(layoutFile(dir).toPath());
	}

	private static File configFile(File dir) {
		return new File(dir, "config.json");
	}

	private static File layoutFile(File dir) {
		return new File(dir, "VirtualKeyboardLayout");
	}

	private static File layoutSidecar(File dir, String suffix) {
		return new File(layoutFile(dir).getPath() + suffix);
	}

	private static File syncRollbackDir(File dir) {
		return new File(dir, SYNC_ROLLBACK_DIR);
	}
}
