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

/**
 * Small, side-effect-free inspector for Yamaha SMAF/MMMD structure.
 *
 * <p>This is intentionally a classifier, not a playback implementation. It
 * validates chunk boundaries and extracts only metadata needed to decide which
 * SMAF subsets may be practical to support later.</p>
 */
public final class SmafFileFormat {
	private static final long CHUNK_HEADER_SIZE = 8;
	private static final long FILE_CRC_SIZE = 2;

	public enum TrackType {
		SCORE,
		PCM_AUDIO
	}

	public enum FormatType {
		HANDY_PHONE_STANDARD(2, false),
		MOBILE_STANDARD_COMPRESS(16, true),
		MOBILE_STANDARD_NO_COMPRESS(16, false),
		UNKNOWN(0, false);

		private final int channelStatusBytes;
		private final boolean compressed;

		FormatType(int channelStatusBytes, boolean compressed) {
			this.channelStatusBytes = channelStatusBytes;
			this.compressed = compressed;
		}
	}

	public enum SequenceType {
		STREAM_SEQUENCE,
		SUBSEQUENCE,
		UNKNOWN
	}

	public enum WaveformSource {
		AWA,
		MWA
	}

	public enum WaveformCodec {
		SIGNED_PCM,
		OFFSET_BINARY_PCM,
		YAMAHA_ADPCM,
		TWIN_VQ,
		MP3,
		UNKNOWN
	}

	public static final class TrackInfo {
		private final TrackType trackType;
		private final FormatType formatType;
		private final SequenceType sequenceType;
		private final int durationTimeBaseMs;
		private final int gateTimeTimeBaseMs;

		private TrackInfo(TrackType trackType, FormatType formatType,
				SequenceType sequenceType, int durationTimeBaseMs, int gateTimeTimeBaseMs) {
			this.trackType = trackType;
			this.formatType = formatType;
			this.sequenceType = sequenceType;
			this.durationTimeBaseMs = durationTimeBaseMs;
			this.gateTimeTimeBaseMs = gateTimeTimeBaseMs;
		}

		public TrackType getTrackType() {
			return trackType;
		}

		public FormatType getFormatType() {
			return formatType;
		}

		public SequenceType getSequenceType() {
			return sequenceType;
		}

		public int getDurationTimeBaseMs() {
			return durationTimeBaseMs;
		}

		public int getGateTimeTimeBaseMs() {
			return gateTimeTimeBaseMs;
		}

		public boolean isCompressed() {
			return formatType.compressed;
		}
	}

	public static final class WaveformInfo {
		private final WaveformSource source;
		private final WaveformCodec codec;
		private final int channels;
		private final int sampleRateHz;
		private final int bitsPerSample;

		private WaveformInfo(WaveformSource source, WaveformCodec codec,
				int channels, int sampleRateHz, int bitsPerSample) {
			this.source = source;
			this.codec = codec;
			this.channels = channels;
			this.sampleRateHz = sampleRateHz;
			this.bitsPerSample = bitsPerSample;
		}

		public WaveformSource getSource() {
			return source;
		}

		public WaveformCodec getCodec() {
			return codec;
		}

		public int getChannels() {
			return channels;
		}

		/** Returns the sample rate in Hz, or -1 when the encoded value is unknown. */
		public int getSampleRateHz() {
			return sampleRateHz;
		}

		public int getBitsPerSample() {
			return bitsPerSample;
		}
	}

	public static final class Info {
		private int scoreTrackCount;
		private int pcmAudioTrackCount;
		private int awaCount;
		private int mwaCount;
		private TrackInfo firstScoreTrack;
		private TrackInfo firstPcmAudioTrack;
		private WaveformInfo firstAwa;
		private WaveformInfo firstMwa;

		private Info() {
		}

		public int getScoreTrackCount() {
			return scoreTrackCount;
		}

		public int getPcmAudioTrackCount() {
			return pcmAudioTrackCount;
		}

		public int getAwaCount() {
			return awaCount;
		}

		public int getMwaCount() {
			return mwaCount;
		}

		public boolean hasScore() {
			return scoreTrackCount > 0;
		}

		public boolean hasPcmAudioTrack() {
			return pcmAudioTrackCount > 0;
		}

		public boolean hasAwa() {
			return awaCount > 0;
		}

		public boolean hasMwa() {
			return mwaCount > 0;
		}

		public TrackInfo getFirstScoreTrack() {
			return firstScoreTrack;
		}

		public TrackInfo getFirstPcmAudioTrack() {
			return firstPcmAudioTrack;
		}

		public WaveformInfo getFirstAwa() {
			return firstAwa;
		}

