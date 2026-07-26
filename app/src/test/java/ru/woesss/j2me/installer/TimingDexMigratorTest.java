/*
 * Copyright 2026 H3NB
 *
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

package ru.woesss.j2me.installer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.h3nb.jlmodplus.config.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingDexMigratorTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void currentVersionConfSkipsMigration() throws Exception {
		File appDir = temporaryFolder.newFolder("current-version");
		Files.write(new File(appDir, "converted.zip").toPath(), new byte[]{1});
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);

		assertFalse(TimingDexMigrator.needsMigration(appDir));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION - 1);
		assertTrue(TimingDexMigrator.needsMigration(appDir));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION + 1);
		assertFalse(TimingDexMigrator.needsMigration(appDir));
	}

	@Test
	public void currentLegacyDexAlsoSkipsMigration() throws Exception {
		File appDir = temporaryFolder.newFolder("current-legacy-dex");
		Files.write(new File(appDir, "converted.dex").toPath(), new byte[]{1});
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);

		assertFalse(TimingDexMigrator.needsMigration(appDir));
	}

	@Test
	public void interruptedMigrationRestoresArchiveAndPreviousConf() throws Exception {
		File appDir = temporaryFolder.newFolder("restore-conf");
		Files.write(new File(appDir, "converted.zip").toPath(), "new".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(appDir, ".converted.timing.backup").toPath(), "old".getBytes(StandardCharsets.US_ASCII));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);
		writeManifest(new File(appDir, ".converted.dex.conf.backup"), Config.MIDLET_DEX_VERSION - 1);
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertEquals("old", read(new File(appDir, "converted.zip")));
		assertEquals(Integer.toString(Config.MIDLET_DEX_VERSION - 1), readVersion(appDir));
		assertFalse(new File(appDir, ".converted.timing.pending").exists());
	}

	@Test
	public void successfulLaunchCommitsActivatedArchiveAndDeletesRecoveryFiles() throws Exception {
		File appDir = temporaryFolder.newFolder("commit-launch");
		Files.write(new File(appDir, "converted.zip").toPath(), "new".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(appDir, ".converted.timing.backup").toPath(), "old".getBytes(StandardCharsets.US_ASCII));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);
		writeManifest(new File(appDir, ".converted.dex.conf.backup"), Config.MIDLET_DEX_VERSION - 1);
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");
		Files.write(new File(appDir, "res.jar").toPath(), new byte[]{2});
		Files.write(new File(appDir, "res.jad").toPath(), new byte[]{3});
		Files.write(new File(appDir, "icon.png").toPath(), new byte[]{4});
		File staleDirectory = new File(appDir, "res");
		assertTrue(staleDirectory.mkdirs());
		Files.write(new File(staleDirectory, "old.bin").toPath(), new byte[]{5});
		Files.write(new File(appDir, "converted.dex").toPath(), new byte[]{6});

		TimingDexMigrator.completeLaunch(appDir, true);

		assertEquals("new", read(new File(appDir, "converted.zip")));
		assertEquals(Integer.toString(Config.MIDLET_DEX_VERSION), readVersion(appDir));
		assertFalse(new File(appDir, ".converted.timing.backup").exists());
		assertFalse(new File(appDir, ".converted.dex.conf.backup").exists());
		assertFalse(new File(appDir, ".converted.timing.pending").exists());
		assertFalse(new File(appDir, Config.MIDLET_TIMING_VERSION_FILE).exists());
		assertTrue(new File(appDir, "res.jar").isFile());
		assertTrue(new File(appDir, "res.jad").isFile());
		assertTrue(new File(appDir, "icon.png").isFile());
		assertFalse(staleDirectory.exists());
		assertFalse(new File(appDir, "converted.dex").exists());
	}

	@Test
	public void recoveryDoesNotDeleteArchiveBeforeBackupMetadataIsReady() throws Exception {
		File appDir = temporaryFolder.newFolder("restore-before-conf-backup");
		Files.write(new File(appDir, ".converted.timing.backup").toPath(), "old".getBytes(StandardCharsets.US_ASCII));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertEquals("old", read(new File(appDir, "converted.zip")));
		assertEquals(Integer.toString(Config.MIDLET_DEX_VERSION), readVersion(appDir));
	}

	@Test
	public void failedFirstConversionRemovesUncommittedArchive() throws Exception {
		File appDir = temporaryFolder.newFolder("failed-first-conversion");
		Files.write(new File(appDir, "converted.zip").toPath(),
				"new".getBytes(StandardCharsets.US_ASCII));
		writeManifest(appDir, Config.MIDLET_DEX_VERSION);
		write(new File(appDir, ".converted.timing.pending"), "");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertFalse(new File(appDir, "converted.zip").exists());
		assertFalse(new File(appDir, ".converted.timing.pending").exists());
	}

	private static void writeManifest(File directory, int version) throws Exception {
		File file = directory.isDirectory()
				? new File(directory, Config.MIDLET_MANIFEST_FILE)
				: directory;
		file.getParentFile().mkdirs();
		String text = "MIDlet-Name: Test\r\n"
				+ "MIDlet-Vendor: Test\r\n"
				+ "MIDlet-Version: 1\r\n"
				+ "MIDlet-1: Test,,example.Main\r\n"
				+ Config.MIDLET_DEX_VERSION_ATTRIBUTE + ": " + version + "\r\n";
		Files.write(file.toPath(), text.getBytes(StandardCharsets.US_ASCII));
	}

	private static String readVersion(File directory) throws Exception {
		String text = read(new File(directory, Config.MIDLET_MANIFEST_FILE));
		String prefix = Config.MIDLET_DEX_VERSION_ATTRIBUTE + ": ";
		for (String line : text.split("\\r?\\n")) {
			if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
		}
		return null;
	}

	private static void write(File file, String value) throws Exception {
		Files.write(file.toPath(), value.getBytes(StandardCharsets.US_ASCII));
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
	}
}
