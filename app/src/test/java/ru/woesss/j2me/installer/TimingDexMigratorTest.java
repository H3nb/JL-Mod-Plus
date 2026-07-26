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
	public void currentVersionMarkerSkipsMigration() throws Exception {
		File appDir = temporaryFolder.newFolder("current-version");

		assertTrue(TimingDexMigrator.needsMigration(appDir));
		write(new File(appDir, "converted.timing.version"),
				Integer.toString(Config.MIDLET_TIMING_TRANSFORM_VERSION));
		assertFalse(TimingDexMigrator.needsMigration(appDir));
		write(new File(appDir, "converted.timing.version"), "3");
		assertTrue(TimingDexMigrator.needsMigration(appDir));
	}

	@Test
	public void interruptedMigrationRestoresArchiveAndPreviousVersion()
			throws Exception {
		File appDir = temporaryFolder.newFolder("restore-version");
		byte[] previousArchive = "old executable".getBytes(StandardCharsets.US_ASCII);
		Files.write(new File(appDir, "converted.zip").toPath(),
				"new executable".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(appDir, ".converted.timing.backup").toPath(),
				previousArchive);
		String currentVersion =
				Integer.toString(Config.MIDLET_TIMING_TRANSFORM_VERSION);
		write(new File(appDir, "converted.timing.version"), currentVersion);
		write(new File(appDir, ".converted.timing.version.backup"), "3");
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertArrayEquals(previousArchive,
				Files.readAllBytes(new File(appDir, "converted.zip").toPath()));
		assertEquals("3",
				read(new File(appDir, "converted.timing.version")));
		assertFalse(new File(appDir, ".converted.timing.pending").exists());
	}

	@Test
	public void interruptedMigrationRestoresAbsenceOfPreviousVersion()
			throws Exception {
		File appDir = temporaryFolder.newFolder("restore-no-version");
		byte[] previousArchive = "old executable".getBytes(StandardCharsets.US_ASCII);
		Files.write(new File(appDir, "converted.zip").toPath(),
				"new executable".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(appDir, ".converted.timing.backup").toPath(),
				previousArchive);
		write(new File(appDir, "converted.timing.version"), "4");
		write(new File(appDir, ".converted.timing.version.absent"), "");
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertArrayEquals(previousArchive,
				Files.readAllBytes(new File(appDir, "converted.zip").toPath()));
		assertFalse(new File(appDir, "converted.timing.version").exists());
		assertFalse(new File(appDir, ".converted.timing.version.absent").exists());
	}

	@Test
	public void recoveryDoesNotDeleteMarkerBeforeItsBackupWasCreated()
			throws Exception {
		File appDir = temporaryFolder.newFolder("restore-before-version-backup");
		byte[] previousArchive = "old executable".getBytes(StandardCharsets.US_ASCII);
		Files.write(new File(appDir, ".converted.timing.backup").toPath(),
				previousArchive);
		write(new File(appDir, "converted.timing.version"), "3");
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.recoverInterruptedMigration(appDir);

		assertArrayEquals(previousArchive,
				Files.readAllBytes(new File(appDir, "converted.zip").toPath()));
		assertEquals("3",
				read(new File(appDir, "converted.timing.version")));
	}

	@Test
	public void successfulLaunchCommitsActivatedArchiveAndDeletesRecoveryFiles()
			throws Exception {
		File appDir = temporaryFolder.newFolder("commit-launch");
		String currentVersion =
				Integer.toString(Config.MIDLET_TIMING_TRANSFORM_VERSION);
		byte[] activatedArchive = "new executable".getBytes(StandardCharsets.US_ASCII);
		Files.write(new File(appDir, "converted.zip").toPath(), activatedArchive);
		Files.write(new File(appDir, ".converted.timing.backup").toPath(),
				"old executable".getBytes(StandardCharsets.US_ASCII));
		write(new File(appDir, "converted.timing.version"), currentVersion);
		write(new File(appDir, ".converted.timing.version.backup"), "3");
		write(new File(appDir, ".converted.timing.pending"), "converted.zip");

		TimingDexMigrator.completeLaunch(appDir, true);

		assertArrayEquals(activatedArchive,
				Files.readAllBytes(new File(appDir, "converted.zip").toPath()));
		assertEquals(currentVersion, read(new File(appDir, "converted.timing.version")));
		assertFalse(new File(appDir, ".converted.timing.backup").exists());
		assertFalse(new File(appDir, ".converted.timing.version.backup").exists());
		assertFalse(new File(appDir, ".converted.timing.pending").exists());
	}

	private static void write(File file, String value) throws Exception {
		Files.write(file.toPath(), value.getBytes(StandardCharsets.US_ASCII));
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
	}
}
