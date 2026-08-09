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

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Reads RIFF/WAVE format metadata used for diagnostics and regression tests.
 *
 * <p>Playback is handled by the dedicated dr_wav backend. This inspector is
 * intentionally side-effect free and, in particular, never routes WAVE data
 * into a MIDI synthesizer.</p>
 */
public final class WavFileFormat {
	public static final int PCM = 0x0001;
	public static final int IEEE_FLOAT = 0x0003;
	public static final int A_LAW = 0x0006;
	public static final int MU_LAW = 0x0007;
	public static final int IMA_ADPCM = 0x0011;
	public static final int GSM_610 = 0x0031;
	public static final int MPEG_LAYER_3 = 0x0055;
	public static final int EXTENSIBLE = 0xfffe;
	public static final int DVM = 0x2000;

	public static final class Info {
		private final int formatTag;
		private final int channels;
		private final long sampleRate;
		private final long averageBytesPerSecond;
		private final int blockAlignment;
		private final int bitsPerSample;
		private final int samplesPerBlock;

		private Info(int formatTag, int channels, long sampleRate,
				long averageBytesPerSecond, int blockAlignment, int bitsPerSample,
				int samplesPerBlock) {
			this.formatTag = formatTag;
			this.channels = channels;
			this.sampleRate = sampleRate;
			this.averageBytesPerSecond = averageBytesPerSecond;
			this.blockAlignment = blockAlignment;
			this.bitsPerSample = bitsPerSample;
			this.samplesPerBlock = samplesPerBlock;
		}

		public int getFormatTag() {
			return formatTag;
		}

		public int getChannels() {
			return channels;
		}

		public long getSampleRate() {
			return sampleRate;
		}

		public long getAverageBytesPerSecond() {
			return averageBytesPerSecond;
		}

		public int getBlockAlignment() {
			return blockAlignment;
		}

		public int getBitsPerSample() {
			return bitsPerSample;
		}

		/** Returns GSM 6.10 samples per block, or 0 when not applicable/valid. */
		public int getSamplesPerBlock() {
			return samplesPerBlock;
		}

		public String getCodecName() {
			return switch (formatTag) {
				case PCM -> "PCM";
				case IEEE_FLOAT -> "IEEE Float";
				case A_LAW -> "A-law";
				case MU_LAW -> "mu-law";
				case IMA_ADPCM -> "IMA/DVI ADPCM";
				case GSM_610 -> "GSM 6.10";
				case MPEG_LAYER_3 -> "MPEG Layer III";
				case DVM -> "WAVE_FORMAT_DVM";
				case EXTENSIBLE -> "WAVE_FORMAT_EXTENSIBLE";
				default -> "Unknown WAVE codec";
			};
		}

		public String describe() {
			String result = String.format(Locale.ROOT,
					"Container: RIFF/WAVE; Codec: %s; formatTag: 0x%04X; channels: %d; sampleRate: %d Hz; bitsPerSample: %d; blockAlign: %d",
					getCodecName(), formatTag, channels, sampleRate, bitsPerSample, blockAlignment);
			if (samplesPerBlock > 0) {
				result += "; samplesPerBlock: " + samplesPerBlock;
			}
			return result;
		}
	}

	private WavFileFormat() {
	}

	/** Returns the WAVE fmt metadata, or {@code null} if the header is invalid/incomplete. */
	public static Info inspect(File file) throws IOException {
		if (file == null || !file.isFile()) {
			return null;
		}

		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			try {
				if (!matches(input, "RIFF") || readUnsignedInt(input) < 4 || !matches(input, "WAVE")) {
					return null;
				}

				long fileLength = input.length();
				while (input.getFilePointer() + 8 <= fileLength) {
					String chunkId = readFourCc(input);
					long chunkSize = readUnsignedInt(input);
					long chunkDataStart = input.getFilePointer();
					if (chunkSize > fileLength - chunkDataStart) {
						return null;
					}

					if ("fmt ".equals(chunkId)) {
						if (chunkSize < 16) {
							return null;
						}
						int formatTag = readUnsignedShort(input);
						int channels = readUnsignedShort(input);
						long sampleRate = readUnsignedInt(input);
						long averageBytesPerSecond = readUnsignedInt(input);
						int blockAlignment = readUnsignedShort(input);
						int bitsPerSample = readUnsignedShort(input);
						int samplesPerBlock = 0;
						if (chunkSize >= 18) {
							int extraSize = readUnsignedShort(input);
							long extensionEnd = 18L + extraSize;
							if (formatTag == GSM_610
									&& extraSize >= 2
									&& extensionEnd <= chunkSize
									&& chunkSize >= 20) {
								samplesPerBlock = readUnsignedShort(input);
							}
						}
						return new Info(formatTag, channels, sampleRate,
								averageBytesPerSecond, blockAlignment, bitsPerSample,
								samplesPerBlock);
					}

					long nextChunk = chunkDataStart + chunkSize + (chunkSize & 1L);
					if (nextChunk < chunkDataStart || nextChunk > fileLength) {
						return null;
					}
					input.seek(nextChunk);
				}
			} catch (EOFException e) {
				return null;
			}
		}
		return null;
	}

	/** Returns whether {@code file} declares mono, 4-bit IMA ADPCM WAVE audio. */
	public static boolean isMonoImaAdpcm(File file) throws IOException {
		Info info = inspect(file);
		return info != null
				&& info.getFormatTag() == IMA_ADPCM
				&& info.getChannels() == 1
				&& info.getBitsPerSample() == 4;
	}

	/** Returns whether {@code file} declares a supported mono GSM 6.10 WAV49 layout. */
	public static boolean isGsm610(File file) throws IOException {
		Info info = inspect(file);
		return info != null
				&& info.getFormatTag() == GSM_610
				&& info.getChannels() == 1
				&& isGsm610SampleRate(info.getSampleRate())
				&& info.getBlockAlignment() == 65
				&& info.getBitsPerSample() == 0
				&& info.getSamplesPerBlock() == 320;
	}

	private static boolean isGsm610SampleRate(long sampleRate) {
		return sampleRate == 8000
				|| sampleRate == 11025
				|| sampleRate == 22050
				|| sampleRate == 44100;
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
