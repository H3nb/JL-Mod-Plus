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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts the deliberately small R4 SMAF score subset to Standard MIDI File
 * data for the existing SONiVOX backend.
 *
 * <p>The source remains SMAF at the MMAPI boundary. This class is only an
 * internal renderer adapter; it does not reclassify MMMD data as MIDI and it
 * returns {@code null} for SMAF features that are outside the proven subset.</p>
 */
public final class SmafSequenceConverter {
	public static final String CONTENT_TYPE = "application/vnd.smaf";

	private static final int CHUNK_HEADER_SIZE = 8;
	private static final int MIDI_DIVISION = 1000;
	private static final int SUPPORTED_TIME_BASE_MS = 4;
	private static final long MAX_MIDI_VLQ = 0x0fffffffL;

	private static final int PRIORITY_SETUP = 0;
	private static final int PRIORITY_CONTROL = 1;
	private static final int PRIORITY_NOTE_OFF = 2;
	private static final int PRIORITY_NOTE_ON = 3;

	private static final int[] HPS_MODULATION = {
			-1, 0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30,
			0x38, 0x40, 0x48, 0x50, 0x60, 0x70, 0x7f, -1
	};

	private SmafSequenceConverter() {
	}

	/**
	 * Returns a Standard MIDI File for the supported score-only subset, or
	 * {@code null} when the file is malformed or uses an unsupported SMAF feature.
	 */
	public static byte[] convert(File file) throws IOException {
		SmafFileFormat.Info info = SmafFileFormat.inspect(file);
		if (!isSupportedFoundation(info)) {
			return null;
		}

		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			try {
				TrackBounds track = findSingleScoreTrack(input);
				if (track == null) {
					return null;
				}
				return convertTrack(input, track);
			} catch (UnsupportedSmafException | EOFException e) {
				return null;
			}
		}
	}

	private static boolean isSupportedFoundation(SmafFileFormat.Info info) {
		if (info == null || info.getScoreTrackCount() != 1
				|| info.hasPcmAudioTrack() || info.hasAwa() || info.hasMwa()) {
			return false;
		}
		SmafFileFormat.TrackInfo track = info.getFirstScoreTrack();
		if (track == null || track.getSequenceType() != SmafFileFormat.SequenceType.STREAM_SEQUENCE
				|| track.getDurationTimeBaseMs() != SUPPORTED_TIME_BASE_MS
				|| track.getGateTimeTimeBaseMs() != SUPPORTED_TIME_BASE_MS) {
			return false;
		}
		return track.getFormatType() == SmafFileFormat.FormatType.HANDY_PHONE_STANDARD
				|| track.getFormatType() == SmafFileFormat.FormatType.MOBILE_STANDARD_NO_COMPRESS;
	}

	private static TrackBounds findSingleScoreTrack(RandomAccessFile input)
			throws IOException, UnsupportedSmafException {
		long length = input.length();
		if (length < 10) {
			return null;
		}
		long end = length - 2;
		long position = 8;
		TrackBounds found = null;
		while (position < end) {
			Chunk chunk = readChunk(input, position, end);
			if (startsWith(chunk.id, 'M', 'T', 'R')) {
				if (found != null) {
					return null;
				}
				found = new TrackBounds(chunk.dataStart, chunk.dataEnd);
			}
			position = chunk.dataEnd;
		}
		return position == end ? found : null;
	}

	private static byte[] convertTrack(RandomAccessFile input, TrackBounds track)
			throws IOException, UnsupportedSmafException {
		input.seek(track.start);
		int format = readByte(input, track.end);
		int sequenceType = readByte(input, track.end);
		int durationTimeBase = readByte(input, track.end);
		int gateTimeBase = readByte(input, track.end);
		if (sequenceType != 0 || durationTimeBase != 0x02 || gateTimeBase != 0x02) {
			throw new UnsupportedSmafException();
		}

		int channelStatusSize;
		if (format == 0) {
			channelStatusSize = 2;
		} else if (format == 2) {
			channelStatusSize = 16;
		} else {
			throw new UnsupportedSmafException();
		}
		byte[] channelStatus = readBytes(input, track.end, channelStatusSize);

		List<MidiEvent> events = new ArrayList<>();
		long nested = input.getFilePointer();
		Chunk sequence = null;
		while (nested < track.end) {
			Chunk chunk = readChunk(input, nested, track.end);
			if (matches(chunk.id, 'M', 't', 's', 'u')) {
				parseSetup(input, chunk, format, events);
			} else if (matches(chunk.id, 'M', 't', 's', 'q')) {
				if (sequence != null) {
					throw new UnsupportedSmafException();
				}
				sequence = chunk;
			} else if (matches(chunk.id, 'M', 't', 's', 'p')) {
				throw new UnsupportedSmafException();
			}
			nested = chunk.dataEnd;
		}
		if (nested != track.end || sequence == null) {
			throw new UnsupportedSmafException();
		}

		long endTick;
		if (format == 0) {
			endTick = parseHandyPhoneSequence(input, sequence, channelStatus, events);
		} else {
			endTick = parseMobileSequence(input, sequence, channelStatus, events);
		}
		return writeSmf(events, endTick);
	}

	private static void parseSetup(RandomAccessFile input, Chunk chunk, int format,
			List<MidiEvent> events) throws IOException, UnsupportedSmafException {
		input.seek(chunk.dataStart);
		while (input.getFilePointer() < chunk.dataEnd) {
			if (format == 0) {
				if (readByte(input, chunk.dataEnd) != 0xff
						|| readByte(input, chunk.dataEnd) != 0xf0) {
					throw new UnsupportedSmafException();
				}
				int length = readByte(input, chunk.dataEnd);
				addSysex(events, 0, readBytes(input, chunk.dataEnd, length));
			} else {
				if (readByte(input, chunk.dataEnd) != 0xf0) {
					throw new UnsupportedSmafException();
				}
				int length = readMidiVlq(input, chunk.dataEnd);
				addSysex(events, 0, readBytes(input, chunk.dataEnd, length));
			}
		}
	}

	private static long parseHandyPhoneSequence(RandomAccessFile input, Chunk sequence,
			byte[] channelStatus, List<MidiEvent> events)
			throws IOException, UnsupportedSmafException {
		HpsChannel[] channels = new HpsChannel[4];
		for (int i = 0; i < channels.length; i++) {
			int packed = channelStatus[i / 2] & 0xff;
			int nibble = (packed >>> ((i & 1) == 0 ? 4 : 0)) & 0x0f;
			channels[i] = new HpsChannel((nibble & 0x03) == 0x03);
		}

		input.seek(sequence.dataStart);
		long tick = 0;
		long farthest = 0;
		while (input.getFilePointer() < sequence.dataEnd) {
			int duration = readHpsVariableLength(input, sequence.dataEnd);
			tick = addTime(tick, duration);
			farthest = Math.max(farthest, tick);
			int event = readByte(input, sequence.dataEnd);

			if (event == 0xff) {
				int subtype = readByte(input, sequence.dataEnd);
				if (subtype == 0x00) {
					continue;
				}
				if (subtype == 0xf0) {
					int length = readByte(input, sequence.dataEnd);
					addSysex(events, tick, readBytes(input, sequence.dataEnd, length));
					continue;
				}
				if (subtype == 0x2f || subtype == 0x58) {
					int length = readByte(input, sequence.dataEnd);
					readBytes(input, sequence.dataEnd, length);
					continue;
				}
				throw new UnsupportedSmafException();
			}

			if (event != 0x00) {
				int channel = (event >>> 6) & 0x03;
				int octave = (event >>> 4) & 0x03;
				int note = event & 0x0f;
				if (note < 1 || note > 12) {
					throw new UnsupportedSmafException();
				}
				int gate = readHpsVariableLength(input, sequence.dataEnd);
				if (gate == 0) {
					continue;
				}
				HpsChannel state = channels[channel];
				int midiChannel = state.percussion ? 9 : channel;
				int pitch;
				if (state.percussion) {
					pitch = state.program;
				} else {
					pitch = 36 + note + octave * 12 + state.octaveShift;
				}
				if (!isDataByte(pitch)) {
					throw new UnsupportedSmafException();
				}
				long noteOff = addTime(tick, gate);
				addChannelEvent(events, tick, PRIORITY_NOTE_ON,
						0x90 | midiChannel, pitch, 0x7f);
				addChannelEvent(events, noteOff, PRIORITY_NOTE_OFF,
						0x80 | midiChannel, pitch, 0);
				farthest = Math.max(farthest, noteOff);
				continue;
			}

			int control = readByte(input, sequence.dataEnd);
			if (control == 0x00) {
				if (readByte(input, sequence.dataEnd) != 0x00) {
					throw new UnsupportedSmafException();
				}
				continue;
			}

			int channel = (control >>> 6) & 0x03;
			int form = (control >>> 4) & 0x03;
			int data = control & 0x0f;
			HpsChannel state = channels[channel];
			if (form == 3) {
				int value = readByte(input, sequence.dataEnd);
				if (!isDataByte(value) && data != 1 && data != 2) {
					throw new UnsupportedSmafException();
				}
				switch (data) {
					case 0 -> {
						state.program = value & 0x7f;
						if (!state.percussion) {
							addShortEvent(events, tick, PRIORITY_CONTROL,
									0xc0 | channel, state.program);
						}
					}
					case 1 -> state.percussion = (value & 0x80) != 0;
					case 2 -> state.octaveShift = octaveShift(value);
					case 3 -> addControl(events, tick, state, channel, 1, value);
					case 4 -> addPitchBend(events, tick, state, channel, value << 7);
					case 7 -> addControl(events, tick, state, channel, 7, value);
					case 0x0a -> addControl(events, tick, state, channel, 10, value);
					case 0x0b -> addControl(events, tick, state, channel, 11, value);
					default -> throw new UnsupportedSmafException();
				}
			} else if (form == 2) {
				int value = data < HPS_MODULATION.length ? HPS_MODULATION[data] : -1;
				if (value < 0) {
					throw new UnsupportedSmafException();
				}
				addControl(events, tick, state, channel, 1, value);
			} else if (form == 1) {
				if (data < 1 || data > 14) {
					throw new UnsupportedSmafException();
				}
				addPitchBend(events, tick, state, channel, (data * 8) << 7);
			} else {
				if (data < 1 || data > 14) {
					throw new UnsupportedSmafException();
				}
				addControl(events, tick, state, channel, 11,
						data == 1 ? 0 : data * 8 + 15);
			}
		}
		return farthest;
	}

	private static long parseMobileSequence(RandomAccessFile input, Chunk sequence,
			byte[] channelStatus, List<MidiEvent> events)
			throws IOException, UnsupportedSmafException {
		for (int channel = 0; channel < channelStatus.length; channel++) {
			int type = channelStatus[channel] & 0x03;
			if (type == 0x03 && channel != 9) {
				throw new UnsupportedSmafException();
			}
		}

		int[] velocity = new int[16];
		for (int i = 0; i < velocity.length; i++) {
			velocity[i] = 64;
		}

		input.seek(sequence.dataStart);
		long tick = 0;
		long farthest = 0;
		while (input.getFilePointer() < sequence.dataEnd) {
			int duration = readMidiVlq(input, sequence.dataEnd);
			tick = addTime(tick, duration);
			farthest = Math.max(farthest, tick);
			int status = readByte(input, sequence.dataEnd);
			int channel = status & 0x0f;

			if (status >= 0x80 && status <= 0x8f) {
				int note = readDataByte(input, sequence.dataEnd);
				int gate = readMidiVlq(input, sequence.dataEnd);
				long noteOff = addTime(tick, gate);
				addChannelEvent(events, tick, PRIORITY_NOTE_ON, 0x90 | channel, note, velocity[channel]);
				addChannelEvent(events, noteOff, PRIORITY_NOTE_OFF, 0x80 | channel, note, 0);
				farthest = Math.max(farthest, noteOff);
			} else if (status >= 0x90 && status <= 0x9f) {
				int note = readDataByte(input, sequence.dataEnd);
				int value = readDataByte(input, sequence.dataEnd);
				velocity[channel] = value;
				int gate = readMidiVlq(input, sequence.dataEnd);
				long noteOff = addTime(tick, gate);
				addChannelEvent(events, tick, PRIORITY_NOTE_ON, status, note, value);
				addChannelEvent(events, noteOff, PRIORITY_NOTE_OFF, 0x80 | channel, note, 0);
				farthest = Math.max(farthest, noteOff);
			} else if (status >= 0xb0 && status <= 0xbf) {
				addChannelEvent(events, tick, PRIORITY_CONTROL, status,
						readDataByte(input, sequence.dataEnd), readDataByte(input, sequence.dataEnd));
			} else if (status >= 0xc0 && status <= 0xcf) {
				addShortEvent(events, tick, PRIORITY_CONTROL, status,
						readDataByte(input, sequence.dataEnd));
			} else if (status >= 0xe0 && status <= 0xef) {
				addChannelEvent(events, tick, PRIORITY_CONTROL, status,
						readDataByte(input, sequence.dataEnd), readDataByte(input, sequence.dataEnd));
			} else if (status == 0xff) {
				int subtype = readByte(input, sequence.dataEnd);
				if (subtype == 0x00) {
					continue;
				}
				if (subtype == 0x2f && readByte(input, sequence.dataEnd) == 0) {
					continue;
				}
				throw new UnsupportedSmafException();
			} else if (status == 0xf0) {
				int length = readMidiVlq(input, sequence.dataEnd);
				addSysex(events, tick, readBytes(input, sequence.dataEnd, length));
			} else {
				throw new UnsupportedSmafException();
			}
		}
		return farthest;
	}

	private static void addControl(List<MidiEvent> events, long tick, HpsChannel state,
			int smafChannel, int controller, int value) throws UnsupportedSmafException {
		if (!isDataByte(value)) {
			throw new UnsupportedSmafException();
		}
		int midiChannel = state.percussion ? 9 : smafChannel;
		addChannelEvent(events, tick, PRIORITY_CONTROL, 0xb0 | midiChannel, controller, value);
	}

	private static void addPitchBend(List<MidiEvent> events, long tick, HpsChannel state,
			int smafChannel, int value) throws UnsupportedSmafException {
		if (value < 0 || value > 0x3fff) {
			throw new UnsupportedSmafException();
		}
		int midiChannel = state.percussion ? 9 : smafChannel;
		addChannelEvent(events, tick, PRIORITY_CONTROL, 0xe0 | midiChannel,
				value & 0x7f, (value >>> 7) & 0x7f);
	}

	private static int octaveShift(int value) throws UnsupportedSmafException {
		return switch (value) {
			case 0 -> 0;
			case 1 -> 12;
			case 2 -> 24;
			case 3 -> 36;
			case 4 -> 48;
			case 0x81 -> -12;
			case 0x82 -> -24;
			case 0x83 -> -36;
			case 0x84 -> -48;
			default -> throw new UnsupportedSmafException();
		};
	}

	private static void addSysex(List<MidiEvent> events, long tick, byte[] data)
			throws UnsupportedSmafException {
		if (data.length > MAX_MIDI_VLQ) {
			throw new UnsupportedSmafException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 6);
		out.write(0xf0);
		writeVlq(out, data.length);
		out.write(data, 0, data.length);
		events.add(new MidiEvent(tick, PRIORITY_SETUP, events.size(), out.toByteArray()));
	}

	private static void addChannelEvent(List<MidiEvent> events, long tick, int priority,
			int status, int data1, int data2) {
		events.add(new MidiEvent(tick, priority, events.size(), new byte[]{
				(byte) status, (byte) data1, (byte) data2}));
	}

	private static void addShortEvent(List<MidiEvent> events, long tick, int priority,
			int status, int data1) {
		events.add(new MidiEvent(tick, priority, events.size(), new byte[]{
				(byte) status, (byte) data1}));
	}

	private static byte[] writeSmf(List<MidiEvent> events, long endTick)
			throws UnsupportedSmafException {
		java.util.Collections.sort(events, (left, right) -> {
			if (left.tick != right.tick) {
				return left.tick < right.tick ? -1 : 1;
			}
			if (left.priority != right.priority) {
				return left.priority < right.priority ? -1 : 1;
			}
			return Integer.compare(left.order, right.order);
		});

		ByteArrayOutputStream track = new ByteArrayOutputStream();
		track.write(0x00);
		track.write(0xff);
		track.write(0x51);
		track.write(0x03);
		track.write(0x0f);
		track.write(0x42);
		track.write(0x40);

		long lastTick = 0;
		for (MidiEvent event : events) {
			if (event.tick < lastTick) {
				throw new UnsupportedSmafException();
			}
			writeVlq(track, event.tick - lastTick);
			track.write(event.data, 0, event.data.length);
			lastTick = event.tick;
			endTick = Math.max(endTick, event.tick);
		}
		writeVlq(track, endTick - lastTick);
		track.write(0xff);
		track.write(0x2f);
		track.write(0x00);

		byte[] trackData = track.toByteArray();
		ByteArrayOutputStream file = new ByteArrayOutputStream(trackData.length + 22);
		writeAscii(file, "MThd");
		writeInt(file, 6);
		writeShort(file, 0);
		writeShort(file, 1);
		writeShort(file, MIDI_DIVISION);
		writeAscii(file, "MTrk");
		writeInt(file, trackData.length);
		file.write(trackData, 0, trackData.length);
		return file.toByteArray();
	}

	private static long addTime(long tick, int units) throws UnsupportedSmafException {
		long delta = (long) units * SUPPORTED_TIME_BASE_MS;
		if (delta < 0 || delta > MAX_MIDI_VLQ || tick > Long.MAX_VALUE - delta) {
			throw new UnsupportedSmafException();
		}
		return tick + delta;
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

	private static int readMidiVlq(RandomAccessFile input, long end)
			throws IOException, UnsupportedSmafException {
		int value = 0;
		for (int i = 0; i < 4; i++) {
			int b = readByte(input, end);
			value = (value << 7) | (b & 0x7f);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new UnsupportedSmafException();
	}

	private static int readDataByte(RandomAccessFile input, long end)
			throws IOException, UnsupportedSmafException {
		int value = readByte(input, end);
		if (!isDataByte(value)) {
			throw new UnsupportedSmafException();
		}
		return value;
	}

	private static int readByte(RandomAccessFile input, long end) throws IOException {
		if (input.getFilePointer() >= end) {
			throw new EOFException();
		}
		return input.readUnsignedByte();
	}

	private static byte[] readBytes(RandomAccessFile input, long end, int length)
			throws IOException, UnsupportedSmafException {
		if (length < 0 || (long) length > end - input.getFilePointer()) {
			throw new UnsupportedSmafException();
		}
		byte[] data = new byte[length];
		input.readFully(data);
		return data;
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

	private static boolean isDataByte(int value) {
		return value >= 0 && value <= 0x7f;
	}

	private static void writeVlq(ByteArrayOutputStream out, long value)
			throws UnsupportedSmafException {
		if (value < 0 || value > MAX_MIDI_VLQ) {
			throw new UnsupportedSmafException();
		}
		int buffer = (int) (value & 0x7f);
		while ((value >>>= 7) != 0) {
			buffer <<= 8;
			buffer |= (int) ((value & 0x7f) | 0x80);
		}
		while (true) {
			out.write(buffer & 0xff);
			if ((buffer & 0x80) != 0) {
				buffer >>>= 8;
			} else {
				break;
			}
		}
	}

	private static void writeAscii(ByteArrayOutputStream out, String value) {
		for (int i = 0; i < value.length(); i++) {
			out.write(value.charAt(i));
		}
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >>> 8) & 0xff);
		out.write(value & 0xff);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write((value >>> 24) & 0xff);
		out.write((value >>> 16) & 0xff);
		out.write((value >>> 8) & 0xff);
		out.write(value & 0xff);
	}

	private static final class TrackBounds {
		final long start;
		final long end;

		TrackBounds(long start, long end) {
			this.start = start;
			this.end = end;
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

	private static final class HpsChannel {
		boolean percussion;
		int program;
		int octaveShift;

		HpsChannel(boolean percussion) {
			this.percussion = percussion;
		}
	}

	private static final class MidiEvent {
		final long tick;
		final int priority;
		final int order;
		final byte[] data;

		MidiEvent(long tick, int priority, int order, byte[] data) {
			this.tick = tick;
			this.priority = priority;
			this.order = order;
			this.data = data;
		}
	}

	private static final class UnsupportedSmafException extends Exception {
	}
}
