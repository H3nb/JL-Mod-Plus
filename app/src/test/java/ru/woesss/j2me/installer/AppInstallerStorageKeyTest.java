/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

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
	public void normalAvailableNameIsPreserved() throws Exception {
		File converted = temporaryFolder.newFolder("converted-normal");

		File selected = AppInstaller.chooseTargetDirectory(converted, "Game");

		assertEquals("Game", selected.getName());
	}

	@Test
	public void sameSizeDifferentJarContentIsNotTreatedAsIdentical() throws Exception {
		File first = temporaryFolder.newFile("first.jar");
		File second = temporaryFolder.newFile("second.jar");
		Files.write(first.toPath(), new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4});
		Files.write(second.toPath(), new byte[]{'P', 'K', 3, 4, 1, 2, 3, 9});

		assertFalse(AppInstaller.filesHaveSameContents(first, second));
	}

	@Test
	public void identicalJarContentIsDetectedAcrossWholeFile() throws Exception {
		File first = temporaryFolder.newFile("same-one.jar");
		File second = temporaryFolder.newFile("same-two.jar");
		byte[] content = new byte[20_000];
		for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
		Files.write(first.toPath(), content);
		Files.write(second.toPath(), content);

		assertTrue(AppInstaller.filesHaveSameContents(first, second));
	}
}
