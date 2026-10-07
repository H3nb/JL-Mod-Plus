/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.settings;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Canonical BCP-47 identity for app-language picker values. */
final class AppLocaleTags {
	private AppLocaleTags() {
	}

	@NonNull
	static String canonicalize(@NonNull String languageTag) {
		if (languageTag.isEmpty()) {
			return "";
		}
		return Locale.forLanguageTag(languageTag).toLanguageTag();
	}
}
