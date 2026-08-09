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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Small, side-effect-free probe for media formats with stable file signatures.
 *
 * <p>This deliberately does not guess from file extensions or MIME strings.
 * Callers may use those as hints only when the content itself is inconclusive.
 * A recognized kind is not automatically a claim that playback is supported;
 * some kinds exist so unsupported media can be diagnosed accurately.</p>
 */
public final class ContentProbe {
	private static final int PROBE_BYTES = 128;
	private static final int FINGERPRINT_BYTES = 16;

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
		SMAF,
		ASF,
		QCP,
		IMELODY,
		RTTTL,
		NOKIA_OTA,
		UNKNOWN
	}

	private ContentProbe() {
	}

	/** Reads only a small prefix and never changes or deletes the media file. */
	public static Kind probe(File file) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("file == null");
		}
		return probe(readPrefix(file, PROBE_BYTES));
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
		if (matches(prefix, 0, 'M', 'M', 'M', 'D')) {
			return Kind.SMAF;
		}
		if (matchesAsciiIgnoreCase(prefix, 0, "BEGIN:IMELODY")) {
			return Kind.IMELODY;
		}
		if (isNokiaOta(prefix)) {
			return Kind.NOKIA_OTA;
		}
		if (isRtttl(prefix)) {
			return Kind.RTTTL;
		}
		if (matches(prefix, 0, 0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11,
				0xa6, 0xd9, 0x00, 0xaa, 0x00, 0x62, 0xce, 0x6c)) {
			return Kind.ASF;
		}
		if (matches(prefix, 0, 'R', 'I', 'F', 'F') && prefix.length >= 12) {
			if (matches(prefix, 8, 'W', 'A', 'V', 'E')) {
				return Kind.WAV;
			}
			if (matches(prefix, 8, 'R', 'M', 'I', 'D')) {
				return Kind.RMID;
			}
			if (matches(prefix, 8, 'Q', 'L', 'C', 'M')) {
				return Kind.QCP;
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

	/**
	 * Returns at most 16 leading bytes as hexadecimal evidence for an unknown
	 * format. This is intentionally tiny: diagnostics should classify media, not
	 * copy game assets into reports.
	 */
	public static String fingerprint(File file) throws IOException {
		byte[] prefix = readPrefix(file, FINGERPRINT_BYTES);
		if (prefix.length == 0) {
			return "empty";
		}
		StringBuilder result = new StringBuilder(prefix.length * 3 - 1);
		for (int i = 0; i < prefix.length; i++) {
			if (i > 0) {
				result.append(' ');
			}
			int value = prefix[i] & 0xff;
			if (value < 0x10) {
				result.append('0');
			}
			result.append(Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT));
		}
		return result.toString();
	}

	private static byte[] readPrefix(File file, int limit) throws IOException {
		byte[] prefix = new byte[limit];
		int total = 0;
		try (FileInputStream input = new FileInputStream(file)) {
			while (total < prefix.length) {
				int read = input.read(prefix, total, prefix.length - total);
				if (read == -1) {
					break;
				}
				if (read == 0) {
					int value = input.read();
					if (value == -1) {
						break;
					}
					prefix[total++] = (byte) value;
				} else {
					total += read;
				}
			}
		}
		return total == prefix.length ? prefix : Arrays.copyOf(prefix, total);
	}

	/** Mirrors the command envelope used by SONiVOX's OTA_CheckFileType(). */
	private static boolean isNokiaOta(byte[] data) {
		if (data.length < 3) {
			return false;
		}
		int commandCount = data[0] & 0xff;
		if (commandCount < 2) {
			return false;
		}
		int availableCommands = Math.min(commandCount, data.length - 1);
		for (int i = 0; i < availableCommands; i++) {
			int command = (data[i + 1] & 0xff) >>> 1;
			if (i == 0) {
				if (command != 0x25) { // Ringing Tone Programming
					return false;
				}
				continue;
			}
			if (command == 0x1d) { // Sound
				return true;
			}
			if (command != 0x22) { // optional Unicode command before Sound
				return false;
			}
		}
		return false;
	}

	/** Validates the RTTTL title/control header before native parser discovery. */
	private static boolean isRtttl(byte[] data) {
		int titleEnd = -1;
		for (int i = 0; i < Math.min(data.length, 32); i++) {
			int value = data[i] & 0xff;
			if (value == ':') {
				titleEnd = i;
				break;
			}
			if (value < 0x20 || value > 0x7e) {
				return false;
			}
		}
		if (titleEnd <= 0) {
			return false;
		}

		int position = titleEnd + 1;
		int controls = 0;
		while (position < data.length) {
			position = skipAsciiWhitespace(data, position);
			if (position >= data.length) {
				return false;
			}
			int control = lowerAscii(data[position++] & 0xff);
			if (control != 'b' && control != 'd' && control != 'l'
					&& control != 'o' && control != 's') {
				return false;
			}

			position = skipAsciiWhitespace(data, position);
			if (position >= data.length || data[position++] != '=') {
				return false;
			}
			position = skipAsciiWhitespace(data, position);
			if (position >= data.length) {
				return false;
			}

			if (control == 's') {
				int style = lowerAscii(data[position++] & 0xff);
				if (style != 's' && style != 'n' && style != 'c') {
					return false;
				}
			} else {
				int digits = 0;
				int value = 0;
				while (position < data.length && data[position] >= '0' && data[position] <= '9') {
					if (value > 10000) {
						return false;
					}
					value = value * 10 + data[position] - '0';
					position++;
					digits++;
				}
				if (digits == 0 || !isValidRtttlControlValue(control, value)) {
					return false;
				}
			}

			position = skipAsciiWhitespace(data, position);
			if (position >= data.length) {
				return false;
			}
			int separator = data[position++] & 0xff;
			controls++;
			if (separator == ':') {
				return controls > 0;
			}
			if (separator != ',') {
				return false;
			}
		}
		return false;
	}

	private static boolean isValidRtttlControlValue(int control, int value) {
		return switch (control) {
			case 'b' -> value >= 25 && value <= 900;
			case 'd' -> value == 1 || value == 2 || value == 4 || value == 8
					|| value == 16 || value == 32;
			case 'l' -> value >= 0 && value <= 15;
			case 'o' -> value >= 4 && value <= 7;
			default -> false;
		};
	}

	private static int skipAsciiWhitespace(byte[] data, int position) {
		while (position < data.length) {
			int value = data[position] & 0xff;
			if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
				break;
			}
			position++;
		}
		return position;
	}

	private static int lowerAscii(int value) {
		return value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value;
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

	private static boolean matchesAsciiIgnoreCase(byte[] data, int offset, String expected) {
		if (offset < 0 || data.length - offset < expected.length()) {
			return false;
		}
		for (int i = 0; i < expected.length(); i++) {
			int actual = data[offset + i] & 0xff;
			char wanted = expected.charAt(i);
			if (actual >= 'a' && actual <= 'z') {
				actual -= 'a' - 'A';
			}
			if (wanted >= 'a' && wanted <= 'z') {
				wanted -= 'a' - 'A';
			}
			if (actual != wanted) {
				return false;
			}
		}
		return true;
	}

	private static boolean matches(byte[] data, int offset, int... expected) {
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
