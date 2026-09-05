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

package ru.woesss.j2me.installer;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import ru.woesss.j2me.jar.Descriptor;

public class AppReconverterTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test public void missingInstalledDescriptorCanBeRebuiltFromRetainedManifest() throws Exception {
		Descriptor source = new Descriptor("MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n", false);
		assertEquals(source, AppReconverter.mergeInstalledDescriptor(source,
				new File(temporaryFolder.getRoot(), "missing.conf")));
	}

	@Test public void jadPropertiesSurviveReconversion() throws Exception {
		Descriptor source = new Descriptor("MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n", false);
		File installed = temporaryFolder.newFile("jad.conf");
		Files.writeString(installed.toPath(), "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n"
				+ "MIDlet-Jar-URL: ../game.jar?token=keep\nNokia-MIDlet-On-Screen-Keypad: no\n");
		Descriptor result = AppReconverter.mergeInstalledDescriptor(source, installed);
		assertEquals("../game.jar?token=keep", result.getJarUrl());
		assertEquals("no", result.getAttrs().get("Nokia-MIDlet-On-Screen-Keypad"));
	}

	@Test public void originalJadRecoversPropertiesWhenMergedDescriptorIsMissing() throws Exception {
		Descriptor source = new Descriptor("MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n", false);
		File jad = temporaryFolder.newFile(AppReconverter.RETAINED_JAD);
		Files.writeString(jad.toPath(), "MIDlet-Name: Game\r\nMIDlet-Vendor: Vendor\r\nMIDlet-Version: 1.0\r\n"
				+ "MIDlet-Jar-URL: game.jar\r\nMIDlet-Jar-Size: 100\r\nVendor-Option: preserve\r\n");
		byte[] original = Files.readAllBytes(jad.toPath());
		Descriptor merged = AppReconverter.mergeInstalledDescriptor(source,
				new File(temporaryFolder.getRoot(), "converted.dex.conf"));
		assertEquals("preserve", merged.getAttrs().get("Vendor-Option"));
		org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(jad.toPath()));
	}

	@Test
	public void installedDescriptorOverridesSourceManifestWithoutDroppingManifestOnlyFields()
			throws Exception {
		Descriptor sourceManifest = new Descriptor(
				"MIDlet-Name: Source\n"
						+ "MIDlet-Vendor: Source Vendor\n"
						+ "MIDlet-Version: 1.0\n"
						+ "MIDlet-Icon: source.png\n",
				false);
		File installed = temporaryFolder.newFile("converted.dex.conf");
		Files.write(installed.toPath(), (
				"MIDlet-Name: Installed\n"
						+ "MIDlet-Vendor: Installed Vendor\n"
						+ "MIDlet-Version: 2.0\n"
						+ "Vendor-Feature: enabled\n").getBytes(StandardCharsets.UTF_8));

		Descriptor merged = AppReconverter.mergeInstalledDescriptor(sourceManifest, installed);

		assertEquals("Installed", merged.getName());
		assertEquals("Installed Vendor", merged.getVendor());
		assertEquals("2.0", merged.getVersion());
		assertEquals("source.png", merged.getAttrs().get(Descriptor.MIDLET_ICON));
		assertEquals("enabled", merged.getAttrs().get("Vendor-Feature"));
	}
}