		public WaveformInfo getFirstMwa() {
			return firstMwa;
		}
	}

	private SmafFileFormat() {
	}

	/** Returns parsed metadata, or {@code null} when MMMD structure is invalid/incomplete. */
	public static Info inspect(File file) throws IOException {
		if (file == null || !file.isFile()) {
			return null;
		}

		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			try {
				long fileLength = input.length();
				if (fileLength < CHUNK_HEADER_SIZE + FILE_CRC_SIZE || !matches(input, "MMMD")) {
					return null;
				}

				long mmmdSize = readUnsignedInt(input);
				if (mmmdSize < FILE_CRC_SIZE || mmmdSize != fileLength - CHUNK_HEADER_SIZE) {
					return null;
				}

				long chunksEnd = fileLength - FILE_CRC_SIZE;
				Info info = new Info();
				if (!parseChunks(input, input.getFilePointer(), chunksEnd, info, null, 0)) {
					return null;
				}
				return info;
			} catch (EOFException e) {
				return null;
			}
		}
	}

	private static boolean parseChunks(RandomAccessFile input, long start, long end,
			Info info, WaveformInfo inheritedAwaInfo, int depth) throws IOException {
		if (depth > 8) {
			return false;
		}
		long position = start;
		while (position < end) {
			if (end - position < CHUNK_HEADER_SIZE) {
				return false;
			}

			input.seek(position);
			byte[] id = new byte[4];
			input.readFully(id);
			long chunkSize = readUnsignedInt(input);
			long dataStart = position + CHUNK_HEADER_SIZE;
			if (chunkSize > end - dataStart) {
				return false;
			}
			long dataEnd = dataStart + chunkSize;

			if (startsWith(id, 'M', 'T', 'R')) {
				if (!parseScoreTrack(input, dataStart, dataEnd, info, depth + 1)) {
					return false;
				}
			} else if (startsWith(id, 'A', 'T', 'R')) {
				if (!parsePcmAudioTrack(input, dataStart, dataEnd, info, depth + 1)) {
					return false;
				}
			} else if (matches(id, 'M', 't', 's', 'p')) {
				if (!parseChunks(input, dataStart, dataEnd, info, inheritedAwaInfo, depth + 1)) {
					return false;
				}
			} else if (startsWith(id, 'A', 'w', 'a')) {
				info.awaCount++;
				if (info.firstAwa == null && inheritedAwaInfo != null) {
					// Awa payload inherits wave metadata from its ATR track header.
					info.firstAwa = inheritedAwaInfo;
				}
			} else if (startsWith(id, 'M', 'w', 'a')) {
				WaveformInfo waveform = parseMwa(input, dataStart, dataEnd);
				if (waveform == null) {
					return false;
				}
				info.mwaCount++;
				if (info.firstMwa == null) {
					info.firstMwa = waveform;
				}
			}

			position = dataEnd;
		}
		return position == end;
	}

	private static boolean parseScoreTrack(RandomAccessFile input, long start, long end,
			Info info, int depth) throws IOException {
		if (end - start < 4) {
			return false;
		}
		input.seek(start);
		FormatType formatType = formatType(input.readUnsignedByte());
		SequenceType sequenceType = sequenceType(input.readUnsignedByte());
		int durationTimeBaseMs = timeBaseMs(input.readUnsignedByte());
		int gateTimeTimeBaseMs = timeBaseMs(input.readUnsignedByte());
		TrackInfo track = new TrackInfo(TrackType.SCORE, formatType, sequenceType,
				durationTimeBaseMs, gateTimeTimeBaseMs);

		info.scoreTrackCount++;
		if (info.firstScoreTrack == null) {
			info.firstScoreTrack = track;
		}

		if (formatType == FormatType.UNKNOWN) {
			return true;
		}
		long nestedStart = start + 4L + formatType.channelStatusBytes;
		if (nestedStart > end) {
			return false;
		}
		return parseChunks(input, nestedStart, end, info, null, depth);
	}

	private static boolean parsePcmAudioTrack(RandomAccessFile input, long start, long end,
			Info info, int depth) throws IOException {
		if (end - start < 6) {
			return false;
		}
		input.seek(start);
		FormatType formatType = formatType(input.readUnsignedByte());
		SequenceType sequenceType = sequenceType(input.readUnsignedByte());
		int waveType = input.readUnsignedShort();
		int durationTimeBaseMs = timeBaseMs(input.readUnsignedByte());
		int gateTimeTimeBaseMs = timeBaseMs(input.readUnsignedByte());
		TrackInfo track = new TrackInfo(TrackType.PCM_AUDIO, formatType, sequenceType,
				durationTimeBaseMs, gateTimeTimeBaseMs);

		info.pcmAudioTrackCount++;
		if (info.firstPcmAudioTrack == null) {
			info.firstPcmAudioTrack = track;
		}

		WaveformInfo awaInfo = decodeAwaWaveType(waveType);
		return parseChunks(input, start + 6, end, info, awaInfo, depth);
	}

	private static WaveformInfo parseMwa(RandomAccessFile input, long start, long end) throws IOException {
		if (end - start < 3) {
			return null;
		}
		input.seek(start);
		int first = input.readUnsignedByte();
		int channels = (first & 0x80) != 0 ? 2 : 1;
		int codecCode = (first >>> 4) & 0x07;
		int bitsPerSample = 4 * ((first & 0x0f) + 1);
		int sampleRateHz = input.readUnsignedShort();
		return new WaveformInfo(WaveformSource.MWA, mwaCodec(codecCode), channels,
				sampleRateHz, bitsPerSample);
	}

	private static WaveformInfo decodeAwaWaveType(int waveType) {
		int channels = (waveType & 0x8000) != 0 ? 2 : 1;
		int codecCode = (waveType >>> 12) & 0x07;
		int rateCode = (waveType >>> 8) & 0x0f;
		int bitsPerSample = 4 * (((waveType >>> 4) & 0x0f) + 1);
		return new WaveformInfo(WaveformSource.AWA, awaCodec(codecCode), channels,
				awaSampleRateHz(rateCode), bitsPerSample);
	}

	private static FormatType formatType(int value) {
		return switch (value) {
			case 0 -> FormatType.HANDY_PHONE_STANDARD;
			case 1 -> FormatType.MOBILE_STANDARD_COMPRESS;
			case 2 -> FormatType.MOBILE_STANDARD_NO_COMPRESS;
			default -> FormatType.UNKNOWN;
		};
	}

	private static SequenceType sequenceType(int value) {
		return switch (value) {
			case 0 -> SequenceType.STREAM_SEQUENCE;
			case 1 -> SequenceType.SUBSEQUENCE;
			default -> SequenceType.UNKNOWN;
		};
	}

	private static int timeBaseMs(int value) {
		return switch (value) {
			case 0x00 -> 1;
			case 0x01 -> 2;
			case 0x02 -> 4;
			case 0x03 -> 5;
			case 0x10 -> 10;
			case 0x11 -> 20;
			case 0x12 -> 40;
			case 0x13 -> 50;
			default -> -1;
		};
	}

	private static WaveformCodec awaCodec(int value) {
		return switch (value) {
			case 0 -> WaveformCodec.SIGNED_PCM;
			case 1 -> WaveformCodec.YAMAHA_ADPCM;
			case 2 -> WaveformCodec.TWIN_VQ;
			case 3 -> WaveformCodec.MP3;
			default -> WaveformCodec.UNKNOWN;
		};
	}

	private static WaveformCodec mwaCodec(int value) {
		return switch (value) {
			case 0 -> WaveformCodec.SIGNED_PCM;
			case 1 -> WaveformCodec.OFFSET_BINARY_PCM;
			case 2 -> WaveformCodec.YAMAHA_ADPCM;
			default -> WaveformCodec.UNKNOWN;
		};
	}

	private static int awaSampleRateHz(int value) {
		return switch (value) {
			case 0 -> 4000;
			case 1 -> 8000;
			case 2 -> 11025;
			case 3 -> 22050;
			case 4 -> 44100;
			default -> -1;
		};
	}

	private static boolean matches(RandomAccessFile input, String expected) throws IOException {
		byte[] value = new byte[4];
		input.readFully(value);
		return value[0] == expected.charAt(0)
				&& value[1] == expected.charAt(1)
				&& value[2] == expected.charAt(2)
				&& value[3] == expected.charAt(3);
	}

	private static boolean matches(byte[] value, int a, int b, int c, int d) {
		return (value[0] & 0xff) == a
				&& (value[1] & 0xff) == b
				&& (value[2] & 0xff) == c
				&& (value[3] & 0xff) == d;
	}

	private static boolean startsWith(byte[] value, int a, int b, int c) {
		return (value[0] & 0xff) == a
				&& (value[1] & 0xff) == b
				&& (value[2] & 0xff) == c;
	}

	private static long readUnsignedInt(RandomAccessFile input) throws IOException {
		return (long) input.readUnsignedByte() << 24
				| (long) input.readUnsignedByte() << 16
				| (long) input.readUnsignedByte() << 8
				| input.readUnsignedByte();
	}
}
