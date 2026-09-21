/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Orders a configuration-source replacement against every durable source-ownership marker.
 *
 * <p>This guard is deliberately separate from {@link PresetLocalOverride}: source replacement
 * clears both named-preset ownership and built-in-theme ownership, while an ordinary local edit
 * keeps named provenance and only detaches its live link.</p>
 */
final class PresetSourceReplacement {
	private PresetSourceReplacement() {
	}

	@NonNull
	static Guard begin(@NonNull SharedPreferences preferences, @NonNull File configDir) {
		String originKey = PresetLinkage.originPreferenceKey(configDir);
		String linkedKey = PresetLinkage.linkedPreferenceKey(configDir);
		String builtInKey = ProfileModel.builtInThemePreferenceKey(configDir);
		@Nullable String previousOrigin = preferences.getString(originKey, null);
		boolean previousLinkedMarker = preferences.getBoolean(linkedKey, false);
		boolean previousBuiltInThemeLinked = preferences.getBoolean(builtInKey, false);
		// Always perform the synchronous clear, even when the process-visible map is already empty.
		// A preceding SharedPreferences.apply() may have changed memory before its disk write lands;
		// source replacement needs a durability barrier, not only a logical state transition.
		if (!clearOwnership(preferences, configDir)) {
			// SharedPreferences may already expose a failed commit in memory. Restore the old
			// source metadata best-effort because filesystem publication is still forbidden.
			if (!restoreOwnership(preferences, configDir, previousOrigin, previousLinkedMarker,
					previousBuiltInThemeLinked)) {
				clearOwnership(preferences, configDir);
			}
			return new Guard(preferences, configDir, previousOrigin, previousLinkedMarker,
					previousBuiltInThemeLinked, false, false);
		}

		return new Guard(preferences, configDir, previousOrigin, previousLinkedMarker,
				previousBuiltInThemeLinked, true, true);
	}

	private static boolean clearOwnership(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		return preferences.edit()
				.remove(PresetLinkage.originPreferenceKey(configDir))
				.remove(PresetLinkage.linkedPreferenceKey(configDir))
				.remove(ProfileModel.builtInThemePreferenceKey(configDir))
				.commit();
	}

	private static boolean restoreOwnership(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir,
			@Nullable String origin,
			boolean linkedMarker,
			boolean builtInThemeLinked) {
		SharedPreferences.Editor editor = preferences.edit();
		String originKey = PresetLinkage.originPreferenceKey(configDir);
		String linkedKey = PresetLinkage.linkedPreferenceKey(configDir);
		String builtInKey = ProfileModel.builtInThemePreferenceKey(configDir);
		if (origin != null) editor.putString(originKey, origin);
		else editor.remove(originKey);
		if (linkedMarker) editor.putBoolean(linkedKey, true);
		else editor.remove(linkedKey);
		if (builtInThemeLinked) editor.putBoolean(builtInKey, true);
		else editor.remove(builtInKey);
		return editor.commit();
	}

	static final class Guard {
		@NonNull private final SharedPreferences preferences;
		@NonNull private final File configDir;
		@Nullable private final String previousOrigin;
		private final boolean previousLinkedMarker;
		private final boolean previousBuiltInThemeLinked;
		private final boolean transitionPerformed;
		private final boolean writeAllowed;

		private Guard(
				@NonNull SharedPreferences preferences,
				@NonNull File configDir,
				@Nullable String previousOrigin,
				boolean previousLinkedMarker,
				boolean previousBuiltInThemeLinked,
				boolean transitionPerformed,
				boolean writeAllowed) {
			this.preferences = preferences;
			this.configDir = configDir;
			this.previousOrigin = previousOrigin;
			this.previousLinkedMarker = previousLinkedMarker;
			this.previousBuiltInThemeLinked = previousBuiltInThemeLinked;
			this.transitionPerformed = transitionPerformed;
			this.writeAllowed = writeAllowed;
		}

		boolean canWrite() {
			return writeAllowed;
		}

		@Nullable
		String previousOrigin() {
			return previousOrigin;
		}

		boolean wasBuiltInThemeLinked() {
			return previousBuiltInThemeLinked;
		}

		/**
		 * Restores old ownership only when the caller proved that no replacement publication
		 * committed. Failed restoration is forced toward CUSTOM rather than reviving ownership.
		 */
		boolean restoreIfUnchanged() {
			if (!writeAllowed || !transitionPerformed) {
				return writeAllowed;
			}
			if (restoreOwnership(preferences, configDir, previousOrigin, previousLinkedMarker,
					previousBuiltInThemeLinked)) {
				return true;
			}
			clearOwnership(preferences, configDir);
			return false;
		}

		/**
		 * Publishes intentional built-in ownership after the built-in snapshot has committed.
		 * Named ownership stays cleared in the same durable transaction.
		 */
		boolean publishBuiltInOwnership() {
			if (!writeAllowed) return false;
			boolean published = preferences.edit()
					.remove(PresetLinkage.originPreferenceKey(configDir))
					.remove(PresetLinkage.linkedPreferenceKey(configDir))
					.putBoolean(ProfileModel.builtInThemePreferenceKey(configDir), true)
					.commit();
			if (!published) {
				clearOwnership(preferences, configDir);
			}
			return published;
		}
	}
}
