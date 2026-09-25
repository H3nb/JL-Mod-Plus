/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;

import io.github.h3nb.jlmodplus.runtime.RuntimeStorageLease;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppInstallerStorageKeyTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void reservedLegacyStagingNameIsSkipped() throws Exception {
		File converted = temporaryFolder.newFolder("converted");

		File selected = AppInstaller.chooseTargetDirectory(converted, ".tmp");

		assertEquals(".tmp_1", selected.getName());
	}

	@Test
	public void reservedFallbackStillAvoidsExistingSuffixes() throws Exception {
		File converted = temporaryFolder.newFolder("converted-existing");
		assertTrue(new File(converted, ".tmp_1").mkdir());

		File selected = AppInstaller.chooseTargetDirectory(converted, ".tmp");

		assertEquals(".tmp_2", selected.getName());
	}

	@Test
	public void indexedKeyIsSkippedEvenWhenFolderIsMissing() throws Exception {
		File converted = temporaryFolder.newFolder("converted-indexed");

		File selected = AppInstaller.chooseTargetDirectory(
				converted,
				"Game",
				Collections.singleton("Game"));

		assertEquals("Game_1", selected.getName());
	}

	@Test
	public void orphanConfigReservesStorageKey() throws Exception {
		File root = temporaryFolder.newFolder("orphan-config-root");
		File converted = new File(root, "converted");
		File configs = new File(root, "configs");
		assertTrue(converted.mkdir());
		assertTrue(configs.mkdir());
		assertTrue(new File(configs, "Game").mkdir());

		File selected = AppInstaller.chooseTargetDirectory(
				converted, "Game", Collections.emptySet());

		assertEquals("Game_1", selected.getName());
	}

	@Test
	public void orphanSaveDataReservesStorageKey() throws Exception {
		File root = temporaryFolder.newFolder("orphan-data-root");
		File converted = new File(root, "converted");
		File data = new File(root, "data");
		assertTrue(converted.mkdir());
		assertTrue(data.mkdir());
		assertTrue(new File(data, "Game").mkdir());

		File selected = AppInstaller.chooseTargetDirectory(
				converted, "Game", Collections.emptySet());

		assertEquals("Game_1", selected.getName());
	}

	@Test
	public void noSideStateKeepsNormalUnsuffixedCandidate() throws Exception {
		File root = temporaryFolder.newFolder("fresh-root");
		File converted = new File(root, "converted");
		assertTrue(converted.mkdir());

		File selected = AppInstaller.chooseTargetDirectory(
				converted, "Game", Collections.emptySet());

		assertEquals("Game", selected.getName());
	}

	@Test
	public void liveRuntimeReservesFreshNameAndNormalSuffixSelectionContinues() throws Exception {
		File filesDir = temporaryFolder.newFolder("lease-files");
		File root = temporaryFolder.newFolder("lease-work");
		File converted = new File(root, "converted");
		assertTrue(converted.mkdir());
		File configs = new File(root, "configs");
		assertTrue(configs.mkdir());
		assertTrue(new File(configs, "Bounce_1").mkdir());
		File oldPath = new File(converted, "Bounce");
		try (RuntimeStorageLease ignored = RuntimeStorageLease.acquire(filesDir, oldPath)) {
			assertEquals("Bounce_2", AppInstaller.chooseTargetDirectory(
					converted, "Bounce", Collections.emptySet(), filesDir).getName());
			File oldData = new File(root, "data/Bounce/save.rms");
			assertTrue(oldData.getParentFile().mkdirs());
			write(oldData, new byte[]{1, 2, 3});
			assertFalse(new File(root, "data/Bounce_2/save.rms").exists());
		}
		assertEquals("Bounce_2", AppInstaller.chooseTargetDirectory(
				converted, "Bounce", Collections.emptySet(), filesDir).getName());
	}

	@Test
	public void liveLeaseIsScopedToWorkdirAndReleaseAllowsCleanReuse() throws Exception {
		File filesDir = temporaryFolder.newFolder("lease-files-2");
		File rootA = temporaryFolder.newFolder("lease-a");
		File rootB = temporaryFolder.newFolder("lease-b");
		File convertedA = new File(rootA, "converted");
		File convertedB = new File(rootB, "converted");
		assertTrue(convertedA.mkdir());
		assertTrue(convertedB.mkdir());
		try (RuntimeStorageLease ignored = RuntimeStorageLease.acquire(
				filesDir, new File(convertedA, "Bounce"))) {
			assertEquals("Bounce_1", AppInstaller.chooseTargetDirectory(
					convertedA, "Bounce", Collections.emptySet(), filesDir).getName());
			assertEquals("Bounce", AppInstaller.chooseTargetDirectory(
					convertedB, "Bounce", Collections.emptySet(), filesDir).getName());
		}
		assertEquals("Bounce", AppInstaller.chooseTargetDirectory(
				convertedA, "Bounce", Collections.emptySet(), filesDir).getName());
	}

	@Test
	public void unknownLeaseStateNeverAllocatesCandidate() throws Exception {
		File root = temporaryFolder.newFolder("lease-unavailable");
		File converted = new File(root, "converted");
		assertTrue(converted.mkdir());
		File unusableFilesDir = temporaryFolder.newFile("not-a-directory");
		assertThrows(IOException.class, () -> AppInstaller.chooseTargetDirectory(
				converted, "Bounce", Collections.emptySet(), unusableFilesDir));
	}

	@Test
	public void normalAvailableNameIsPreserved() throws Exception {
		File converted = temporaryFolder.newFolder("converted-normal");

		File selected = AppInstaller.chooseTargetDirectory(converted, "Game");

		assertEquals("Game", selected.getName());
	}

	@Test
	public void emptyAndParentNamesStayInsideConvertedDirectory() throws Exception {
		File converted = temporaryFolder.newFolder("converted-unsafe-name");

		assertEquals(
				new File(converted, "MIDlet"),
				AppInstaller.chooseTargetDirectory(converted, ""));
		assertEquals(
				new File(converted, "MIDlet"),
				AppInstaller.chooseTargetDirectory(converted, ".."));
	}

	@Test
	public void sameSizeDifferentJarContentIsNotTreatedAsIdentical() throws Exception {
		File first = temporaryFolder.newFile("first.jar");
		File second = temporaryFolder.newFile("second.jar");
		write(first, new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4});
		write(second, new byte[]{'P', 'K', 3, 4, 1, 2, 3, 9});

		assertFalse(AppInstaller.filesHaveSameContents(first, second));
	}

	@Test
	public void identicalJarContentIsDetectedAcrossWholeFile() throws Exception {
		File first = temporaryFolder.newFile("same-one.jar");
		File second = temporaryFolder.newFile("same-two.jar");
		byte[] content = new byte[20_000];
		for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
		write(first, content);
		write(second, content);

		assertTrue(AppInstaller.filesHaveSameContents(first, second));
	}

	private static void write(File file, byte[] content) throws Exception {
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(content);
		}
	}
}
