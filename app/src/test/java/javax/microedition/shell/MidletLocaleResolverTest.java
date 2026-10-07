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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

public class MidletLocaleResolverTest {
	@Test
	public void explicitRegionWinsOverSystemRegion() {
		assertEquals("fr-CA", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("fr-CA"),
				List.of(Locale.forLanguageTag("fr-FR")),
				(requested, candidate) -> true));
	}

	@Test
	public void sameLanguageSystemRegionCompletesLanguageOnlyLocale() {
		assertEquals("id-ID", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("id"),
				List.of(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("id-ID")),
				(requested, candidate) -> true));
	}

	@Test
	public void unrelatedSystemLocaleDoesNotInventCountry() {
		assertEquals("de", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("de"),
				List.of(Locale.forLanguageTag("en-US")),
				(requested, candidate) -> false));
	}

	@Test
	public void scriptMatcherSkipsIncompatibleSameLanguageRegion() {
		assertEquals("zh-TW", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("zh-Hant"),
				List.of(Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("zh-TW")),
				(requested, candidate) -> "TW".equals(candidate.getCountry())));
	}

	@Test
	public void legacyLanguageCodeCanonicalizesThroughLanguageTag() {
		assertEquals("he-IL", MidletLocaleResolver.resolve(
				new Locale("iw", "IL"),
				List.of(),
				(requested, candidate) -> true));
	}

	@Test
	public void nonIsoEffectiveRegionIsOmitted() {
		assertEquals("en", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("en-XA"),
				List.of(),
				(requested, candidate) -> true));
	}

	@Test
	public void nonIsoSystemRegionIsNotBorrowed() {
		assertEquals("en", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("en"),
				List.of(Locale.forLanguageTag("en-XA")),
				(requested, candidate) -> true));
	}

	@Test
	public void unsupportedThreeLetterLanguageIsNotInventedAsMidpLocale() {
		assertNull(MidletLocaleResolver.resolve(
				Locale.forLanguageTag("sat-IN"),
				List.of(),
				(requested, candidate) -> true));
	}

	@Test
	public void validLanguageCanRemainLanguageOnlyWhenNoRegionCanBeResolved() {
		assertEquals("eo", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("eo"),
				List.of(),
				(requested, candidate) -> true));
	}

	@Test
	public void variantIsPreservedAfterValidCountry() {
		assertEquals("en-US-POSIX", MidletLocaleResolver.resolve(
				Locale.forLanguageTag("en-US-POSIX"),
				List.of(),
				(requested, candidate) -> true));
	}
}
