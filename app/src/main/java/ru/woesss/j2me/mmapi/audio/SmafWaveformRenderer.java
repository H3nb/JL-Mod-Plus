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

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Renders the deliberately small R5 SMAF PCM-audio subset to a temporary
 * PCM16 WAVE file for the existing dr_wav backend.
 *
 * <p>The supported slice is audio-only Handy Phone Standard ATR with Awa
 * Yamaha ADPCM, mono, 4-bit, StreamSequence, and 4 ms duration/gate timebase.
 * Unsupported constructs are rejected rather than approximated.</p>
 */
public final class SmafWaveformRenderer {
	public static final String CONTENT_TYPE = "application/vnd.smaf";

	private static final int CHUNK_HEADER_SIZE = 8;
	private static final int TIME_BASE_MS = 4;
	private static final long MAX_PCM_BYTES = 64L * 1024L * 1024L;
	private static final int[] YAMAHA_INDEX_SCALE = {
			230, 230, 230, 230, 307, 409, 512, 614,
			230, 230, 230, 230, 307, 409, 512, 614
	};
	private static final int[] YAMAHA_DIFF_LOOKUP = {
			1, 3, 5, 7, 9, 11, 13, 15,
			-1, -3, -5, -7, -9, -11, -13, -15
	};

	private SmafWaveformRenderer() {
	}

