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

/**
 * Small, side-effect-free probe for media formats with stable file signatures.
 *
 * <p>This deliberately does not guess from file extensions or MIME strings.
 * Callers may use those as hints only when the content itself is inconclusive.</p>
 */
public final class ContentProbe {
	public enum Kind {
		MIDI,
		XMF,
		WAV,
		RMID,
		MP3,
		AAC,
		AMR,
		AMR_WB,
		MP4,
		UNKNOWN
	}

	private ContentProbe() {
	}

	public static Kind probe(byte[] prefix) {
		if (prefix == null || prefix.length == 0) {
			return Kind.UNKNOWN;
		}

		if (matches(prefix, 0, 'M', 'T', 'h', 'd')) {
			return Kind.MIDI;
		}
		if (matches(prefix, 0, 'X', 'M', 'F', '_')) {
			return Kind.XMF;
		}
		if (matches(prefix, 0, 'R', 'I', 'F', 'F') && prefix.length >= 12) {
			if (matches(prefix, 8, 'W', 'A', 'V', 'E')) {
				return Kind.WAV;
			}
			if (matches(prefix, 8, 'R', 'M', 'I', 'D')) {
				return Kind.RMID;
			}
		}
		if (matches(prefix, 0, '#', '!', 'A', 'M', 'R', '-', 'W', 'B', '\n')) {
			return Kind.AMR_WB;
		}
		if (matches(prefix, 0, '#', '!', 'A', 'M', 'R', '\n')) {
			return Kind.AMR;
		}
		if (matches(prefix, 0, 'I', 'D', '3') || isMpegAudioFrame(prefix)) {
			return Kind.MP3;
		}
		if (isAdtsFrame(prefix)) {
			return Kind.AAC;
		}
		if (matches(prefix, 4, 'f', 't', 'y', 'p')) {
			return Kind.MP4;
		}
		return Kind.UNKNOWN;
	}

	private static boolean isMpegAudioFrame(byte[] data) {
		if (data.length < 3) {
			return false;
		}
		int b0 = data[0] & 0xff;
		int b1 = data[1] & 0xff;
		int b2 = data[2] & 0xff;
		if (b0 != 0xff || (b1 & 0xe0) != 0xe0) {
			return false;
		}

		int version = (b1 >>> 3) & 0x03;
		int layer = (b1 >>> 1) & 0x03;
		int bitrateIndex = (b2 >>> 4) & 0x0f;
		int sampleRateIndex = (b2 >>> 2) & 0x03;
		return version != 0x01
				&& layer != 0x00
				&& bitrateIndex != 0x00
				&& bitrateIndex != 0x0f
				&& sampleRateIndex != 0x03;
	}

	private static boolean isAdtsFrame(byte[] data) {
		if (data.length < 2) {
			return false;
		}
		int b0 = data[0] & 0xff;
		int b1 = data[1] & 0xff;
		return b0 == 0xff && (b1 & 0xf6) == 0xf0;
	}

	private static boolean matches(byte[] data, int offset, char... expected) {
		if (offset < 0 || data.length - offset < expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((data[offset + i] & 0xff) != expected[i]) {
				return false;
			}
		}
		return true;
	}
}
