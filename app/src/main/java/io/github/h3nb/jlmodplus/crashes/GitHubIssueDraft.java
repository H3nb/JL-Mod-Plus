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

package io.github.h3nb.jlmodplus.crashes;

import java.nio.charset.StandardCharsets;

/** Builds a bounded GitHub Issue Form URL without splitting UTF-8/UTF-16 characters. */
final class GitHubIssueDraft {
	// GitHub does not publish a practical browser/server URL limit for issue prefills. Retain the
	// project's conservative transport guard; normal content is deliberately compact before here.
	static final int MAX_URL_CHARS = 4096;
	private static final int MAX_TITLE_ENCODED_CHARS = 768;
	private static final String SHORTENED_NOTICE =
			"\n\n[Diagnostic summary shortened to fit the GitHub prefill URL. The ZIP is authoritative.]";

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private GitHubIssueDraft() {}

	static String buildIssueFormUrl(String baseUrl, String template, String title,
			String fieldId, String fieldValue) {
		String prefix = value(baseUrl)
				+ "?template=" + encode(value(template))
				+ "&title=";
		int titleBudget = Math.min(
				MAX_TITLE_ENCODED_CHARS,
				Math.max(0, MAX_URL_CHARS - prefix.length()));
		Encoded encodedTitle = encodeBounded(value(title), titleBudget);
		String fieldPrefix = prefix + encodedTitle.text + "&" + encode(value(fieldId)) + "=";
		int fieldBudget = MAX_URL_CHARS - fieldPrefix.length();
		if (fieldBudget <= 0) return prefix + encodedTitle.text;

		Encoded summary = encodeBounded(value(fieldValue), fieldBudget);
		if (summary.complete) return fieldPrefix + summary.text;

		Encoded notice = encodeBounded(SHORTENED_NOTICE, fieldBudget);
		int summaryBudget = Math.max(0, fieldBudget - notice.text.length());
		return fieldPrefix + encodeBounded(value(fieldValue), summaryBudget).text + notice.text;
	}

	private static String encode(String value) {
		return encodeBounded(value, Integer.MAX_VALUE).text;
	}

	private static Encoded encodeBounded(String value, int maxChars) {
		if (value.isEmpty()) return new Encoded("", true);
		if (maxChars <= 0) return new Encoded("", false);

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
		for (byte value : bytes) length += isUnreserved(value & 0xff) ? 1 : 3;
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
