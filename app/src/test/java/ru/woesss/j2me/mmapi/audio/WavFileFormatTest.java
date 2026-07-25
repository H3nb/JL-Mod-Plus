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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WavFileFormatTest {
	@Test
	public void recognizesMonoImaAdpcmWithNonAudioChunk() throws IOException {
		Path file = Files.createTempFile("ima", ".wav");
		Files.write(file, waveFile(0x0011, 1, 4, true));

		assertTrue(WavFileFormat.isMonoImaAdpcm(file.toFile()));
	}

	@Test
	public void rejectsStereoImaAdpcmBecauseEasSupportsMonoOnly() throws IOException {
		Path file = Files.createTempFile("ima-stereo", ".wav");
		Files.write(file, waveFile(0x0011, 2, 4, false));

		assertFalse(WavFileFormat.isMonoImaAdpcm(file.toFile()));
	}

	@Test
	public void rejectsPcmAndMalformedFiles() throws IOException {
		Path pcm = Files.createTempFile("pcm", ".wav");
		Files.write(pcm, waveFile(0x0001, 1, 16, false));
		assertFalse(WavFileFormat.isMonoImaAdpcm(pcm.toFile()));

		Path malformed = Files.createTempFile("malformed", ".wav");
		Files.write(malformed, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0});
		assertFalse(WavFileFormat.isMonoImaAdpcm(malformed.toFile()));
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

	private static void putAscii(ByteBuffer buffer, String value) {
		for (int i = 0; i < value.length(); i++) {
			buffer.put((byte) value.charAt(i));
		}
	}
}
