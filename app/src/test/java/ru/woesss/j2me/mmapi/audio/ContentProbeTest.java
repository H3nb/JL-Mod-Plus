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

package ru.woesss.j2me.mmapi.audio;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class ContentProbeTest {
	@Test
	public void detectsSequencedMediaSignatures() {
		assertEquals(ContentProbe.Kind.MIDI, probe("MThd\0\0\0\6"));
		assertEquals(ContentProbe.Kind.XMF, probe("XMF_1.00"));
		assertEquals(ContentProbe.Kind.RMID, probe("RIFF\0\0\0\0RMID"));
		assertEquals(ContentProbe.Kind.IMELODY, probe("BEGIN:IMELODY\nVERSION:1.2"));
	}

	@Test
	public void detectsWaveBeforeAnyMimeOrExtensionHintExists() {
		assertEquals(ContentProbe.Kind.WAV, probe("RIFF\0\0\0\0WAVEfmt "));
	}

	@Test
	public void detectsDiagnosticOnlySignatures() {
		assertEquals(ContentProbe.Kind.SMAF, probe("MMMD\0\0\0\0CNTI"));
		assertEquals(ContentProbe.Kind.QCP, probe("RIFF\0\0\0\0QLCMfmt "));
		assertEquals(ContentProbe.Kind.ASF, ContentProbe.probe(new byte[]{
				0x30, 0x26, (byte) 0xb2, 0x75, (byte) 0x8e, 0x66, (byte) 0xcf, 0x11,
				(byte) 0xa6, (byte) 0xd9, 0x00, (byte) 0xaa, 0x00, 0x62, (byte) 0xce, 0x6c
		}));
	}

	@Test
	public void probesCachedFilesWithoutDependingOnTheirExtension() throws Exception {
		File file = File.createTempFile("mmapi-content-probe", ".mid");
		try {
			Files.write(file.toPath(), "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1));
			assertEquals(ContentProbe.Kind.WAV, ContentProbe.probe(file));
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	@Test
	public void fingerprintsOnlySmallLeadingPrefix() throws Exception {
		File file = File.createTempFile("mmapi-content-probe", ".dat");
		try {
			Files.write(file.toPath(), new byte[]{0x01, 0x23, (byte) 0xab, (byte) 0xcd});
			assertEquals("01 23 AB CD", ContentProbe.fingerprint(file));
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	@Test
	public void detectsCommonCompressedAudioSignatures() {
		assertEquals(ContentProbe.Kind.AMR, probe("#!AMR\n"));
		assertEquals(ContentProbe.Kind.AMR_WB, probe("#!AMR-WB\n"));
		assertEquals(ContentProbe.Kind.MP3, probe("ID3\4\0\0"));
		assertEquals(ContentProbe.Kind.MP3,
				ContentProbe.probe(new byte[]{(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x00}));
		assertEquals(ContentProbe.Kind.AAC,
				ContentProbe.probe(new byte[]{(byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80}));
		assertEquals(ContentProbe.Kind.MP4,
				ContentProbe.probe(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}));
	}

	@Test
	public void rejectsTruncatedAndUnrecognizedPrefixes() {
		assertEquals(ContentProbe.Kind.UNKNOWN, ContentProbe.probe((byte[]) null));
		assertEquals(ContentProbe.Kind.UNKNOWN, ContentProbe.probe(new byte[0]));
		assertEquals(ContentProbe.Kind.UNKNOWN, probe("RIFFWAVE"));
		assertEquals(ContentProbe.Kind.UNKNOWN, probe("RIFF\0\0\0\0sfbk"));
		assertEquals(ContentProbe.Kind.UNKNOWN, probe("not media"));
	}

	private static ContentProbe.Kind probe(String value) {
		return ContentProbe.probe(value.getBytes(StandardCharsets.ISO_8859_1));
	}
}
