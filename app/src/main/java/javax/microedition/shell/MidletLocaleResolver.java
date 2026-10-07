/*
 * Copyright 2026 JL-Mod Plus contributors
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

package javax.microedition.shell;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.core.app.LocaleManagerCompat;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Resolves the Java ME device locale independently from MIDlet content.
 *
 * <p>MIDP 2.0 exposes a device locale as {@code language[-COUNTRY[-variant]]}. Android app
 * locales may be intentionally language-only, so the runtime preserves an explicit region and
 * otherwise borrows a region only from a compatible system locale. It never invents a country
 * merely because a modern locale database has a likely default.</p>
 */
final class MidletLocaleResolver {
	private MidletLocaleResolver() {
	}

	@Nullable
	static String resolve(Context context) {
		LocaleListCompat effectiveLocales =
				ConfigurationCompat.getLocales(context.getResources().getConfiguration());
		LocaleListCompat systemLocaleList = LocaleManagerCompat.getSystemLocales(context);
		List<Locale> systemLocales = new ArrayList<>(systemLocaleList.size());
		for (int i = 0; i < systemLocaleList.size(); i++) {
			Locale locale = systemLocaleList.get(i);
			if (locale != null) {
				systemLocales.add(locale);
			}
		}

		Locale effectiveLocale = effectiveLocales.size() > 0 ? effectiveLocales.get(0) : null;
		if (effectiveLocale == null && !systemLocales.isEmpty()) {
			effectiveLocale = systemLocales.get(0);
		}
		return resolve(effectiveLocale, systemLocales, LocaleListCompat::matchesLanguageAndScript);
	}

	@Nullable
	static String resolve(
			@Nullable Locale effectiveLocale,
			List<Locale> systemLocales,
			LocaleMatcher localeMatcher) {
		Locale effective = canonicalize(effectiveLocale);
		String language = midpLanguage(effective);
		if (language == null) {
			return null;
		}

		String country = midpCountry(effective);
		if (country == null) {
			for (Locale systemLocale : systemLocales) {
				Locale candidate = canonicalize(systemLocale);
				if (!language.equals(midpLanguage(candidate))) {
					continue;
				}
				String candidateCountry = midpCountry(candidate);
				if (candidateCountry == null
						|| !localeMatcher.matches(effective, candidate)) {
					continue;
				}
				country = candidateCountry;
				break;
			}
		}

		return serialize(language, country, effective.getVariant());
	}

	@Nullable
	private static Locale canonicalize(@Nullable Locale locale) {
		if (locale == null) {
			return null;
		}
		String tag = locale.toLanguageTag();
		Locale canonical = Locale.forLanguageTag(tag);
		return canonical.getLanguage().isEmpty() ? locale : canonical;
	}

	@Nullable
	private static String midpLanguage(@Nullable Locale locale) {
		if (locale == null) {
			return null;
		}
		String tag = locale.toLanguageTag();
		int separator = tag.indexOf('-');
		String language = (separator < 0 ? tag : tag.substring(0, separator))
				.toLowerCase(Locale.ROOT);
		if (!isTwoAsciiLetters(language)) {
			return null;
		}
		try {
			if (locale.getISO3Language().isEmpty()) {
				return null;
			}
		} catch (MissingResourceException error) {
			return null;
		}
		return language;
	}

	@Nullable
	private static String midpCountry(@Nullable Locale locale) {
		if (locale == null) {
			return null;
		}
		String country = locale.getCountry().toUpperCase(Locale.ROOT);
		// Preserve a real two-letter region supplied by the platform. Java ME-era locale packs
		// can contain historical ISO-3166 identifiers that modern Java no longer maps to ISO3.
		return isTwoAsciiLetters(country) ? country : null;
	}

	private static boolean isTwoAsciiLetters(String value) {
		return value.length() == 2
				&& isAsciiLetter(value.charAt(0))
				&& isAsciiLetter(value.charAt(1));
	}

	private static boolean isAsciiLetter(char value) {
		return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
	}

	private static String serialize(String language, @Nullable String country, String variant) {
		StringBuilder value = new StringBuilder(language);
		if (country == null) {
			return value.toString();
		}
		value.append('-').append(country);
		if (variant == null || variant.isEmpty()) {
			return value.toString();
		}
		for (String part : variant.split("[_-]+")) {
			if (!part.isEmpty() && part.matches("[A-Za-z0-9]+")) {
				value.append('-').append(part);
			}
		}
		return value.toString();
	}

	@FunctionalInterface
	interface LocaleMatcher {
		boolean matches(Locale requested, Locale candidate);
	}
}
