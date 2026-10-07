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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.LocaleManagerCompat;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the Java ME device locale independently from MIDlet content.
 *
 * <p>MIDP 2.0 exposes a device locale as {@code language[-COUNTRY[-variant]]}. Android app
 * locales may be intentionally language-only, so the runtime preserves an explicit region,
 * otherwise prefers a compatible region from the user's system locale list, and finally uses
 * ICU/CLDR likely-subtags to complete an underspecified locale.</p>
 */
final class MidletLocaleResolver {
	private static final String TAG = "MidletLocaleResolver";
	private static final Set<String> ISO_LANGUAGES =
			new HashSet<>(Arrays.asList(Locale.getISOLanguages()));
	private static final Set<String> ISO_COUNTRIES =
			new HashSet<>(Arrays.asList(Locale.getISOCountries()));

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
		return resolve(effectiveLocale, systemLocales, MidletLocaleResolver::maximizePlatformLocale);
	}

	@Nullable
	static String resolve(
			@Nullable Locale effectiveLocale,
			List<Locale> systemLocales,
			LocaleMaximizer maximizer) {
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
						|| !scriptsCompatible(effective, candidate, maximizer)) {
					continue;
				}
				country = candidateCountry;
				break;
			}
		}

		if (country == null) {
			country = midpCountry(safeMaximize(effective, maximizer));
		}

		return serialize(language, country, effective.getVariant());
	}

	private static boolean scriptsCompatible(
			Locale requested,
			Locale candidate,
			LocaleMaximizer maximizer) {
		String requestedScript = requested.getScript();
		if (requestedScript == null || requestedScript.isEmpty()) {
			return true;
		}
		String candidateScript = candidate.getScript();
		if (candidateScript == null || candidateScript.isEmpty()) {
			Locale maximizedCandidate = safeMaximize(candidate, maximizer);
			candidateScript = maximizedCandidate == null ? "" : maximizedCandidate.getScript();
		}
		return requestedScript.equalsIgnoreCase(candidateScript);
	}

	@Nullable
	private static Locale safeMaximize(Locale locale, LocaleMaximizer maximizer) {
		try {
			Locale maximized = maximizer.maximize(locale);
			return maximized == null ? locale : canonicalize(maximized);
		} catch (RuntimeException error) {
			Log.w(TAG, "Unable to maximize locale " + locale.toLanguageTag(), error);
			return locale;
		}
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
		String language = locale.getLanguage().toLowerCase(Locale.ROOT);
		if (language.length() != 2 || !ISO_LANGUAGES.contains(language)) {
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
		if (country.length() != 2 || !ISO_COUNTRIES.contains(country)) {
			return null;
		}
		return country;
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

	private static Locale maximizePlatformLocale(Locale locale) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			return Api24Impl.maximize(locale);
		}
		return Api23Impl.maximize(locale);
	}

	@RequiresApi(Build.VERSION_CODES.N)
	private static final class Api24Impl {
		private Api24Impl() {
		}

		static Locale maximize(Locale locale) {
			android.icu.util.ULocale uLocale = android.icu.util.ULocale.forLocale(locale);
			return android.icu.util.ULocale.addLikelySubtags(uLocale).toLocale();
		}
	}

	private static final class Api23Impl {
		private static final Method ADD_LIKELY_SUBTAGS = findAddLikelySubtags();

		private Api23Impl() {
		}

		@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
		@Nullable
		private static Method findAddLikelySubtags() {
			try {
				Class<?> icu = Class.forName("libcore.icu.ICU");
				return icu.getMethod("addLikelySubtags", Locale.class);
			} catch (ReflectiveOperationException error) {
				Log.w(TAG, "API 23 ICU likely-subtags bridge is unavailable", error);
				return null;
			}
		}

		static Locale maximize(Locale locale) {
			Method method = ADD_LIKELY_SUBTAGS;
			if (method == null) {
				return locale;
			}
			try {
				Object value = method.invoke(null, locale);
				return value instanceof Locale ? (Locale) value : locale;
			} catch (IllegalAccessException | InvocationTargetException error) {
				Log.w(TAG, "API 23 ICU likely-subtags lookup failed", error);
				return locale;
			}
		}
	}

	@FunctionalInterface
	interface LocaleMaximizer {
		Locale maximize(Locale locale);
	}
}
