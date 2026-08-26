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

package ru.playsoftware.j2meloader.config;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.FileUtils;

/**
 * Keeps reusable profile components linked to per-game configuration files.
 *
 * <p>The game keeps a local materialized copy for compatibility with the existing runtime. Before
 * that copy is read, an unchanged linked component is refreshed from its profile. A local edit
 * keeps the link as its origin and becomes a modified component; later profile updates do not
 * overwrite that local edit until the user explicitly updates the profile or detaches the link.</p>
 */
final class ProfileLinks {
	private static final String TAG = ProfileLinks.class.getSimpleName();
	private static final String SETTINGS_PREFIX = "profile_link_settings:";
	private static final String KEYBOARD_PREFIX = "profile_link_keyboard:";
	private static final String SETTINGS_HASH_PREFIX = "profile_link_settings_hash:";
	private static final String KEYBOARD_HASH_PREFIX = "profile_link_keyboard_hash:";
	private static final String LEGACY_ORIGIN_PREFIX = "config_profile_origin:";

	private ProfileLinks() {
	}

	static void resolve(@NonNull File configDir) {
		if (!isGameConfigDir(configDir)) return;
		migrateLegacyOrigin(configDir);
		resolveComponent(configDir, true);
		resolveComponent(configDir, false);
	}

	static void linkAppliedComponents(@NonNull Profile profile, @NonNull File configDir,
			boolean settingsRequested, boolean settingsApplied,
			boolean keyboardRequested, boolean keyboardApplied) {
		if (!isGameConfigDir(configDir)) return;
		if (settingsRequested) {
			if (settingsApplied) setLink(configDir, profile.getName(), true);
			else clearLink(configDir, true);
		}
		if (keyboardRequested) {
			if (keyboardApplied) setLink(configDir, profile.getName(), false);
			else clearLink(configDir, false);
		}
	}

	static void refreshLinkedBaselines(@NonNull Profile profile, @NonNull File configDir,
			boolean settingsSaved, boolean keyboardSaved) {
		if (!isGameConfigDir(configDir)) return;
		String name = profile.getName();
		if (settingsSaved && name.equals(getLinkedProfile(configDir, true))) {
			refreshBaseline(configDir, true);
		}
		if (keyboardSaved && name.equals(getLinkedProfile(configDir, false))) {
			refreshBaseline(configDir, false);
		}
	}

	@Nullable
	static String getSettingsProfile(@NonNull File configDir) {
		return getLinkedProfile(configDir, true);
	}

	@Nullable
	static String getKeyboardProfile(@NonNull File configDir) {
		return getLinkedProfile(configDir, false);
	}

	static boolean isSettingsModified(@NonNull File configDir) {
		return isModified(configDir, true);
	}

	static boolean isKeyboardModified(@NonNull File configDir) {
		return isModified(configDir, false);
	}

	/**
	 * Returns true when the shared source changed after this app received its last baseline.
	 * A source that already equals the local modified copy is not a conflict; saving it again is a
	 * harmless no-op that refreshes the baseline.
	 */
	static boolean hasSourceConflict(@NonNull File configDir, boolean settings) {
		String profileName = getLinkedProfile(configDir, settings);
		if (profileName == null) return false;
		Profile profile = new Profile(profileName);
		if (settings && ProfilesManager.loadConfig(profile.getDir(), true) == null) return true;
		File source = settings ? profile.getConfig() : profile.getKeyLayout();
		File local = localFile(configDir, settings);
		if (!source.isFile() || !local.isFile()) return true;
		String baseline = preferences().getString(hashKey(configDir, settings), null);
		if (baseline == null) return true;
		try {
			String sourceHash = hash(source);
			if (sourceHash.equals(baseline)) return false;
			return !sourceHash.equals(hash(local));
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Unable to inspect linked profile conflict: " + profileName, e);
			return true;
		}
	}

	static void detachSettings(@NonNull File configDir) {
		clearLink(configDir, true);
		clearLegacyOrigin(configDir);
	}

	static void detachKeyboard(@NonNull File configDir) {
		clearLink(configDir, false);
		clearLegacyOrigin(configDir);
	}

