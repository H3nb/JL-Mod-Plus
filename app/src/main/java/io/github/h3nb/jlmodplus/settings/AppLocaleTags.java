/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** Normalizes app-language picker values through the platform BCP-47 mapping. */
public final class AppLocaleTags {
	private AppLocaleTags() {
	}

	/** Drop only the retired Santali locale, preserving any other requested languages. */
	public static void migrateRetiredSantaliLocale() {
		String retained = withoutRetiredSantali(AppCompatDelegate.getApplicationLocales().toLanguageTags());
		if (retained != null) {
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(retained));
		}
	}

	/** Null means no change; an empty string means follow the system language. */
	@Nullable
	static String withoutRetiredSantali(@NonNull String languageTags) {
		boolean removed = false;
		StringBuilder retained = new StringBuilder();
		for (String tag : languageTags.split(",")) {
			if ("sat".equals(Locale.forLanguageTag(tag).getLanguage())) {
				removed = true;
			} else if (!tag.isEmpty()) {
				if (retained.length() > 0) {
					retained.append(',');
				}
				retained.append(tag);
			}
		}
		return removed ? retained.toString() : null;
	}

	@NonNull
	static String canonicalize(@NonNull String languageTag) {
		if (languageTag.isEmpty()) {
			return "";
		}
		return Locale.forLanguageTag(languageTag).toLanguageTag();
	}
}
