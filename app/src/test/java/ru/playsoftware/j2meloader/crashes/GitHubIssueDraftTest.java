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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class GitHubIssueDraftTest {
	private static final String BASE_URL = "https://github.com/H3nb/JL-Mod-Plus/issues/new";

	@Test
	public void ordinaryDraftRoundTripsQueryValues() {
		String url = GitHubIssueDraft.buildUrl(
				BASE_URL,
				"issue-template.md",
				"Diagnostic report: Game & Demo",
				"line one\nline two = value",
				"\n[shortened]");

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		assertEquals("issue-template.md", queryValue(url, "template"));
		assertEquals("Diagnostic report: Game & Demo", queryValue(url, "title"));
		assertEquals("line one\nline two = value", queryValue(url, "body"));
	}

	@Test
	public void longEncodedBodyIsBoundedAndCarriesNotice() {
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 2000; i++) {
			body.append("line with spaces & reserved ? # % = ").append(i).append('\n');
		}
		String notice = "\n\n[Diagnostic prefill shortened]";

		String url = GitHubIssueDraft.buildUrl(
				BASE_URL,
				"issue-template.md",
				"Diagnostic report",
				body.toString(),
				notice);

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		String decodedBody = queryValue(url, "body");
		assertTrue(decodedBody.endsWith(notice));
		assertTrue(decodedBody.length() < body.length());
	}

	@Test
	public void truncationNeverSplitsSupplementaryUnicode() {
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 1200; i++) {
			body.append("entry ").append(i).append(" 🚀 ");
		}

		String url = GitHubIssueDraft.buildUrl(
				BASE_URL,
				"issue-template.md",
				"Emoji 🚀 diagnostic",
				body.toString(),
				"\n[shortened]");

		assertTrue(url.length() <= GitHubIssueDraft.MAX_URL_CHARS);
		assertWellFormedUtf16(queryValue(url, "title"));
		assertWellFormedUtf16(queryValue(url, "body"));
	}

	@Test
	public void reservedCharactersArePercentEncoded() {
		String url = GitHubIssueDraft.buildUrl(
				BASE_URL,
				"issue-template.md",
				"A&B?C#D%=E",
				"x&y?z#p%=q",
				"[shortened]");

		assertTrue(url.contains("%26"));
		assertTrue(url.contains("%3F"));
		assertTrue(url.contains("%23"));
		assertTrue(url.contains("%25"));
		assertTrue(url.contains("%3D"));
		assertEquals("A&B?C#D%=E", queryValue(url, "title"));
		assertEquals("x&y?z#p%=q", queryValue(url, "body"));
	}

	private static String queryValue(String url, String name) {
		int queryStart = url.indexOf('?');
		assertTrue(queryStart >= 0);
		for (String parameter : url.substring(queryStart + 1).split("&")) {
			int equals = parameter.indexOf('=');
			if (equals < 0 || !name.equals(parameter.substring(0, equals))) {
				continue;
			}
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
