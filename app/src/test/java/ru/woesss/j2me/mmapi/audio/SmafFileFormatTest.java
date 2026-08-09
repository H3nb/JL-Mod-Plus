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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmafFileFormatTest {
	@Test
	public void acceptsGeneratedMinimalMmmd() throws IOException {
		SmafFileFormat.Info info = inspect(mmmd(), ".mmf");

		assertNotNull(info);
		assertFalse(info.hasScore());
		assertFalse(info.hasPcmAudioTrack());
		assertFalse(info.hasAwa());
		assertFalse(info.hasMwa());
	}

	@Test
	public void rejectsFakeMmfWithoutMmmd() throws IOException {
		assertNull(inspect(new byte[]{'n', 'o', 't', ' ', 's', 'm', 'a', 'f'}, ".mmf"));
	}

	@Test
	public void rejectsTruncatedChunkHeader() throws IOException {
		ByteBuffer file = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
		file.put(new byte[]{'M', 'M', 'M', 'D'});
		file.putInt(6);
		file.put(new byte[]{'J', 'U', 'N', 'K'});
		file.putShort((short) 0);

		assertNull(inspect(file.array(), ".mmf"));
	}

	@Test
	public void rejectsChunkLengthPastContainerWithoutAllocatingPayload() throws IOException {
		ByteBuffer file = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
		file.put(new byte[]{'M', 'M', 'M', 'D'});
		file.putInt(10);
		file.put(new byte[]{'J', 'U', 'N', 'K'});
		file.putInt(-1); // unsigned 0xFFFFFFFF: deliberately impossible length
		file.putShort((short) 0);

		assertNull(inspect(file.array(), ".mmf"));
	}

	@Test
	public void skipsUnknownChunkWhenItsBoundsAreSafe() throws IOException {
		SmafFileFormat.Info info = inspect(mmmd(chunk(id("JUNK"), new byte[]{1, 2, 3, 4})), ".mmf");

		assertNotNull(info);
		assertEquals(0, info.getScoreTrackCount());
		assertEquals(0, info.getPcmAudioTrackCount());
	}

	@Test
	public void classifiesScoreOnlyHandyPhoneStandardAtFourMs() throws IOException {
		byte[] score = scoreTrack(0, 0, 2, 2, chunk(id("Mtsq"), new byte[0]));
		SmafFileFormat.Info info = inspect(mmmd(score), ".mmf");

		assertNotNull(info);
		assertTrue(info.hasScore());
		assertFalse(info.hasAwa());
		assertFalse(info.hasMwa());
		assertEquals(1, info.getScoreTrackCount());
		SmafFileFormat.TrackInfo track = info.getFirstScoreTrack();
		assertNotNull(track);
		assertEquals(SmafFileFormat.TrackType.SCORE, track.getTrackType());
		assertEquals(SmafFileFormat.FormatType.HANDY_PHONE_STANDARD, track.getFormatType());
		assertEquals(SmafFileFormat.SequenceType.STREAM_SEQUENCE, track.getSequenceType());
		assertEquals(4, track.getDurationTimeBaseMs());
		assertEquals(4, track.getGateTimeTimeBaseMs());
		assertFalse(track.isCompressed());
	}

	@Test
	public void classifiesMobileStandardNoCompress() throws IOException {
		byte[] score = scoreTrack(2, 1, 2, 2, chunk(id("Mtsq"), new byte[0]));
		SmafFileFormat.TrackInfo track = inspect(mmmd(score), ".mmf").getFirstScoreTrack();

		assertEquals(SmafFileFormat.FormatType.MOBILE_STANDARD_NO_COMPRESS, track.getFormatType());
		assertEquals(SmafFileFormat.SequenceType.SUBSEQUENCE, track.getSequenceType());
		assertFalse(track.isCompressed());
	}

	@Test
	public void exposesCompressedMobileStandardWithoutDecodingIt() throws IOException {
		byte[] score = scoreTrack(1, 0, 2, 2, chunk(id("Mtsq"), new byte[0]));
		SmafFileFormat.TrackInfo track = inspect(mmmd(score), ".mmf").getFirstScoreTrack();

		assertEquals(SmafFileFormat.FormatType.MOBILE_STANDARD_COMPRESS, track.getFormatType());
		assertTrue(track.isCompressed());
	}

	@Test
	public void classifiesAwaAndInheritedYamahaAdpcmMetadata() throws IOException {
		int waveType = awaWaveType(1, 1, 2, 4); // mono, ADPCM, 11.025 kHz, 4-bit
		byte[] atr = pcmAudioTrack(0, 0, waveType, 2, 2,
				chunk(new byte[]{'A', 'w', 'a', 0}, new byte[]{0x55}));
		SmafFileFormat.Info info = inspect(mmmd(atr), ".mmf");

		assertNotNull(info);
		assertTrue(info.hasPcmAudioTrack());
		assertTrue(info.hasAwa());
		assertEquals(1, info.getAwaCount());
		SmafFileFormat.WaveformInfo wave = info.getFirstAwa();
		assertNotNull(wave);
		assertEquals(SmafFileFormat.WaveformSource.AWA, wave.getSource());
		assertEquals(SmafFileFormat.WaveformCodec.YAMAHA_ADPCM, wave.getCodec());
		assertEquals(1, wave.getChannels());
		assertEquals(11025, wave.getSampleRateHz());
		assertEquals(4, wave.getBitsPerSample());
	}

	@Test
	public void classifiesMwaAndYamahaAdpcmMetadata() throws IOException {
		byte[] mwa = mwa(6000);
		byte[] score = scoreTrack(2, 0, 2, 2, chunk(id("Mtsp"), mwa));
		SmafFileFormat.Info info = inspect(mmmd(score), ".mmf");

		assertNotNull(info);
		assertTrue(info.hasScore());
		assertTrue(info.hasMwa());
		assertEquals(1, info.getMwaCount());
		SmafFileFormat.WaveformInfo wave = info.getFirstMwa();
		assertNotNull(wave);
		assertEquals(SmafFileFormat.WaveformSource.MWA, wave.getSource());
		assertEquals(SmafFileFormat.WaveformCodec.YAMAHA_ADPCM, wave.getCodec());
		assertEquals(1, wave.getChannels());
		assertEquals(6000, wave.getSampleRateHz());
		assertEquals(4, wave.getBitsPerSample());
	}

	@Test
	public void readsObservedMwaSampleRatesDirectly() throws IOException {
		for (int sampleRate : new int[]{4000, 6000, 8000, 11025}) {
			byte[] score = scoreTrack(2, 0, 2, 2, chunk(id("Mtsp"), mwa(sampleRate)));
			SmafFileFormat.WaveformInfo wave = inspect(mmmd(score), ".mmf").getFirstMwa();
			assertNotNull(wave);
			assertEquals(sampleRate, wave.getSampleRateHz());
		}
	}

	@Test
	public void rejectsTruncatedMwaMetadata() throws IOException {
		byte[] brokenMwa = chunk(new byte[]{'M', 'w', 'a', 0}, new byte[]{0x20, 0x1f});
		byte[] score = scoreTrack(2, 0, 2, 2, chunk(id("Mtsp"), brokenMwa));

		assertNull(inspect(mmmd(score), ".mmf"));
	}

	private static SmafFileFormat.Info inspect(byte[] bytes, String suffix) throws IOException {
		Path file = Files.createTempFile("smaf-generated-", suffix);
		try {
			Files.write(file, bytes);
			return SmafFileFormat.inspect(file.toFile());
		} finally {
			Files.deleteIfExists(file);
		}
	}

	private static byte[] mmmd(byte[]... chunks) {
		int payloadSize = 2; // trailing SMAF CRC field; parser deliberately does not validate the checksum yet
		for (byte[] chunk : chunks) {
			payloadSize += chunk.length;
		}
		ByteBuffer buffer = ByteBuffer.allocate(8 + payloadSize).order(ByteOrder.BIG_ENDIAN);
		buffer.put(id("MMMD"));
		buffer.putInt(payloadSize);
		for (byte[] chunk : chunks) {
			buffer.put(chunk);
		}
		buffer.putShort((short) 0);
		return buffer.array();
	}

	private static byte[] scoreTrack(int formatType, int sequenceType,
			int durationTimeBase, int gateTimeBase, byte[]... nestedChunks) {
		int channelStatusBytes = switch (formatType) {
			case 0 -> 2;
			case 1, 2 -> 16;
			default -> throw new IllegalArgumentException("unsupported generated formatType: " + formatType);
		};
		int payloadSize = 4 + channelStatusBytes;
		for (byte[] chunk : nestedChunks) {
			payloadSize += chunk.length;
		}
		ByteBuffer payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.BIG_ENDIAN);
		payload.put((byte) formatType);
		payload.put((byte) sequenceType);
		payload.put((byte) durationTimeBase);
		payload.put((byte) gateTimeBase);
		payload.put(new byte[channelStatusBytes]);
		for (byte[] chunk : nestedChunks) {
			payload.put(chunk);
		}
		return chunk(new byte[]{'M', 'T', 'R', 0}, payload.array());
	}

	private static byte[] pcmAudioTrack(int formatType, int sequenceType, int waveType,
			int durationTimeBase, int gateTimeBase, byte[]... nestedChunks) {
		int payloadSize = 6;
		for (byte[] chunk : nestedChunks) {
			payloadSize += chunk.length;
		}
		ByteBuffer payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.BIG_ENDIAN);
		payload.put((byte) formatType);
		payload.put((byte) sequenceType);
		payload.putShort((short) waveType);
		payload.put((byte) durationTimeBase);
		payload.put((byte) gateTimeBase);
		for (byte[] chunk : nestedChunks) {
			payload.put(chunk);
		}
		return chunk(new byte[]{'A', 'T', 'R', 0}, payload.array());
	}

	private static int awaWaveType(int channels, int codec, int sampleRateCode, int bitsPerSample) {
		int value = channels == 2 ? 0x8000 : 0;
		value |= (codec & 0x07) << 12;
		value |= (sampleRateCode & 0x0f) << 8;
		value |= ((bitsPerSample / 4 - 1) & 0x0f) << 4;
		return value;
	}

	private static byte[] mwa(int sampleRate) {
		ByteBuffer payload = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN);
		payload.put((byte) 0x20); // mono, Yamaha ADPCM, 4-bit
		payload.putShort((short) sampleRate);
		return chunk(new byte[]{'M', 'w', 'a', 0}, payload.array());
	}

	private static byte[] chunk(byte[] id, byte[] payload) {
		ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
		buffer.put(id);
		buffer.putInt(payload.length);
		buffer.put(payload);
		return buffer.array();
	}

	private static byte[] id(String value) {
		if (value.length() != 4) {
			throw new IllegalArgumentException(value);
		}
		return new byte[]{
				(byte) value.charAt(0),
				(byte) value.charAt(1),
				(byte) value.charAt(2),
				(byte) value.charAt(3)
		};
	}
}
