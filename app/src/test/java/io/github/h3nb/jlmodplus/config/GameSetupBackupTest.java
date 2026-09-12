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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class GameSetupBackupTest {
	private File directory;

	@Before
	public void setUp() throws Exception {
		directory = Files.createTempDirectory("jlmod-previous-setup").toFile();
	}

	@After
	public void tearDown() {
		deleteRecursively(directory);
	}

	@Test
	public void restoreRemovesLayoutThatWasNotInPreviousSetup() throws Exception {
		writeConfig("old-config");
		GameSetupBackup.capture(directory, "Old preset", false);

		writeConfig("new-config");
		writeLayout("new-layout");

		GameSetupBackup.RestoredMetadata restored = GameSetupBackup.restore(directory);

		assertTrue(restored.profileOrigin.equals("Old preset"));
		assertFalse(restored.builtInThemeLinked);
		assertTrue(read("config.json").equals("old-config"));
		assertFalse(new File(directory, "VirtualKeyboardLayout").exists());
		assertFalse(GameSetupBackup.hasBackup(directory));
	}

	@Test
	public void restoreReturnsPreviousLayoutAndMetadata() throws Exception {
		writeConfig("old-config");
		writeLayout("old-layout");
		GameSetupBackup.capture(directory, null, true);

		writeConfig("new-config");
		writeLayout("new-layout");

		GameSetupBackup.RestoredMetadata restored = GameSetupBackup.restore(directory);

		assertTrue(restored.profileOrigin == null);
		assertTrue(restored.builtInThemeLinked);
		assertTrue(read("config.json").equals("old-config"));
		assertTrue(read("VirtualKeyboardLayout").equals("old-layout"));
	}

	@Test
	public void secondCaptureReplacesTheOnePreviousSetup() throws Exception {
		writeConfig("first");
		GameSetupBackup.capture(directory, "first-origin", false);

		writeConfig("second");
		GameSetupBackup.capture(directory, "second-origin", false);

		GameSetupBackup.RestoredMetadata restored = GameSetupBackup.restore(directory);

		assertTrue(restored.profileOrigin.equals("second-origin"));
		assertTrue(read("config.json").equals("second"));
	}

	private void writeConfig(String value) throws Exception {
		Files.write(new File(directory, "config.json").toPath(), value.getBytes(StandardCharsets.UTF_8));
	}

	private void writeLayout(String value) throws Exception {
		Files.write(new File(directory, "VirtualKeyboardLayout").toPath(), value.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws Exception {
		return new String(Files.readAllBytes(new File(directory, name).toPath()), StandardCharsets.UTF_8);
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}
}
