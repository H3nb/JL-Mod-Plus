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
