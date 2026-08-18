/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

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
	public void normalAvailableNameIsPreserved() throws Exception {
		File converted = temporaryFolder.newFolder("converted-normal");

		File selected = AppInstaller.chooseTargetDirectory(converted, "Game");

		assertEquals("Game", selected.getName());
	}
}
