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
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the small mono IMA-ADPCM WAV shape that crashed EAS. */
public class ImaAdpcmRoutingTest {
	@Test
	public void monoImaAdpcmWaveAlwaysUsesDedicatedWavBackend() throws Exception {
		byte[] wav = createMonoImaAdpcmWave();
		File file = File.createTempFile("mmapi-ima-adpcm", ".mid");
		try {
			Files.write(file.toPath(), wav);

			assertEquals(ContentProbe.Kind.WAV, ContentProbe.probe(file));
			assertTrue(WavFileFormat.isMonoImaAdpcm(file));
			assertEquals(MediaRouter.Backend.WAV,
					MediaRouter.route(ContentProbe.probe(file), "audio/midi"));
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	private static byte[] createMonoImaAdpcmWave() {
		final int sampleRate = 8000;
		final int blockAlign = 256;
		final int samplesPerBlock = 505;
		final int dataSize = blockAlign;
		final int fmtChunkSize = 20;
		final int riffPayloadSize = 4 + (8 + fmtChunkSize) + (8 + dataSize);

		ByteArrayOutputStream out = new ByteArrayOutputStream(riffPayloadSize + 8);
		fourCc(out, "RIFF");
		le32(out, riffPayloadSize);
		fourCc(out, "WAVE");

		fourCc(out, "fmt ");
		le32(out, fmtChunkSize);
		le16(out, 0x0011); // WAVE_FORMAT_IMA_ADPCM
		le16(out, 1);      // mono
		le32(out, sampleRate);
		le32(out, (sampleRate * blockAlign) / samplesPerBlock);
		le16(out, blockAlign);
		le16(out, 4);
		le16(out, 2); // cbSize
		le16(out, samplesPerBlock);

		fourCc(out, "data");
		le32(out, dataSize);
		// One valid silent-ish IMA block: predictor=0, step index=0, reserved=0,
		// followed by zero nibbles.
		for (int i = 0; i < dataSize; i++) {
			out.write(0);
		}
		return out.toByteArray();
	}

	private static void fourCc(ByteArrayOutputStream out, String value) {
		for (int i = 0; i < 4; i++) {
			out.write(value.charAt(i));
		}
	}

	private static void le16(ByteArrayOutputStream out, int value) {
		out.write(value & 0xff);
		out.write((value >>> 8) & 0xff);
	}

	private static void le32(ByteArrayOutputStream out, int value) {
		out.write(value & 0xff);
		out.write((value >>> 8) & 0xff);
		out.write((value >>> 16) & 0xff);
		out.write((value >>> 24) & 0xff);
	}
}
