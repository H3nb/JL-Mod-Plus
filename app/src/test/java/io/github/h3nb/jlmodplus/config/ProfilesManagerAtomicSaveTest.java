/*
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class ProfilesManagerAtomicSaveTest {
	@Test
	public void savePublishesUtf8ConfigWithoutLeavingSidecars() throws Exception {
		File directory = Files.createTempDirectory("jlmod-atomic-save").toFile();
		directory.deleteOnExit();
		ProfileModel profile = new ProfileModel();
		profile.dir = directory;
		profile.version = ProfileModel.VERSION;
		profile.systemProperties = "unicode=✓";

		assertTrue(ProfilesManager.saveConfig(profile));
		File config = new File(directory, Config.MIDLET_CONFIG_FILE);
		assertTrue(config.isFile());
		assertFalse(new File(config.getPath() + ".new").exists());
		assertFalse(new File(config.getPath() + ".bak").exists());
		String saved = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
		assertTrue(saved.contains("SystemProperties"));
		assertTrue(saved.contains("✓"));
	}

	@Test
	public void interruptedBackupIsRestoredBeforeLoad() throws Exception {
		File directory = Files.createTempDirectory("jlmod-atomic-recover").toFile();
		directory.deleteOnExit();
		ProfileModel previous = new ProfileModel();
		previous.dir = directory;
		previous.version = ProfileModel.VERSION;
		File config = new File(directory, Config.MIDLET_CONFIG_FILE);
		Files.write(config.toPath(), new Gson().toJson(previous).getBytes(StandardCharsets.UTF_8));
		File backup = new File(config.getPath() + ".bak");
		assertTrue(config.renameTo(backup));

		ProfileModel loaded = ProfilesManager.loadConfig(directory, false);

		assertNotNull(loaded);
		assertTrue(config.isFile());
		assertFalse(backup.exists());
	}
}
