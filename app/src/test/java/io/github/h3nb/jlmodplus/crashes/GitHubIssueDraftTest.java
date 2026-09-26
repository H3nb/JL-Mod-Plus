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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class GitHubIssueDraftTest {
	private static final String BASE_URL = "https://github.com/H3nb/JL-Mod-Plus/issues/new";

	@Test
	public void issueFormFieldsRoundTripExactly() {
		String url = GitHubIssueDraft.buildIssueFormUrl(
				BASE_URL,
				"diagnostic-report.yml",
				"[MIDlet crash] Game & Demo — NullPointerException",
				"diagnostic-summary",
				"### Summary\nline two = value & evidence");

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		assertEquals("diagnostic-report.yml", queryValue(url, "template"));
		assertEquals("[MIDlet crash] Game & Demo — NullPointerException", queryValue(url, "title"));
		assertEquals("### Summary\nline two = value & evidence",
				queryValue(url, "diagnostic-summary"));
		assertFalse(url.contains("&body="));
	}

	@Test
	public void pathologicalTitleDoesNotStarveSummaryBudget() {
		StringBuilder title = new StringBuilder();
		for (int i = 0; i < 1000; i++) title.append("🚀 & ");

		String url = GitHubIssueDraft.buildIssueFormUrl(
				BASE_URL,
				"diagnostic-report.yml",
				title.toString(),
				"diagnostic-summary",
				"important summary");

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		assertTrue(queryValue(url, "title").length() < title.length());
		assertEquals("important summary", queryValue(url, "diagnostic-summary"));
	}

	@Test
	public void safetyGuardDoesNotSplitSupplementaryUnicode() {
		StringBuilder summary = new StringBuilder();
		for (int i = 0; i < 1200; i++) summary.append("entry ").append(i).append(" 🚀 ");

		String url = GitHubIssueDraft.buildIssueFormUrl(
				BASE_URL,
				"diagnostic-report.yml",
				"Emoji 🚀 diagnostic",
				"diagnostic-summary",
				summary.toString());

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		assertWellFormedUtf16(queryValue(url, "title"));
		assertWellFormedUtf16(queryValue(url, "diagnostic-summary"));
		assertTrue(queryValue(url, "diagnostic-summary").contains("ZIP is authoritative"));
	}

	@Test
	public void reservedCharactersArePercentEncoded() {
		String url = GitHubIssueDraft.buildIssueFormUrl(
				BASE_URL,
				"diagnostic-report.yml",
				"A&B?C#D%=E",
				"diagnostic-summary",
				"x&y?z#p%=q");

		assertTrue(url.contains("%26"));
		assertTrue(url.contains("%3F"));
		assertTrue(url.contains("%23"));
		assertTrue(url.contains("%25"));
		assertTrue(url.contains("%3D"));
		assertEquals("A&B?C#D%=E", queryValue(url, "title"));
		assertEquals("x&y?z#p%=q", queryValue(url, "diagnostic-summary"));
	}

	private static String queryValue(String url, String name) {
		int queryStart = url.indexOf('?');
		assertTrue(queryStart >= 0);
		for (String parameter : url.substring(queryStart + 1).split("&")) {
			int equals = parameter.indexOf('=');
			if (equals < 0 || !name.equals(parameter.substring(0, equals))) continue;
			return URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8);
		}
		throw new AssertionError("Missing query parameter: " + name);
	}

	private static void assertWellFormedUtf16(String value) {
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			if (Character.isHighSurrogate(current)) {
				assertTrue(i + 1 < value.length());
				assertTrue(Character.isLowSurrogate(value.charAt(++i)));
			} else {
				assertFalse(Character.isLowSurrogate(current));
			}
		}
	}
}
