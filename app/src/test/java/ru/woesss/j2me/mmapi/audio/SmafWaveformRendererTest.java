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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmafWaveformRendererTest {
	private static final int YAMAHA_MONO_8KHZ_4BIT = 0x1100;

	@Test
	public void rendersAwaAtAtsqTimeAndClipsToGate() throws Exception {
		byte[] adpcm = new byte[32];
		for (int i = 0; i < adpcm.length; i++) {
			adpcm[i] = 0x01;
		}
		byte[] sequence = new byte[]{
				0x01, 0x01, 0x01,
				0x00, 0x00, 0x00, 0x00
		};

		byte[] wav = render(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence), awa(1, adpcm))));

		assertEquals('R', wav[0]);
		assertEquals('W', wav[8]);
		assertEquals(8000, readLe32(wav, 24));
		// 4 ms silence (32 frames) + 4 ms gated audio (32 frames).
		assertEquals(128, readLe32(wav, 40));
		int firstAudio = 44 + 32 * 2;
		assertEquals(47, wav[firstAudio] & 0xff);
		assertEquals(0, wav[firstAudio + 1] & 0xff);
	}

	@Test
	public void zeroGatePlaysWholeAwa() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x01, 0x00,
				0x00, 0x00, 0x00, 0x00
		};
		byte[] wav = render(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence), awa(1, new byte[]{0x01, 0x01}))));

		assertEquals(8, readLe32(wav, 40));
	}

	@Test
	public void rendersSequentialWaveEventsWithoutLosingSequencePosition() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x01, 0x01,
				0x01, 0x02, 0x01,
				0x00, 0x00, 0x00, 0x00
		};
		byte[] wav = render(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence),
				awa(1, new byte[32]), awa(2, new byte[32]))));

		assertEquals(64 * 2, readLe32(wav, 40));
	}

	@Test
	public void rejectsOverlappingWaveEventsUntilMixingIsImplemented() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x01, 0x02,
				0x01, 0x01, 0x01,
				0x00, 0x00, 0x00, 0x00
		};
		assertFalse(renderSupported(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence), awa(1, new byte[32])))));
	}

	@Test
	public void rejectsMissingReferencedWave() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x02, 0x01,
				0x00, 0x00, 0x00, 0x00
		};
		assertFalse(renderSupported(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence), awa(1, new byte[8])))));
	}

	@Test
	public void rejectsScoreAndWaveMixInsteadOfDroppingScore() throws Exception {
		byte[] score = chunk("MTR0", concat(
				new byte[]{0x00, 0x00, 0x02, 0x02, 0x00, 0x00},
				chunk("Mtsq", new byte[0])));
		byte[] audio = atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", new byte[]{0x00, 0x01, 0x01}), awa(1, new byte[8]));
		assertFalse(renderSupported(mmmd(score, audio)));
	}

	@Test
	public void rejectsNonYamahaAwa() throws Exception {
		int signedPcmMono8Khz4Bit = 0x0100;
		assertFalse(renderSupported(mmmd(atr(signedPcmMono8Khz4Bit,
				chunk("Atsq", new byte[]{0x00, 0x01, 0x01}), awa(1, new byte[8])))));
	}

	@Test
	public void rejectsHpsControlEventThatCouldChangeAudioSemantics() throws Exception {
		byte[] sequence = new byte[]{
				0x00, 0x00, 0x37, 0x40
		};
		assertFalse(renderSupported(mmmd(atr(YAMAHA_MONO_8KHZ_4BIT,
				chunk("Atsq", sequence), awa(1, new byte[8])))));
	}

	private static byte[] render(byte[] smaf) throws Exception {
		File source = File.createTempFile("smaf-wave-", ".mmf");
		File target = File.createTempFile("smaf-wave-", ".wav");
		try {
			try (FileOutputStream out = new FileOutputStream(source)) {
				out.write(smaf);
			}
			assertTrue(SmafWaveformRenderer.render(source, target));
			return readAllBytes(target);
		} finally {
			source.delete();
			target.delete();
		}
	}

	private static boolean renderSupported(byte[] smaf) throws Exception {
		File source = File.createTempFile("smaf-wave-", ".mmf");
		File target = File.createTempFile("smaf-wave-", ".wav");
		try {
			try (FileOutputStream out = new FileOutputStream(source)) {
				out.write(smaf);
			}
			return SmafWaveformRenderer.render(source, target);
		} finally {
			source.delete();
			target.delete();
		}
	}

	private static byte[] readAllBytes(File file) throws Exception {
		try (FileInputStream input = new FileInputStream(file);
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[1024];
			int count;
			while ((count = input.read(buffer)) != -1) {
				output.write(buffer, 0, count);
			}
			return output.toByteArray();
		}
	}

	private static byte[] atr(int waveType, byte[]... nested) throws Exception {
		return chunk("ATR0", concat(
				new byte[]{0x00, 0x00, (byte) (waveType >>> 8), (byte) waveType, 0x02, 0x02},
				concat(nested)));
	}

	private static byte[] awa(int number, byte[] body) throws Exception {
		return chunk(new byte[]{'A', 'w', 'a', (byte) number}, body);
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
		return chunk(id.getBytes(StandardCharsets.US_ASCII), body);
	}

	private static byte[] chunk(byte[] id, byte[] body) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(id);
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

	private static int readLe32(byte[] data, int offset) {
		return (data[offset] & 0xff)
				| (data[offset + 1] & 0xff) << 8
				| (data[offset + 2] & 0xff) << 16
				| (data[offset + 3] & 0xff) << 24;
	}
}