	/**
	 * Renders the supported audio-only SMAF subset into {@code targetWav}.
	 * Returns {@code false} for a valid SMAF file outside the supported subset.
	 */
	public static boolean render(File source, File targetWav) throws IOException {
		if (source == null || targetWav == null) {
			throw new IllegalArgumentException();
		}

		SmafFileFormat.Info info = SmafFileFormat.inspect(source);
		if (!isSupportedFoundation(info)) {
			return false;
		}

		try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
			try {
				Layout layout = readLayout(input, info.getFirstAwa().getSampleRateHz());
				if (layout == null) {
					return false;
				}

				long frames = processTimeline(input, layout, null);
				if (frames < 0 || frames > MAX_PCM_BYTES / 2) {
					return false;
				}

				long dataBytes = frames * 2;
				try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(targetWav))) {
					writeWavHeader(output, layout.sampleRateHz, dataBytes);
					PcmWriter writer = new PcmWriter(output);
					long rendered = processTimeline(input, layout, writer);
					if (rendered != frames) {
						throw new IOException("SMAF waveform timeline changed during render");
					}
					writer.flush();
				}
				return true;
			} catch (UnsupportedSmafException | EOFException e) {
				return false;
			}
		}
	}

	private static boolean isSupportedFoundation(SmafFileFormat.Info info) {
		if (info == null || info.hasScore() || info.getPcmAudioTrackCount() != 1
				|| !info.hasAwa() || info.hasMwa()) {
			return false;
		}
		SmafFileFormat.TrackInfo track = info.getFirstPcmAudioTrack();
		SmafFileFormat.WaveformInfo wave = info.getFirstAwa();
		return track != null
				&& track.getFormatType() == SmafFileFormat.FormatType.HANDY_PHONE_STANDARD
				&& track.getSequenceType() == SmafFileFormat.SequenceType.STREAM_SEQUENCE
				&& track.getDurationTimeBaseMs() == TIME_BASE_MS
				&& track.getGateTimeTimeBaseMs() == TIME_BASE_MS
				&& wave != null
				&& wave.getCodec() == SmafFileFormat.WaveformCodec.YAMAHA_ADPCM
				&& wave.getChannels() == 1
				&& wave.getBitsPerSample() == 4
				&& wave.getSampleRateHz() > 0;
	}

	private static Layout readLayout(RandomAccessFile input, int expectedSampleRate)
			throws IOException, UnsupportedSmafException {
		long fileLength = input.length();
		if (fileLength < 10) {
			return null;
		}

		long chunksEnd = fileLength - 2;
		long position = 8;
		Chunk atr = null;
		while (position < chunksEnd) {
			Chunk chunk = readChunk(input, position, chunksEnd);
			if (startsWith(chunk.id, 'A', 'T', 'R')) {
				if (atr != null) {
					return null;
				}
				atr = chunk;
			}
			position = chunk.dataEnd;
		}
		if (position != chunksEnd || atr == null) {
			return null;
		}

		input.seek(atr.dataStart);
		if (atr.dataEnd - atr.dataStart < 6
				|| readByte(input, atr.dataEnd) != 0
				|| readByte(input, atr.dataEnd) != 0) {
			return null;
		}
		int waveType = readUnsignedShort(input, atr.dataEnd);
		if (readByte(input, atr.dataEnd) != 0x02 || readByte(input, atr.dataEnd) != 0x02) {
			return null;
		}
		if (decodeSampleRate(waveType) != expectedSampleRate
				|| ((waveType >>> 12) & 0x07) != 1
				|| (waveType & 0x8000) != 0
				|| ((waveType >>> 4) & 0x0f) != 0) {
			return null;
		}

		long[] waveOffsets = new long[256];
		long[] waveLengths = new long[256];
		Arrays.fill(waveOffsets, -1L);
		Chunk sequence = null;
		position = atr.dataStart + 6;
		while (position < atr.dataEnd) {
			Chunk chunk = readChunk(input, position, atr.dataEnd);
			if (matches(chunk.id, 'A', 't', 's', 'q')) {
				if (sequence != null) {
					return null;
				}
				sequence = chunk;
			} else if (startsWith(chunk.id, 'A', 'w', 'a')) {
				int number = chunk.id[3] & 0xff;
				if (waveOffsets[number] >= 0) {
					return null;
				}
				waveOffsets[number] = chunk.dataStart;
				waveLengths[number] = chunk.dataEnd - chunk.dataStart;
			} else if (!matches(chunk.id, 'A', 's', 'p', 'I')
					&& !matches(chunk.id, 'A', 't', 's', 'u')) {
				// Unknown ATR children could carry playback semantics we do not model.
				return null;
			}
			position = chunk.dataEnd;
		}
		if (position != atr.dataEnd || sequence == null) {
			return null;
		}
		return new Layout(expectedSampleRate, sequence, waveOffsets, waveLengths);
	}

	/**
	 * Validates and optionally renders the HPS Atsq timeline. Overlap is rejected
	 * in R5 instead of inventing realtime mixing semantics.
	 */
	private static long processTimeline(RandomAccessFile input, Layout layout, PcmWriter writer)
			throws IOException, UnsupportedSmafException {
		input.seek(layout.sequence.dataStart);
		long timeUnits = 0;
		long cursorFrame = 0;
		boolean sawWave = false;
		while (input.getFilePointer() < layout.sequence.dataEnd) {
			int duration = readHpsVariableLength(input, layout.sequence.dataEnd);
			if (timeUnits > Long.MAX_VALUE - duration) {
				throw new UnsupportedSmafException();
			}
			timeUnits += duration;
			int event = readByte(input, layout.sequence.dataEnd);

			if (event == 0xff) {
				int subtype = readByte(input, layout.sequence.dataEnd);
				if (subtype == 0x00) {
					continue;
				}
				if (subtype == 0x2f || subtype == 0x58) {
					int length = readByte(input, layout.sequence.dataEnd);
					skipBytes(input, layout.sequence.dataEnd, length);
					continue;
				}
				// Tempo and SysEx may affect playback; reject until modeled.
				throw new UnsupportedSmafException();
			}

			if (event == 0x00) {
				int second = readByte(input, layout.sequence.dataEnd);
				if (second != 0x00 || readByte(input, layout.sequence.dataEnd) != 0x00) {
					// HPS control events can alter channel playback; unsupported in R5.
					throw new UnsupportedSmafException();
				}
				continue;
			}

			int gateUnits = readHpsVariableLength(input, layout.sequence.dataEnd);
			int waveNumber = event & 0x3f;
			long waveOffset = layout.waveOffsets[waveNumber];
			long waveLength = layout.waveLengths[waveNumber];
			if (waveOffset < 0) {
				throw new UnsupportedSmafException();
			}

			long startFrame = unitsToFrames(timeUnits, layout.sampleRateHz);
			long availableFrames = safeDouble(waveLength);
			long playFrames = availableFrames;
			if (gateUnits > 0) {
				long gateFrames = unitsToFrames(gateUnits, layout.sampleRateHz);
				playFrames = Math.min(playFrames, gateFrames);
			}
			if (startFrame < cursorFrame || playFrames > Long.MAX_VALUE - startFrame) {
				throw new UnsupportedSmafException();
			}

			if (writer != null) {
				writer.writeSilence(startFrame - cursorFrame);
				long sequencePosition = input.getFilePointer();
				decodeYamahaAdpcm(input, waveOffset, waveLength, playFrames, writer);
				input.seek(sequencePosition);
			}
			cursorFrame = startFrame + playFrames;
			sawWave = true;
		}
		if (!sawWave) {
			return -1;
		}
		long sequenceEndFrame = unitsToFrames(timeUnits, layout.sampleRateHz);
		long endFrame = Math.max(cursorFrame, sequenceEndFrame);
		if (writer != null) {
			writer.writeSilence(endFrame - cursorFrame);
		}
		return endFrame;
	}

	private static void decodeYamahaAdpcm(RandomAccessFile input, long offset, long length,
			long frames, PcmWriter writer) throws IOException, UnsupportedSmafException {
		if (frames < 0 || frames > safeDouble(length)) {
			throw new UnsupportedSmafException();
		}
		input.seek(offset);
		int predictor = 0;
		int step = 127;
		long remaining = frames;
		for (long i = 0; i < length && remaining > 0; i++) {
			int value = input.readUnsignedByte();
			int nibble = value & 0x0f;
			predictor = decodeNibble(predictor, step, nibble);
			step = nextStep(step, nibble);
			writer.writeSample(predictor);
			remaining--;
			if (remaining == 0) {
				break;
			}
			nibble = (value >>> 4) & 0x0f;
			predictor = decodeNibble(predictor, step, nibble);
			step = nextStep(step, nibble);
			writer.writeSample(predictor);
			remaining--;
		}
		if (remaining != 0) {
			throw new EOFException();
		}
	}

	private static int decodeNibble(int predictor, int step, int nibble) {
		int value = predictor + (step * YAMAHA_DIFF_LOOKUP[nibble]) / 8;
		if (value > 32767) {
			return 32767;
		}
		return Math.max(value, -32768);
	}

	private static int nextStep(int step, int nibble) {
		int value = (step * YAMAHA_INDEX_SCALE[nibble]) >> 8;
		if (value < 127) {
			return 127;
		}
		return Math.min(value, 24576);
	}

	private static long unitsToFrames(long units, int sampleRate) throws UnsupportedSmafException {
		if (units < 0 || units > Long.MAX_VALUE / TIME_BASE_MS) {
			throw new UnsupportedSmafException();
		}
		long millis = units * TIME_BASE_MS;
		if (millis > (Long.MAX_VALUE - 500) / sampleRate) {
			throw new UnsupportedSmafException();
		}
		return (millis * sampleRate + 500) / 1000;
	}

	private static long safeDouble(long value) throws UnsupportedSmafException {
		if (value < 0 || value > Long.MAX_VALUE / 2) {
			throw new UnsupportedSmafException();
		}
		return value * 2;
	}

	private static int decodeSampleRate(int waveType) {
		return switch ((waveType >>> 8) & 0x0f) {
			case 0 -> 4000;
			case 1 -> 8000;
			case 2 -> 11025;
			case 3 -> 22050;
			case 4 -> 44100;
			default -> -1;
		};
	}

	private static int readHpsVariableLength(RandomAccessFile input, long end)
			throws IOException, UnsupportedSmafException {
		int first = readByte(input, end);
		if ((first & 0x80) == 0) {
			return first;
		}
		int second = readByte(input, end);
		return (((first & 0x7f) + 1) << 7) | second;
	}

	private static int readByte(RandomAccessFile input, long end) throws IOException {
		if (input.getFilePointer() >= end) {
			throw new EOFException();
		}
		return input.readUnsignedByte();
	}

	private static int readUnsignedShort(RandomAccessFile input, long end) throws IOException {
		int high = readByte(input, end);
		return (high << 8) | readByte(input, end);
	}

	private static void skipBytes(RandomAccessFile input, long end, int length)
			throws IOException, UnsupportedSmafException {
		if (length < 0 || length > end - input.getFilePointer()) {
			throw new UnsupportedSmafException();
		}
		input.seek(input.getFilePointer() + length);
	}

	private static Chunk readChunk(RandomAccessFile input, long position, long end)
			throws IOException, UnsupportedSmafException {
		if (position < 0 || end - position < CHUNK_HEADER_SIZE) {
			throw new UnsupportedSmafException();
		}
		input.seek(position);
		byte[] id = new byte[4];
		input.readFully(id);
		long size = Integer.toUnsignedLong(input.readInt());
		long dataStart = position + CHUNK_HEADER_SIZE;
		if (size > end - dataStart) {
			throw new UnsupportedSmafException();
		}
		return new Chunk(id, dataStart, dataStart + size);
	}

	private static boolean startsWith(byte[] id, char a, char b, char c) {
		return id[0] == (byte) a && id[1] == (byte) b && id[2] == (byte) c;
	}

	private static boolean matches(byte[] id, char a, char b, char c, char d) {
		return id[0] == (byte) a && id[1] == (byte) b
				&& id[2] == (byte) c && id[3] == (byte) d;
	}

	private static void writeWavHeader(BufferedOutputStream output, int sampleRate, long dataBytes)
			throws IOException, UnsupportedSmafException {
		if (dataBytes < 0 || dataBytes > 0xffffffffL - 36) {
			throw new UnsupportedSmafException();
		}
		writeAscii(output, "RIFF");
		writeLe32(output, dataBytes + 36);
		writeAscii(output, "WAVE");
		writeAscii(output, "fmt ");
		writeLe32(output, 16);
		writeLe16(output, 1);
		writeLe16(output, 1);
		writeLe32(output, sampleRate);
		writeLe32(output, (long) sampleRate * 2);
		writeLe16(output, 2);
		writeLe16(output, 16);
		writeAscii(output, "data");
		writeLe32(output, dataBytes);
	}

	private static void writeAscii(BufferedOutputStream output, String value) throws IOException {
		for (int i = 0; i < value.length(); i++) {
			output.write(value.charAt(i));
		}
	}

	private static void writeLe16(BufferedOutputStream output, int value) throws IOException {
		output.write(value & 0xff);
		output.write((value >>> 8) & 0xff);
	}

	private static void writeLe32(BufferedOutputStream output, long value) throws IOException {
		output.write((int) value & 0xff);
		output.write((int) (value >>> 8) & 0xff);
		output.write((int) (value >>> 16) & 0xff);
		output.write((int) (value >>> 24) & 0xff);
	}

	private static final class Layout {
		final int sampleRateHz;
		final Chunk sequence;
		final long[] waveOffsets;
		final long[] waveLengths;

		Layout(int sampleRateHz, Chunk sequence, long[] waveOffsets, long[] waveLengths) {
			this.sampleRateHz = sampleRateHz;
			this.sequence = sequence;
			this.waveOffsets = waveOffsets;
			this.waveLengths = waveLengths;
		}
	}

	private static final class Chunk {
		final byte[] id;
		final long dataStart;
		final long dataEnd;

		Chunk(byte[] id, long dataStart, long dataEnd) {
			this.id = id;
			this.dataStart = dataStart;
			this.dataEnd = dataEnd;
		}
	}

	private static final class PcmWriter {
		private static final byte[] SILENCE = new byte[4096];
		private final BufferedOutputStream output;
		private final byte[] buffer = new byte[4096];
		private int size;

		PcmWriter(BufferedOutputStream output) {
			this.output = output;
		}

		void writeSample(int sample) throws IOException {
			if (size > buffer.length - 2) {
				flush();
			}
			buffer[size++] = (byte) sample;
			buffer[size++] = (byte) (sample >>> 8);
		}

		void writeSilence(long frames) throws IOException {
			flush();
			long bytes = frames * 2;
			while (bytes > 0) {
				int count = (int) Math.min(bytes, SILENCE.length);
				output.write(SILENCE, 0, count);
				bytes -= count;
			}
		}

		void flush() throws IOException {
			if (size > 0) {
				output.write(buffer, 0, size);
				size = 0;
			}
		}
	}

	private static final class UnsupportedSmafException extends Exception {
	}
}
