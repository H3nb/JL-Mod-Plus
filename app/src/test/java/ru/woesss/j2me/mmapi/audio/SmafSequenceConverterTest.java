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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmafSequenceConverterTest {
	@Test
	public void convertsHandyPhoneNoteOnExactFourMillisecondTimeline() throws Exception {
		byte[] sequence = new byte[]{
				0x01, 0x01, 0x02,
				0x01, 0x00, 0x00, 0x00
		};
		byte[] smf = convert(score(0, 0, 0x02, new byte[2], chunk("Mtsq", sequence)));

		assertNotNull(smf);
		assertSmfHeader(smf);
		assertContains(smf, new int[]{0x04, 0x90, 37, 0x7f});
		assertContains(smf, new int[]{0x08, 0x80, 37, 0x00});
	}

	@Test
	public void mapsHandyPhoneRhythmVoiceToMidiDrumChannel() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x00, 0x30, 40,
				0x00, 0x01, 0x01,
				0x00, 0x00, 0x00, 0x00
		};
		byte[] smf = convert(score(0, 0, 0x02, new byte[]{0x30, 0x00}, chunk("Mtsq", sequence)));

		assertNotNull(smf);
		assertContains(smf, new int[]{0x00, 0x99, 40, 0x7f});
		assertContains(smf, new int[]{0x04, 0x89, 40, 0x00});
	}

	@Test
	public void carriesHandyPhoneSetupSysexIntoSmf() throws Exception {
		byte[] setup = new byte[]{(byte) 0xff, (byte) 0xf0, 0x02, 0x43, (byte) 0xf7};
		byte[] sequence = new byte[]{0x00, 0x00, 0x00, 0x00};
		byte[] smf = convert(score(0, 0, 0x02, new byte[2],
				chunk("Mtsu", setup), chunk("Mtsq", sequence)));

		assertNotNull(smf);
		assertContains(smf, new int[]{0x00, 0xf0, 0x02, 0x43, 0xf7});
	}

	@Test
	public void convertsMobileStandardNoCompressNote() throws Exception {
		byte[] sequence = new byte[]{
				0x01, (byte) 0x90, 60, 100, 0x02,
				0x01, (byte) 0xff, 0x2f, 0x00
		};
		byte[] smf = convert(score(2, 0, 0x02, new byte[16], chunk("Mtsq", sequence)));

		assertNotNull(smf);
		assertContains(smf, new int[]{0x04, 0x90, 60, 100});
		assertContains(smf, new int[]{0x08, 0x80, 60, 0x00});
	}

	@Test
	public void rejectsCompressedSubsequenceAndUnobservedTimebase() throws Exception {
		assertNull(convert(score(1, 0, 0x02, new byte[16], chunk("Mtsq", new byte[0]))));
		assertNull(convert(score(0, 1, 0x02, new byte[2], chunk("Mtsq", new byte[0]))));
		assertNull(convert(score(0, 0, 0x00, new byte[2], chunk("Mtsq", new byte[0]))));
	}

	@Test
	public void rejectsScoreWithWaveformInsteadOfSilentlyDroppingIt() throws Exception {
		byte[] score = score(0, 0, 0x02, new byte[2],
				chunk("Mtsq", new byte[]{0x00, 0x00, 0x00, 0x00}));
		byte[] atr = chunk("ATR0", concat(
				new byte[]{0x00, 0x00, 0x10, 0x00, 0x02, 0x02},
				chunk("Awa0", new byte[0])));

		assertNull(convert(score, atr));
	}

	@Test
	public void rejectsTempoMetaUntilTempoSemanticsAreImplemented() throws Exception {
		byte[] sequence = new byte[]{
				0x00, (byte) 0xff, 0x51, 0x03, 0x07, (byte) 0xa1, 0x20
		};
		assertNull(convert(score(0, 0, 0x02, new byte[2], chunk("Mtsq", sequence))));
	}

	@Test
	public void usesRegisteredSmafContentTypeAtPlayerBoundary() {
		assertEquals("application/vnd.smaf", SmafSequenceConverter.CONTENT_TYPE);
	}

	private static byte[] convert(byte[]... chunks) throws Exception {
		File file = File.createTempFile("smaf-sequence-", ".mmf");
		try {
			try (FileOutputStream out = new FileOutputStream(file)) {
				out.write(mmmd(chunks));
			}
			return SmafSequenceConverter.convert(file);
		} finally {
			file.delete();
		}
	}

	private static byte[] score(int format, int sequenceType, int timeBase,
			byte[] channelStatus, byte[]... nestedChunks) throws Exception {
		return chunk("MTR0", concat(
				new byte[]{(byte) format, (byte) sequenceType, (byte) timeBase, (byte) timeBase},
				channelStatus,
				concat(nestedChunks)));
	}

	private static byte[] mmmd(byte[]... chunks) throws Exception {
		byte[] body = concat(chunk("CNTI", new byte[5]), concat(chunks));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write("MMMD".getBytes(StandardCharsets.US_ASCII));
		out.write(intBytes(body.length + 2));
		out.write(body);
		out.write(0);
		out.write(0);
		return out.toByteArray();
	}

	private static byte[] chunk(String id, byte[] body) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(id.getBytes(StandardCharsets.US_ASCII));
		out.write(intBytes(body.length));
		out.write(body);
		return out.toByteArray();
	}

	private static byte[] intBytes(int value) {
		return ByteBuffer.allocate(4).putInt(value).array();
	}

	private static byte[] concat(byte[]... arrays) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] array : arrays) {
			out.write(array);
		}
		return out.toByteArray();
	}

	private static void assertSmfHeader(byte[] data) {
		assertTrue(data.length >= 22);
		assertEquals('M', data[0]);
		assertEquals('T', data[1]);
		assertEquals('h', data[2]);
		assertEquals('d', data[3]);
		assertEquals(0x03, data[12]);
		assertEquals(0xe8, data[13] & 0xff);
	}

	private static void assertContains(byte[] data, int[] expected) {
		for (int start = 0; start <= data.length - expected.length; start++) {
			boolean matches = true;
			for (int i = 0; i < expected.length; i++) {
				if ((data[start + i] & 0xff) != expected[i]) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return;
			}
		}
		throw new AssertionError("Expected byte sequence not found");
	}
}
