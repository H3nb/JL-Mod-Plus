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

package io.github.h3nb.jlmodplus.filepicker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FilePickerModelTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void modeValuesRemainCompatibleWithTheLegacyContract() {
		assertEquals(0, FilePickerContract.MODE_FILE);
		assertEquals(1, FilePickerContract.MODE_DIR);
		assertEquals(2, FilePickerContract.MODE_FILE_AND_DIR);
		assertEquals(3, FilePickerContract.MODE_NEW_FILE);
	}

	@Test
	public void selectedFileRestoresItsParentDirectory() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File games = new File(root, "games");
		assertTrue(games.mkdir());
		File selected = new File(games, "Game.JAR");
		assertTrue(selected.createNewFile());

		assertEquals(
				FilePickerModel.canonicalFile(games),
				FilePickerModel.normalizeStartPath(selected, root));
	}

	@Test
	public void missingPathFallsBackToExistingParentOrRoot() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File existingParent = new File(root, "games");
		assertTrue(existingParent.mkdir());

		assertEquals(
				FilePickerModel.canonicalFile(existingParent),
				FilePickerModel.normalizeStartPath(new File(existingParent, "new.jar"), root));
		assertEquals(
				FilePickerModel.canonicalFile(root),
				FilePickerModel.normalizeStartPath(new File(root, "missing/new.jar"), root));
	}

	@Test
	public void canonicalPathMustRemainInsidePickerRoot() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File child = new File(root, "games");
		assertTrue(child.mkdir());

		assertTrue(FilePickerModel.isWithinRoot(child, root));
		assertFalse(FilePickerModel.isWithinRoot(new File(root, "../outside"), root));
		assertEquals(
				FilePickerModel.canonicalFile(root),
				FilePickerModel.normalizeStartPath(new File(root, "../outside"), root));
	}

	@Test
	public void extensionFilterIsCaseInsensitiveAndModeAware() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File jar = new File(root, "GAME.JAR");
		File txt = new File(root, "notes.txt");
		assertTrue(jar.createNewFile());
		assertTrue(txt.createNewFile());

		assertTrue(FilePickerModel.isItemVisible(jar, FilePickerContract.MODE_FILE));
		assertFalse(FilePickerModel.isItemVisible(txt, FilePickerContract.MODE_FILE));
		assertFalse(FilePickerModel.isItemVisible(jar, FilePickerContract.MODE_DIR));
	}

	@Test
	public void directoriesSortBeforeFilesWithDeterministicTieBreakers() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File zFile = new File(root, "z.jar");
		File aDirectory = new File(root, "A");
		File bDirectory = new File(root, "b");
		assertTrue(zFile.createNewFile());
		assertTrue(aDirectory.mkdir());
		assertTrue(bDirectory.mkdir());

		List<File> files = new ArrayList<>(Arrays.asList(zFile, bDirectory, aDirectory));
		FilePickerModel.sortFiles(files);

		assertEquals(aDirectory, files.get(0));
		assertEquals(bDirectory, files.get(1));
		assertEquals(zFile, files.get(2));
	}

	@Test
	public void directoryNameValidationRejectsPathsAndAmbiguousNames() {
		assertTrue(FilePickerModel.isValidDirectoryName("Games 2026"));
		assertFalse(FilePickerModel.isValidDirectoryName(null));
		assertFalse(FilePickerModel.isValidDirectoryName(""));
		assertFalse(FilePickerModel.isValidDirectoryName(" . "));
		assertFalse(FilePickerModel.isValidDirectoryName("."));
		assertFalse(FilePickerModel.isValidDirectoryName(".."));
		assertFalse(FilePickerModel.isValidDirectoryName("a/b"));
		assertFalse(FilePickerModel.isValidDirectoryName("a\\b"));
	}

	@Test
	public void selectionRulesMatchFileAndDirectoryModes() throws IOException {
		File root = temporaryFolder.newFolder("storage");
		File directory = new File(root, "games");
		File jar = new File(root, "game.jar");
		assertTrue(directory.mkdir());
		assertTrue(jar.createNewFile());

		assertTrue(FilePickerModel.isSelectable(directory, FilePickerContract.MODE_DIR, false));
		assertFalse(FilePickerModel.isSelectable(directory, FilePickerContract.MODE_FILE, false));
		assertTrue(FilePickerModel.isSelectable(jar, FilePickerContract.MODE_FILE, false));
		assertTrue(FilePickerModel.isSelectable(jar, FilePickerContract.MODE_FILE_AND_DIR, false));
	}
}
