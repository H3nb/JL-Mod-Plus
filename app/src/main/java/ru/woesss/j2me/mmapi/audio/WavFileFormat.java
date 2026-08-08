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

import java.io.File;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads the small part of a RIFF/WAVE header needed for format diagnostics and
 * regression tests.
 *
 * <p>Playback is handled by the dedicated dr_wav backend. This inspector is
 * intentionally side-effect free and, in particular, never routes WAVE data
 * into a MIDI synthesizer.</p>
 */
public final class WavFileFormat {
	private static final int WAVE_FORMAT_IMA_ADPCM = 0x0011;

	private WavFileFormat() {
	}

	/** Returns whether {@code file} declares mono, 4-bit IMA ADPCM WAVE audio. */
	public static boolean isMonoImaAdpcm(File file) throws IOException {
		if (file == null || !file.isFile()) {
			return false;
		}

		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			try {
				if (!matches(input, "RIFF") || readUnsignedInt(input) < 4 || !matches(input, "WAVE")) {
					return false;
				}

				long fileLength = input.length();
				boolean supportedFormat = false;
				while (input.getFilePointer() + 8 <= fileLength) {
					String chunkId = readFourCc(input);
					long chunkSize = readUnsignedInt(input);
					long chunkDataStart = input.getFilePointer();
					if (chunkSize > fileLength - input.getFilePointer()) {
						return false;
					}

					if ("fmt ".equals(chunkId)) {
						if (chunkSize < 16) {
							return false;
						}
						int format = readUnsignedShort(input);
						int channels = readUnsignedShort(input);
						input.skipBytes(4); // sample rate
						input.skipBytes(4); // average bytes per second
						input.skipBytes(2); // block alignment
						int bitsPerSample = readUnsignedShort(input);
						supportedFormat = format == WAVE_FORMAT_IMA_ADPCM
								&& channels == 1 && bitsPerSample == 4;
					} else if ("data".equals(chunkId)) {
						return supportedFormat;
					}

					long nextChunk = chunkDataStart + chunkSize + (chunkSize & 1L);
					if (nextChunk < input.getFilePointer() || nextChunk > fileLength) {
						return false;
					}
					input.seek(nextChunk);
				}
			} catch (EOFException e) {
				return false;
			}
		}
		return false;
	}

	private static boolean matches(RandomAccessFile input, String expected) throws IOException {
		byte[] value = new byte[4];
		input.readFully(value);
		return value[0] == expected.charAt(0)
				&& value[1] == expected.charAt(1)
				&& value[2] == expected.charAt(2)
				&& value[3] == expected.charAt(3);
	}

	private static String readFourCc(RandomAccessFile input) throws IOException {
		byte[] value = new byte[4];
		input.readFully(value);
		return new String(value, java.nio.charset.StandardCharsets.US_ASCII);
	}

	private static int readUnsignedShort(RandomAccessFile input) throws IOException {
		return input.readUnsignedByte() | input.readUnsignedByte() << 8;
	}

	private static long readUnsignedInt(RandomAccessFile input) throws IOException {
		return (long) input.readUnsignedByte()
				| (long) input.readUnsignedByte() << 8
				| (long) input.readUnsignedByte() << 16
				| (long) input.readUnsignedByte() << 24;
	}
}
