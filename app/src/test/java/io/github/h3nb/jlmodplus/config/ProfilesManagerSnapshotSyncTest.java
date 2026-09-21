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
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile(dir)))) {
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
		return new File(dir, "config.json");
	}

	private static File layoutFile(File dir) {
		return new File(dir, "VirtualKeyboardLayout");
	}
}
