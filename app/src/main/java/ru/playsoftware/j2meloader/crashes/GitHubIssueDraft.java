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

package ru.playsoftware.j2meloader.crashes;

import java.nio.charset.StandardCharsets;

/** Builds a bounded, UTF-8 percent-encoded GitHub new-issue URL without splitting Unicode. */
final class GitHubIssueDraft {
	// GitHub documents 414 for an oversized issue URL but does not publish the server limit. Keep
	// a conservative client-side ceiling on the final encoded URI rather than on the raw body text.
	static final int MAX_URL_CHARS = 4096;

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private GitHubIssueDraft() {}

	static String buildUrl(String baseUrl, String template, String title, String body,
			String truncationNotice) {
		String fixedPrefix = value(baseUrl)
				+ "?template=" + encode(value(template))
				+ "&title=";
		String bodySeparator = "&body=";
		int titleBudget = MAX_URL_CHARS - fixedPrefix.length() - bodySeparator.length();
		if (titleBudget <= 0) {
			return value(baseUrl);
		}

		Encoded titleEncoded = encodeBounded(value(title), titleBudget);
		String prefix = fixedPrefix + titleEncoded.text + bodySeparator;
		int bodyBudget = MAX_URL_CHARS - prefix.length();
		if (bodyBudget <= 0) {
			return prefix;
		}

		Encoded fullBody = encodeBounded(value(body), bodyBudget);
		if (fullBody.complete) {
			return prefix + fullBody.text;
		}

		Encoded notice = encodeBounded(value(truncationNotice), bodyBudget);
		int truncatedBodyBudget = Math.max(0, bodyBudget - notice.text.length());
		Encoded truncatedBody = encodeBounded(value(body), truncatedBodyBudget);
		return prefix + truncatedBody.text + notice.text;
	}

	private static String encode(String value) {
		return encodeBounded(value, Integer.MAX_VALUE).text;
	}

	private static Encoded encodeBounded(String value, int maxChars) {
		if (value.isEmpty()) {
			return new Encoded("", true);
		}
		if (maxChars <= 0) {
			return new Encoded("", false);
		}

		StringBuilder encoded = new StringBuilder(Math.min(maxChars, Math.max(16, value.length())));
		for (int offset = 0; offset < value.length(); ) {
			int codePoint = value.codePointAt(offset);
			byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
			int encodedLength = encodedLength(bytes);
			if (encoded.length() > maxChars - encodedLength) {
				return new Encoded(encoded.toString(), false);
			}
			appendEncoded(encoded, bytes);
			offset += Character.charCount(codePoint);
		}
		return new Encoded(encoded.toString(), true);
	}

	private static int encodedLength(byte[] bytes) {
		int length = 0;
		for (byte value : bytes) {
			length += isUnreserved(value & 0xff) ? 1 : 3;
		}
		return length;
	}

	private static void appendEncoded(StringBuilder encoded, byte[] bytes) {
		for (byte value : bytes) {
			int unsigned = value & 0xff;
			if (isUnreserved(unsigned)) {
				encoded.append((char) unsigned);
			} else {
				encoded.append('%')
						.append(HEX[unsigned >>> 4])
						.append(HEX[unsigned & 0x0f]);
			}
		}
	}

	private static boolean isUnreserved(int value) {
		return value >= 'a' && value <= 'z'
				|| value >= 'A' && value <= 'Z'
				|| value >= '0' && value <= '9'
				|| value == '-'
				|| value == '.'
				|| value == '_'
				|| value == '~';
	}

	private static String value(String text) {
		return text == null ? "" : text;
	}

	private static final class Encoded {
		final String text;
		final boolean complete;

		Encoded(String text, boolean complete) {
			this.text = text;
			this.complete = complete;
		}
	}
}
