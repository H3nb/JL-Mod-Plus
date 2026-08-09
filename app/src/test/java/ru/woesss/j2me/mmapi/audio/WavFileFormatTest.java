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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WavFileFormatTest {
	@Test
	public void recognizesMonoImaAdpcmWithNonAudioChunk() throws IOException {
		Path file = Files.createTempFile("ima", ".wav");
		Files.write(file, waveFile(WavFileFormat.IMA_ADPCM, 1, 4, true));

		assertTrue(WavFileFormat.isMonoImaAdpcm(file.toFile()));
	}

	@Test
	public void rejectsStereoImaAdpcmForMonoCompatibilityCheck() throws IOException {
		Path file = Files.createTempFile("ima-stereo", ".wav");
		Files.write(file, waveFile(WavFileFormat.IMA_ADPCM, 2, 4, false));

		assertFalse(WavFileFormat.isMonoImaAdpcm(file.toFile()));
	}

	@Test
	public void identifiesStandardGsm610Wav49() throws IOException {
		Path file = Files.createTempFile("gsm610", ".wav");
		Files.write(file, gsm610WaveFile(1, 65, 320));

		WavFileFormat.Info info = WavFileFormat.inspect(file.toFile());
		assertNotNull(info);
		assertEquals(WavFileFormat.GSM_610, info.getFormatTag());
		assertEquals("GSM 6.10", info.getCodecName());
		assertEquals(1, info.getChannels());
		assertEquals(8000, info.getSampleRate());
		assertEquals(65, info.getBlockAlignment());
		assertEquals(320, info.getSamplesPerBlock());
		assertTrue(info.describe().contains("formatTag: 0x0031"));
		assertTrue(WavFileFormat.isGsm610(file.toFile()));
	}

	@Test
	public void rejectsNonStandardGsm610Layout() throws IOException {
		Path stereo = Files.createTempFile("gsm610-stereo", ".wav");
		Files.write(stereo, gsm610WaveFile(2, 65, 320));
		assertFalse(WavFileFormat.isGsm610(stereo.toFile()));

		Path wrongBlock = Files.createTempFile("gsm610-block", ".wav");
		Files.write(wrongBlock, gsm610WaveFile(1, 64, 320));
		assertFalse(WavFileFormat.isGsm610(wrongBlock.toFile()));

		Path wrongSamples = Files.createTempFile("gsm610-samples", ".wav");
		Files.write(wrongSamples, gsm610WaveFile(1, 65, 160));
		assertFalse(WavFileFormat.isGsm610(wrongSamples.toFile()));

		Path invalidExtension = Files.createTempFile("gsm610-extension", ".wav");
		Files.write(invalidExtension, gsm610WaveFile(1, 65, 320, 4));
		WavFileFormat.Info invalidInfo = WavFileFormat.inspect(invalidExtension.toFile());
		assertNotNull(invalidInfo);
		assertEquals(0, invalidInfo.getSamplesPerBlock());
		assertFalse(WavFileFormat.isGsm610(invalidExtension.toFile()));
	}

	@Test
	public void doesNotTreatGenericFmtExtensionAsSamplesPerBlock() throws IOException {
		Path file = Files.createTempFile("extended-non-gsm", ".wav");
		Files.write(file, extendedWaveFile(WavFileFormat.MPEG_LAYER_3, 0x1234));

		WavFileFormat.Info info = WavFileFormat.inspect(file.toFile());
		assertNotNull(info);
		assertEquals(WavFileFormat.MPEG_LAYER_3, info.getFormatTag());
		assertEquals(0, info.getSamplesPerBlock());
	}

	@Test
	public void identifiesPcmAndRejectsMalformedFiles() throws IOException {
		Path pcm = Files.createTempFile("pcm", ".wav");
		Files.write(pcm, waveFile(WavFileFormat.PCM, 1, 16, false));
		WavFileFormat.Info info = WavFileFormat.inspect(pcm.toFile());
		assertNotNull(info);
		assertEquals("PCM", info.getCodecName());
		assertFalse(WavFileFormat.isMonoImaAdpcm(pcm.toFile()));
		assertFalse(WavFileFormat.isGsm610(pcm.toFile()));

		Path malformed = Files.createTempFile("malformed", ".wav");
		Files.write(malformed, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0});
		assertEquals(null, WavFileFormat.inspect(malformed.toFile()));
		assertFalse(WavFileFormat.isMonoImaAdpcm(malformed.toFile()));
		assertFalse(WavFileFormat.isGsm610(malformed.toFile()));
	}

	private static byte[] waveFile(int format, int channels, int bitsPerSample, boolean includeJunk) {
		int junkSize = includeJunk ? 4 : 0;
		int fmtSize = 16;
		int dataSize = 0;
		int riffSize = 4 + (includeJunk ? 8 + junkSize : 0) + 8 + fmtSize + 8 + dataSize;
		ByteBuffer buffer = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN);
		putAscii(buffer, "RIFF");
		buffer.putInt(riffSize);
		putAscii(buffer, "WAVE");
		if (includeJunk) {
			putAscii(buffer, "JUNK");
			buffer.putInt(junkSize);
			buffer.putInt(0);
		}
		putAscii(buffer, "fmt ");
		buffer.putInt(fmtSize);
		buffer.putShort((short) format);
		buffer.putShort((short) channels);
		buffer.putInt(8000);
		buffer.putInt(4096);
		buffer.putShort((short) 1);
		buffer.putShort((short) bitsPerSample);
		putAscii(buffer, "data");
		buffer.putInt(dataSize);
		return buffer.array();
	}

	private static byte[] gsm610WaveFile(int channels, int blockAlign, int samplesPerBlock) {
		return gsm610WaveFile(channels, blockAlign, samplesPerBlock, 2);
	}

	private static byte[] gsm610WaveFile(int channels, int blockAlign, int samplesPerBlock, int extraSize) {
		int fmtSize = 20;
		int dataSize = 65;
		int factSize = 4;
		int riffSize = 4 + (8 + fmtSize) + (8 + factSize) + (8 + dataSize + 1);
		ByteBuffer buffer = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN);
		putAscii(buffer, "RIFF");
		buffer.putInt(riffSize);
		putAscii(buffer, "WAVE");
		putAscii(buffer, "fmt ");
		buffer.putInt(fmtSize);
		buffer.putShort((short) WavFileFormat.GSM_610);
		buffer.putShort((short) channels);
		buffer.putInt(8000);
		buffer.putInt(1625);
		buffer.putShort((short) blockAlign);
		buffer.putShort((short) 0);
		buffer.putShort((short) extraSize);
		buffer.putShort((short) samplesPerBlock);
		putAscii(buffer, "fact");
		buffer.putInt(factSize);
		buffer.putInt(320);
		putAscii(buffer, "data");
		buffer.putInt(dataSize);
		buffer.put(new byte[dataSize]);
		buffer.put((byte) 0); // RIFF chunks are word-aligned; padding is outside data size.
		return buffer.array();
	}

	private static byte[] extendedWaveFile(int format, int extensionValue) {
		int fmtSize = 20;
		int dataSize = 0;
		int riffSize = 4 + 8 + fmtSize + 8 + dataSize;
		ByteBuffer buffer = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN);
		putAscii(buffer, "RIFF");
		buffer.putInt(riffSize);
		putAscii(buffer, "WAVE");
		putAscii(buffer, "fmt ");
		buffer.putInt(fmtSize);
		buffer.putShort((short) format);
		buffer.putShort((short) 1);
		buffer.putInt(8000);
		buffer.putInt(4096);
		buffer.putShort((short) 1);
		buffer.putShort((short) 0);
		buffer.putShort((short) 2);
		buffer.putShort((short) extensionValue);
		putAscii(buffer, "data");
		buffer.putInt(dataSize);
		return buffer.array();
	}

	private static void putAscii(ByteBuffer buffer, String value) {
		for (int i = 0; i < value.length(); i++) {
			buffer.put((byte) value.charAt(i));
		}
	}
}
