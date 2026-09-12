/*
 * Copyright 2018 Nikita Shakarun
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

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import javax.microedition.shell.timing.TimingMode;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.util.XmlUtils;

public class ProfilesManager {

	private static final String TAG = ProfilesManager.class.getName();
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	static ArrayList<Profile> getProfiles() {
		File root = new File(Config.getProfilesDir());
		return getList(root);
	}

	/** A disk-only inspection result prepared before Compose state is updated. */
	static final class ProfileInfo {
		@NonNull final Profile profile;
		@Nullable final ProfileModel config;
		final boolean validGamePreset;
		final boolean usableKeyboardLayout;

		ProfileInfo(@NonNull Profile profile, @Nullable ProfileModel config,
				boolean usableKeyboardLayout) {
			this.profile = profile;
			this.config = config;
			this.validGamePreset = config != null;
			this.usableKeyboardLayout = usableKeyboardLayout;
		}
	}

	/** Performs profile parsing and artifact checks in the caller's worker thread. */
	@NonNull
	static ArrayList<ProfileInfo> inspectProfiles(@Nullable List<Profile> profiles) {
		ArrayList<ProfileInfo> result = new ArrayList<>();
		if (profiles == null) return result;
		for (Profile profile : profiles) {
			ProfileModel config = (profile.hasConfig() || profile.hasOldConfig())
					? loadConfig(profile.getDir(), false) : null;
			result.add(new ProfileInfo(profile, config, profile.hasUsableKeyLayout()));
		}
		return result;
	}

	/** Returns true when a directory or a saved layout already occupies this collection name. */
	static boolean profileNameExists(@Nullable String rawName) {
		if (!Profile.isValidName(rawName)) return false;
		String name = rawName.trim();
		for (Profile profile : getProfiles()) {
			if (profile.getName().equalsIgnoreCase(name)) return true;
		}
		File root = new File(Config.getProfilesDir());
		return new File(root, name).exists()
				|| new File(root, name + Config.MIDLET_CONFIG_FILE).exists()
				|| new File(root, name + Config.MIDLET_KEY_LAYOUT_FILE).exists();
	}

	@NonNull
	private static ArrayList<Profile> getList(File root) {
		File[] dirs = root.listFiles();
		if (dirs == null) {
			return new ArrayList<>();
		}
		int size = dirs.length;
		ArrayList<Profile> result = new ArrayList<>(size);
		for (File dir : dirs) {
			if (dir.isDirectory()) {
				result.add(new Profile(dir.getName()));
			}
		}
		return result;
	}

	static void load(Profile from, String toPath, boolean config, boolean keyboard)
			throws IOException {
		if (!config && !keyboard) {
			return;
		}
		File dstConfig = new File(toPath, Config.MIDLET_CONFIG_FILE);
		File dstKeyLayout = new File(toPath, Config.MIDLET_KEY_LAYOUT_FILE);
		if (config) {
			File source = from.getConfig();
			if (source.exists()) {
				FileUtils.copyFileUsingChannel(source, dstConfig);
			} else {
				ProfileModel params = loadConfig(from.getDir());
				if (params == null) {
					throw new IOException("Profile configuration is not loadable");
				}
				params.dir = dstConfig.getParentFile();
				if (!saveConfig(params)) {
					throw new IOException("Unable to materialize profile configuration");
				}
			}
		}
		if (keyboard) {
			if (!from.hasUsableKeyLayout()) {
				throw new IOException("Profile keyboard layout is not loadable");
			}
			FileUtils.copyFileUsingChannel(from.getKeyLayout(), dstKeyLayout);
		}
	}

	static void save(Profile profile, String fromPath, boolean config, boolean keyboard)
			throws IOException {
		if (!config && !keyboard) {
			return;
		}
		profile.create();
		File srcConfig = new File(fromPath, Config.MIDLET_CONFIG_FILE);
		File srcKeyLayout = new File(fromPath, Config.MIDLET_KEY_LAYOUT_FILE);
		if (config) FileUtils.copyFileUsingChannel(srcConfig, profile.getConfig());
		if (keyboard) FileUtils.copyFileUsingChannel(srcKeyLayout, profile.getKeyLayout());
	}

	/** Saves the current MIDlet configuration as one reusable template snapshot. */
	static void saveSnapshot(Profile profile, String fromPath) throws IOException {
		saveSnapshot(profile, fromPath, true);
	}

	/**
	 * Saves a reusable game preset. Game settings are always copied; the separate keyboard layout
	 * is copied only when explicitly requested, and stale destination layouts are removed.
	 */
	static void saveSnapshot(Profile profile, String fromPath, boolean includeKeyboard) throws IOException {
		profile.create();
		File srcConfig = new File(fromPath, Config.MIDLET_CONFIG_FILE);
		File srcKeyLayout = new File(fromPath, Config.MIDLET_KEY_LAYOUT_FILE);
		File dstKeyLayout = profile.getKeyLayout();
		FileUtils.copyFileUsingChannel(srcConfig, profile.getConfig());
		if (includeKeyboard && Config.isUsableFile(srcKeyLayout)) {
			FileUtils.copyFileUsingChannel(srcKeyLayout, dstKeyLayout);
		} else if (dstKeyLayout.exists() && !dstKeyLayout.delete()) {
			Log.w(TAG, "saveSnapshot: could not remove stale key layout " + dstKeyLayout);
		}
	}

	/** Returns whether a profile owns loadable game settings, regardless of keyboard ownership. */
	static boolean isValidGamePreset(@Nullable Profile profile) {
		return profile != null
				&& (profile.hasConfig() || profile.hasOldConfig())
				&& loadConfig(profile.getDir(), false) != null;
	}

	/** Resolves a preference to a valid game preset without treating keyboard-only data as one. */
	@Nullable
	static Profile findValidGamePreset(@Nullable String name) {
		if (name == null) {
			return null;
		}
		for (Profile profile : getProfiles()) {
			if (name.equals(profile.getName()) && isValidGamePreset(profile)) {
				return profile;
			}
		}
		return null;
	}

	/** Resolves a saved collection entry without requiring it to contain a game configuration. */
	@Nullable
	static Profile findProfile(@Nullable String name) {
		if (name == null) return null;
		for (Profile profile : getProfiles()) {
			if (name.equals(profile.getName())) return profile;
		}
		return null;
	}

	@Nullable
	public static ProfileModel loadConfig(File dir) {
		return loadConfig(dir, true);
	}

	/** Loads a profile and optionally persists legacy-format migrations. */
	@Nullable
	static ProfileModel loadConfig(File dir, boolean persistMigrations) {
		File file = new File(dir, Config.MIDLET_CONFIG_FILE);
		ProfileModel params = null;
		if (file.exists()) {
			try (FileReader reader = new FileReader(file)) {
				params = gson.fromJson(reader, ProfileModel.class);
				params.dir = dir;
			} catch (Exception e) {
				Log.e(TAG, "loadConfig: ", e);
			}
		}
		if (params == null) {
			File oldFile = new File(dir, "config.xml");
			if (oldFile.exists()) {
				try (FileInputStream in = new FileInputStream(oldFile)) {
					HashMap<String, Object> map = XmlUtils.readMapXml(in);
					JsonElement json = gson.toJsonTree(map);
					params = gson.fromJson(json, ProfileModel.class);
					params.dir = dir;
					if (persistMigrations && saveConfig(params) && oldFile.delete()) {
						Log.d(TAG, "loadConfig: old config file deleted");
					}
				} catch (Exception e) {
					Log.e(TAG, "loadConfig: ", e);
				}
			}
		}
		if (params == null) {
			return null;
		}
		switch (params.version) {
			case 0:
				if (params.hwAcceleration) {
					params.graphicsMode = 3;
				}
				updateSystemProperties(params);
			case 1:
				int w = params.screenWidth;
				int h = params.screenHeight;
				if (w > 0) {
					if (h > 0) {
						params.fontAA = Math.min(w, h) >= 240;
					} else {
						params.fontAA = w >= 240;
					}
				} else {
					params.fontAA = (h <= 0) || (h >= 240);
				}
			case 2:
				if (params.screenScaleToFit) {
					if (params.screenKeepAspectRatio) {
						params.screenScaleType = 1;
					} else {
						params.screenScaleType = 2;
					}
				} else {
					params.screenScaleType = 0;
				}
				params.screenGravity = 1;
				break;
		}
		boolean versionNeedsMigration = params.version < ProfileModel.VERSION;
		int normalizedTimingMode = TimingMode.sanitize(params.timingMode);
		boolean timingModeNeedsMigration = params.timingMode != normalizedTimingMode;
		params.timingMode = normalizedTimingMode;
		if (versionNeedsMigration) {
			params.version = ProfileModel.VERSION;
		}
		if (persistMigrations && (versionNeedsMigration || timingModeNeedsMigration)) {
			ProfilesManager.saveConfig(params);
		}
		return params;
	}

	public static boolean saveConfig(ProfileModel p) {
		try (FileWriter writer = new FileWriter(new File(p.dir, Config.MIDLET_CONFIG_FILE))) {
			gson.toJson(p, writer);
			writer.close();
			return true;
		} catch (Exception e) {
			Log.e(TAG, "saveConfig: ", e);
		}
		return false;
	}

	public static void updateSystemProperties(ProfileModel params) {
		String defaultProperties = ContextHolder.getAssetAsString("defaults/system.props");
		String properties = params.systemProperties;
		StringBuilder sb = new StringBuilder();
		if (properties == null) {
			params.systemProperties = defaultProperties;
			return;
		}
		sb.append(properties);
		String[] defaults = defaultProperties.split("[\\n\\r]+");
		for (String line : defaults) {
			if (properties.contains(line.substring(0, line.indexOf(':')))) continue;
			sb.append(line).append('\n');
		}
		params.systemProperties = sb.toString();
	}
}
