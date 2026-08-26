/*
 * Copyright 2018 Nikita Shakarun
 *
 * Modified by JL-Mod Plus contributors; original upstream attribution is retained.
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

package ru.playsoftware.j2meloader.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.TimingMode;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.XmlUtils;

public class ProfilesManager {

	private static final String TAG = ProfilesManager.class.getName();
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	static final class ProfileUpdateConflictException extends IOException {
		ProfileUpdateConflictException(@NonNull String profileName) {
			super("Profile changed since this app was linked: " + profileName);
		}
	}

	static ArrayList<Profile> getProfiles() {
		File root = new File(Config.getProfilesDir());
		return getList(root);
	}

	@NonNull
	private static ArrayList<Profile> getList(File root) {
		File[] dirs = root.listFiles();
		if (dirs == null) {
			return new ArrayList<>();
		}
		int size = dirs.length;
		Profile[] profiles = new Profile[size];
		for (int i = 0; i < size; i++) {
			profiles[i] = new Profile(dirs[i].getName());
		}
		return new ArrayList<>(Arrays.asList(profiles));
	}

	static void load(Profile from, String toPath, boolean config, boolean keyboard)
			throws IOException {
		ProfileModel sourceConfig = config && (from.hasConfig() || from.hasOldConfig())
				? loadConfig(from.getDir(), true)
				: null;
		boolean configRequested = config && sourceConfig != null;
		boolean keyboardRequested = keyboard && from.hasKeyLayout();
		if (!configRequested && !keyboardRequested) {
			return;
		}
		File configDir = new File(toPath);
		File dstConfig = new File(configDir, Config.MIDLET_CONFIG_FILE);
		File dstKeyLayout = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
		boolean configApplied = false;
		boolean keyboardApplied = false;

		if (configRequested) {
			File source = from.getConfig();
			if (source.isFile()) {
				// loadConfig() above validates and migrates the source before it is materialized.
				FileUtils.copyFileUsingChannel(source, dstConfig);
				configApplied = true;
			} else {
				sourceConfig.dir = configDir;
				configApplied = saveConfig(sourceConfig);
			}
		}
		if (keyboardRequested) {
			FileUtils.copyFileUsingChannel(from.getKeyLayout(), dstKeyLayout);
			keyboardApplied = true;
		}

		ProfileLinks.linkAppliedComponents(from, configDir,
				configRequested, configApplied, keyboardRequested, keyboardApplied);
	}

	static void save(Profile profile, String fromPath, boolean config, boolean keyboard)
			throws IOException {
		if (!config && !keyboard) {
			return;
		}
		profile.create();
		File fromDir = new File(fromPath);
		File srcConfig = new File(fromDir, Config.MIDLET_CONFIG_FILE);
		File srcKeyLayout = new File(fromDir, Config.MIDLET_KEY_LAYOUT_FILE);
		boolean configSaved = false;
		boolean keyboardSaved = false;
		if (config && srcConfig.isFile()) {
			FileUtils.copyFileUsingChannel(srcConfig, profile.getConfig());
			configSaved = true;
		}
		if (keyboard && srcKeyLayout.isFile()) {
			FileUtils.copyFileUsingChannel(srcKeyLayout, profile.getKeyLayout());
			keyboardSaved = true;
		}
		ProfileLinks.refreshLinkedBaselines(profile, fromDir, configSaved, keyboardSaved);
	}

	/**
	 * Saves a reusable template snapshot while preserving component scope. For a linked game,
	 * Update writes only components that are actually modified in that game; an unchanged sibling
	 * component is never rewritten as a side effect of updating the other one.
	 */
	static void saveSnapshot(Profile profile, String fromPath) throws IOException {
		File fromDir = new File(fromPath);
		boolean existingConfig = profile.hasConfig() || profile.hasOldConfig();
		boolean existingKeyboard = profile.hasKeyLayout();
		if (existingConfig || existingKeyboard) {
			boolean linkedConfig = profile.getName().equals(ProfileLinks.getSettingsProfile(fromDir));
			boolean linkedKeyboard = profile.getName().equals(ProfileLinks.getKeyboardProfile(fromDir));
			if (linkedConfig || linkedKeyboard) {
				boolean updateConfig = existingConfig && linkedConfig
						&& ProfileLinks.isSettingsModified(fromDir);
				boolean updateKeyboard = existingKeyboard && linkedKeyboard
						&& ProfileLinks.isKeyboardModified(fromDir);
				if ((updateConfig && ProfileLinks.hasSourceConflict(fromDir, true))
						|| (updateKeyboard && ProfileLinks.hasSourceConflict(fromDir, false))) {
					throw new ProfileUpdateConflictException(profile.getName());
				}
				if (updateConfig || updateKeyboard) {
					save(profile, fromPath, updateConfig, updateKeyboard);
				}
			} else {
				// Legacy/unlinked profile editing keeps the old profile's component scope.
				save(profile, fromPath, existingConfig, existingKeyboard);
			}
			return;
		}

		File srcConfig = new File(fromDir, Config.MIDLET_CONFIG_FILE);
		File srcKeyLayout = new File(fromDir, Config.MIDLET_KEY_LAYOUT_FILE);
		profile.create();
		boolean configSaved = false;
		boolean keyboardSaved = false;
		if (srcConfig.isFile()) {
			FileUtils.copyFileUsingChannel(srcConfig, profile.getConfig());
			configSaved = true;
		}
		if (srcKeyLayout.isFile()) {
			FileUtils.copyFileUsingChannel(srcKeyLayout, profile.getKeyLayout());
			keyboardSaved = true;
		}
		ProfileLinks.refreshLinkedBaselines(profile, fromDir, configSaved, keyboardSaved);
	}

	/** Loads a game config after refreshing any reusable profile links. */
	@Nullable
	public static ProfileModel loadGameConfig(File dir) {
		ProfileLinks.resolve(dir);
		return loadConfig(dir, true);
	}

	/** Generic profile/config loading deliberately has no game-link side effects. */
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
				android.util.Log.e(TAG, "loadConfig: ", e);
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
						android.util.Log.d(TAG, "loadConfig: old config file deleted");
					}
				} catch (Exception e) {
					android.util.Log.e(TAG, "loadConfig: ", e);
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
		int normalizedSpeed = EmulationSpeed.sanitizePercent(params.emulationSpeedPercent);
		boolean speedNeedsMigration = params.emulationSpeedPercent != normalizedSpeed;
		params.emulationSpeedPercent = normalizedSpeed;
		int normalizedTimingMode = TimingMode.sanitize(params.timingMode);
		boolean timingModeNeedsMigration = params.timingMode != normalizedTimingMode;
		params.timingMode = normalizedTimingMode;
		if (versionNeedsMigration) {
			params.version = ProfileModel.VERSION;
		}
		if (persistMigrations && (versionNeedsMigration || speedNeedsMigration
				|| timingModeNeedsMigration)) {
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
			android.util.Log.e(TAG, "saveConfig: ", e);
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
