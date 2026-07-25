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

package ru.woesss.j2me.mmapi.synth;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SoundBankResolverTest {
	@Test
	public void resolvesDlsBySignatureRegardlessOfExtension() throws IOException {
		Path directory = Files.createTempDirectory("soundbanks");
		File file = Files.createFile(directory.resolve("bank.bin")).toFile();
		Files.write(file.toPath(), riffHeader("DLS "));

		SoundBankResolver.ResolvedSoundBank resolved =
				SoundBankResolver.resolve(directory.toFile(), file.getName());

		assertEquals(SoundBankResolver.Format.DLS, resolved.getFormat());
		assertEquals(file.getCanonicalFile(), resolved.getFile());
	}

	@Test
	public void resolvesSf2BySignature() throws IOException {
		Path directory = Files.createTempDirectory("soundbanks");
		File file = Files.createFile(directory.resolve("custom.dls")).toFile();
		Files.write(file.toPath(), riffHeader("sfbk"));

		SoundBankResolver.ResolvedSoundBank resolved =
				SoundBankResolver.resolve(directory.toFile(), file.getName());

		assertEquals(SoundBankResolver.Format.SF2, resolved.getFormat());
	}

	@Test
	public void defaultAndMissingSelectionsResolveToNoCustomBank() throws IOException {
		Path directory = Files.createTempDirectory("soundbanks");

		assertNull(SoundBankResolver.resolve(directory.toFile(), null));
		assertNull(SoundBankResolver.resolve(directory.toFile(), ""));
		assertNull(SoundBankResolver.resolve(directory.toFile(), "missing.sf2"));
	}

	@Test
	public void rejectsPathOutsideSoundbankDirectory() throws IOException {
		Path root = Files.createTempDirectory("soundbanks");
		Path outside = Files.createTempDirectory("outside");
		File file = Files.createFile(outside.resolve("outside.sf2")).toFile();
		Files.write(file.toPath(), riffHeader("sfbk"));

		assertNull(SoundBankResolver.resolve(root.toFile(), "../outside/outside.sf2"));
	}

	@Test
	public void rejectsUnknownSignature() throws IOException {
		Path directory = Files.createTempDirectory("soundbanks");
		File file = Files.createFile(directory.resolve("not-a-bank.sf2")).toFile();
		Files.write(file.toPath(), new byte[]{'N', 'O', 'T', ' ', 'A'});

		assertNull(SoundBankResolver.resolve(directory.toFile(), file.getName()));
	}

	@Test
	public void recognizesOnlyRegularFiles() throws IOException {
		Path directory = Files.createTempDirectory("soundbanks");
		Path childDirectory = Files.createDirectory(directory.resolve("child.sf2"));

		assertTrue(Files.isDirectory(childDirectory));
		assertNull(SoundBankResolver.resolve(directory.toFile(), childDirectory.getFileName().toString()));
	}

	private static byte[] riffHeader(String form) {
		byte[] header = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0,
				(byte) form.charAt(0), (byte) form.charAt(1), (byte) form.charAt(2), (byte) form.charAt(3)};
		return header;
	}
}
