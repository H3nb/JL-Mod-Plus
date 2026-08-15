/*
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

package org.microemu.cldc;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.security.auth.x500.X500Principal;

/** Formats X.500 names using the strict printable representation required by MIDP 2.0. */
final class X500NameFormatter {
	private X500NameFormatter() {
	}

	static String format(X500Principal principal) {
		if (principal == null) {
			return "";
		}
		try {
			DerReader root = new DerReader(principal.getEncoded());
			DerValue sequence = root.readExpected(0x30);
			DerReader rdns = sequence.reader();
			StringBuilder result = new StringBuilder();
			while (rdns.hasRemaining()) {
				DerReader attributes = rdns.readExpected(0x31).reader();
				while (attributes.hasRemaining()) {
					DerReader attribute = attributes.readExpected(0x30).reader();
					DerValue oid = attribute.readExpected(0x06);
					DerValue value = attribute.read();
					if (attribute.hasRemaining()) {
						throw new IllegalArgumentException("Unexpected X.500 attribute data");
					}
					if (result.length() != 0) {
						result.append(';');
					}
					result.append(labelFor(oid.value));
					result.append('=');
					result.append(formatValue(value));
				}
			}
			if (root.hasRemaining()) {
				throw new IllegalArgumentException("Unexpected X.500 name data");
			}
			return result.toString();
		} catch (RuntimeException ex) {
			// A provider-specific principal encoding should not crash a MIDlet. The
			// RFC2253 name remains a useful non-null fallback if DER parsing fails.
			return principal.getName(X500Principal.RFC2253);
		}
	}

	private static String labelFor(byte[] oid) {
		String encoded = toHex(oid);
		if ("55:04:03".equals(encoded)) return "CN";
		if ("55:04:04".equals(encoded)) return "SN";
		if ("55:04:06".equals(encoded)) return "C";
		if ("55:04:07".equals(encoded)) return "L";
		if ("55:04:08".equals(encoded)) return "ST";
		if ("55:04:09".equals(encoded)) return "STREET";
		if ("55:04:0A".equals(encoded)) return "O";
		if ("55:04:0B".equals(encoded)) return "OU";
		if ("2A:86:48:86:F7:0D:01:09:01".equals(encoded)) return "EmailAddress";
		return encoded;
	}

	private static String formatValue(DerValue value) {
		switch (value.tag) {
			case 0x0c: // UTF8String
				return new String(value.value, StandardCharsets.UTF_8);
			case 0x12: // NumericString
			case 0x13: // PrintableString
			case 0x16: // IA5String
			case 0x1a: // VisibleString
			case 0x1b: // GeneralString
				return new String(value.value, StandardCharsets.US_ASCII);
			case 0x14: // TeletexString / T61String
				return new String(value.value, StandardCharsets.ISO_8859_1);
			case 0x1e: // BMPString
				return new String(value.value, Charset.forName("UTF-16BE"));
			case 0x1c: // UniversalString
				return new String(value.value, Charset.forName("UTF-32BE"));
			default:
				return toHex(value.value);
		}
	}

	private static String toHex(byte[] bytes) {
		if (bytes.length == 0) {
			return "";
		}
		final char[] hex = "0123456789ABCDEF".toCharArray();
		StringBuilder result = new StringBuilder(bytes.length * 3 - 1);
		for (int i = 0; i < bytes.length; i++) {
			if (i != 0) result.append(':');
			int value = bytes[i] & 0xff;
			result.append(hex[value >>> 4]);
			result.append(hex[value & 0x0f]);
		}
		return result.toString();
	}

	private static final class DerValue {
		final int tag;
		final byte[] value;

		DerValue(int tag, byte[] value) {
			this.tag = tag;
			this.value = value;
		}

		DerReader reader() {
			return new DerReader(value);
		}
	}

	private static final class DerReader {
		private final byte[] data;
		private int offset;

		DerReader(byte[] data) {
			this.data = data;
		}

		boolean hasRemaining() {
			return offset < data.length;
		}

		DerValue readExpected(int expectedTag) {
			DerValue value = read();
			if (value.tag != expectedTag) {
				throw new IllegalArgumentException("Unexpected DER tag: " + value.tag);
			}
			return value;
		}

		DerValue read() {
			if (!hasRemaining()) {
				throw new IllegalArgumentException("Unexpected end of DER data");
			}
			int tag = data[offset++] & 0xff;
			int length = readLength();
			if (length < 0 || length > data.length - offset) {
				throw new IllegalArgumentException("Invalid DER length");
			}
			byte[] value = new byte[length];
			System.arraycopy(data, offset, value, 0, length);
			offset += length;
			return new DerValue(tag, value);
		}

		private int readLength() {
			if (!hasRemaining()) {
				throw new IllegalArgumentException("Missing DER length");
			}
			int first = data[offset++] & 0xff;
			if ((first & 0x80) == 0) {
				return first;
			}
			int count = first & 0x7f;
			if (count == 0 || count > 4 || count > data.length - offset) {
				throw new IllegalArgumentException("Unsupported DER length");
			}
			int length = 0;
			for (int i = 0; i < count; i++) {
				length = (length << 8) | (data[offset++] & 0xff);
			}
			return length;
		}
	}
}
