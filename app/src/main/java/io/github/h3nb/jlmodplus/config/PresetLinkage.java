/*
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/** Persists preset provenance separately from the explicit whole-preset linkage marker. */
final class PresetLinkage {
	private static final String ORIGIN_PREFIX = "config_profile_origin:";
	private static final String LINKED_PREFIX = "config_profile_linked:";

	@NonNull private final SharedPreferences preferences;
	@NonNull private final File configDir;

	PresetLinkage(@NonNull SharedPreferences preferences, @NonNull File configDir) {
		this.preferences = preferences;
		this.configDir = configDir;
	}

	@Nullable
	String getOrigin() {
		return preferences.getString(originPreferenceKey(configDir), null);
	}

	boolean isLinked() {
		return getOrigin() != null && preferences.getBoolean(linkedPreferenceKey(configDir), false);
	}

	/** Stores provenance only. Any existing live link is explicitly detached. */
	boolean setOrigin(@Nullable String name) {
		if (name == null) {
			return clear();
		}
		return preferences.edit()
				.putString(originPreferenceKey(configDir), name)
				.remove(linkedPreferenceKey(configDir))
				.commit();
	}

	/** Durably publishes origin and link state through one SharedPreferences editor transaction. */
	boolean linkTo(@NonNull String name) {
		return preferences.edit()
				.putString(originPreferenceKey(configDir), name)
				.putBoolean(linkedPreferenceKey(configDir), true)
				.commit();
	}

	/** Stops following the preset while retaining provenance. */
	boolean detach() {
		return preferences.edit().remove(linkedPreferenceKey(configDir)).commit();
	}

	boolean clear() {
		return preferences.edit()
				.remove(originPreferenceKey(configDir))
				.remove(linkedPreferenceKey(configDir))
				.commit();
	}

	static String originPreferenceKey(@NonNull File configDir) {
		return ORIGIN_PREFIX + configDir.getAbsolutePath();
	}

	static String linkedPreferenceKey(@NonNull File configDir) {
		return LINKED_PREFIX + configDir.getAbsolutePath();
	}

	@NonNull
	static String originPreferencePrefix() {
		return ORIGIN_PREFIX;
	}

	@NonNull
	static String linkedPreferenceKeyForOriginKey(@NonNull String originKey) {
		if (!originKey.startsWith(ORIGIN_PREFIX)) {
			throw new IllegalArgumentException("Not a preset origin preference key");
		}
		return LINKED_PREFIX + originKey.substring(ORIGIN_PREFIX.length());
	}
}