	static void renameProfile(@NonNull String oldName, @NonNull String newName) {
		if (oldName.equals(newName)) return;
		SharedPreferences prefs = preferences();
		SharedPreferences.Editor editor = prefs.edit();
		for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if ((key.startsWith(SETTINGS_PREFIX) || key.startsWith(KEYBOARD_PREFIX)
					|| key.startsWith(LEGACY_ORIGIN_PREFIX)) && oldName.equals(value)) {
				editor.putString(key, newName);
			}
		}
		editor.apply();
	}

	static void unlinkProfile(@NonNull String name) {
		SharedPreferences prefs = preferences();
		SharedPreferences.Editor editor = prefs.edit();
		for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (key.startsWith(SETTINGS_PREFIX) && name.equals(value)) {
				String suffix = key.substring(SETTINGS_PREFIX.length());
				editor.remove(key).remove(SETTINGS_HASH_PREFIX + suffix);
			} else if (key.startsWith(KEYBOARD_PREFIX) && name.equals(value)) {
				String suffix = key.substring(KEYBOARD_PREFIX.length());
				editor.remove(key).remove(KEYBOARD_HASH_PREFIX + suffix);
			} else if (key.startsWith(LEGACY_ORIGIN_PREFIX) && name.equals(value)) {
				editor.remove(key);
			}
		}
		editor.apply();
	}

	private static void resolveComponent(@NonNull File configDir, boolean settings) {
		String profileName = getLinkedProfile(configDir, settings);
		if (profileName == null) return;

		Profile profile = new Profile(profileName);
		if (settings && ProfilesManager.loadConfig(profile.getDir(), true) == null) {
			clearLink(configDir, true);
			return;
		}
		File source = settings ? profile.getConfig() : profile.getKeyLayout();
		File local = localFile(configDir, settings);
		if (!source.isFile()) {
			// A deleted component must never destroy the last usable per-game copy.
			clearLink(configDir, settings);
			return;
		}

		try {
			String sourceHash = hash(source);
			String localHash = local.isFile() ? hash(local) : null;
			String lastHash = preferences().getString(hashKey(configDir, settings), null);

			if (sourceHash.equals(localHash)) {
				storeBaseline(configDir, settings, sourceHash);
				return;
			}
			if (localHash != null && lastHash != null && !lastHash.equals(localHash)) {
				// The game copy changed since the last materialization. Keep the source link so the
				// UI can show "Profile · Modified", but never overwrite that local customization.
				return;
			}
			if (localHash != null && lastHash == null) {
				// Unknown provenance: preserve user data and keep the source visible. The first
				// explicit apply/update will establish a safe baseline.
				return;
			}

			FileUtils.copyFileUsingChannel(source, local);
			storeBaseline(configDir, settings, hash(local));
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "Unable to refresh linked profile component: " + profileName, e);
		}
	}

	private static boolean isModified(@NonNull File configDir, boolean settings) {
		if (getLinkedProfile(configDir, settings) == null) return false;
		File local = localFile(configDir, settings);
		String baseline = preferences().getString(hashKey(configDir, settings), null);
		if (baseline == null) return local.isFile();
		if (!local.isFile()) return true;
		try {
			return !baseline.equals(hash(local));
		} catch (IOException e) {
			Log.w(TAG, "Unable to inspect linked profile component", e);
			return true;
		}
	}

	/**
	 * Converts the old single profile-origin marker into explicit per-component links. The origin
	 * marker itself is explicit provenance, so a locally modified component is still safe to link:
	 * its baseline is the source profile hash, which preserves the local file as Modified instead of
	 * overwriting it or pretending it is synchronized.
	 */
	private static void migrateLegacyOrigin(@NonNull File configDir) {
		SharedPreferences prefs = preferences();
		String legacyKey = LEGACY_ORIGIN_PREFIX + configDir.getAbsolutePath();
		String legacyName = prefs.getString(legacyKey, null);
		if (legacyName == null) return;

		Profile profile = new Profile(legacyName);
		if (!profile.hasConfig() && profile.hasOldConfig()) {
			ProfilesManager.loadConfig(profile.getDir(), true);
		}

		try {
			migrateLegacyComponent(configDir, profile.getName(), profile.getConfig(), true);
			migrateLegacyComponent(configDir, profile.getName(), profile.getKeyLayout(), false);
			// The old marker is no longer needed once every available component has had a chance to
			// become an explicit link. Removing it also makes an explicit detach permanent.
			prefs.edit().remove(legacyKey).apply();
		} catch (IOException e) {
			// Preserve the marker so a transient I/O failure can be retried on the next load.
			Log.w(TAG, "Unable to migrate legacy profile origin: " + legacyName, e);
		}
	}

	private static void migrateLegacyComponent(@NonNull File configDir, @NonNull String profileName,
			@NonNull File source, boolean settings) throws IOException {
		File local = localFile(configDir, settings);
		if (getLinkedProfile(configDir, settings) != null || !source.isFile() || !local.isFile()) {
			return;
		}
		preferences().edit()
				.putString(linkKey(configDir, settings), profileName)
				.putString(hashKey(configDir, settings), hash(source))
				.apply();
	}

	private static void setLink(@NonNull File configDir, @NonNull String profileName,
			boolean settings) {
		File local = localFile(configDir, settings);
		SharedPreferences.Editor editor = preferences().edit()
				.putString(linkKey(configDir, settings), profileName);
		try {
			if (local.isFile()) editor.putString(hashKey(configDir, settings), hash(local));
			else editor.remove(hashKey(configDir, settings));
		} catch (IOException e) {
			Log.w(TAG, "Unable to record linked profile baseline: " + profileName, e);
			editor.remove(hashKey(configDir, settings));
		}
		editor.apply();
	}

	private static void refreshBaseline(@NonNull File configDir, boolean settings) {
		File local = localFile(configDir, settings);
		if (!local.isFile()) {
			preferences().edit().remove(hashKey(configDir, settings)).apply();
			return;
		}
		try {
			storeBaseline(configDir, settings, hash(local));
		} catch (IOException e) {
			Log.w(TAG, "Unable to refresh linked profile baseline", e);
		}
	}

	private static void storeBaseline(@NonNull File configDir, boolean settings,
			@NonNull String value) {
		preferences().edit().putString(hashKey(configDir, settings), value).apply();
	}

	private static void clearLink(@NonNull File configDir, boolean settings) {
		preferences().edit()
				.remove(linkKey(configDir, settings))
				.remove(hashKey(configDir, settings))
				.apply();
	}

	private static void clearLegacyOrigin(@NonNull File configDir) {
		preferences().edit()
				.remove(LEGACY_ORIGIN_PREFIX + configDir.getAbsolutePath())
				.apply();
	}

	@Nullable
	private static String getLinkedProfile(@NonNull File configDir, boolean settings) {
		return preferences().getString(linkKey(configDir, settings), null);
	}

	@NonNull
	private static File localFile(@NonNull File configDir, boolean settings) {
		return new File(configDir, settings ? Config.MIDLET_CONFIG_FILE : Config.MIDLET_KEY_LAYOUT_FILE);
	}

	private static boolean isGameConfigDir(@NonNull File configDir) {
		File parent = configDir.getParentFile();
		return parent != null && parent.equals(new File(Config.getConfigsDir()));
	}

	@NonNull
	private static String linkKey(@NonNull File configDir, boolean settings) {
		return (settings ? SETTINGS_PREFIX : KEYBOARD_PREFIX) + keySuffix(configDir);
	}

	@NonNull
	private static String hashKey(@NonNull File configDir, boolean settings) {
		return (settings ? SETTINGS_HASH_PREFIX : KEYBOARD_HASH_PREFIX) + keySuffix(configDir);
	}

	@NonNull
	private static String keySuffix(@NonNull File configDir) {
		return configDir.getAbsolutePath();
	}

	@NonNull
	private static SharedPreferences preferences() {
		return PreferenceManager.getDefaultSharedPreferences(ContextHolder.getAppContext());
	}

	@NonNull
	private static String hash(@NonNull File file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = new FileInputStream(file)) {
				byte[] buffer = new byte[8192];
				for (int read; (read = input.read(buffer)) != -1; ) {
					digest.update(buffer, 0, read);
				}
			}
			byte[] bytes = digest.digest();
			StringBuilder out = new StringBuilder(bytes.length * 2);
			for (byte value : bytes) {
				out.append(Character.forDigit((value >>> 4) & 0x0f, 16));
				out.append(Character.forDigit(value & 0x0f, 16));
			}
			return out.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 unavailable", e);
		}
	}
}
