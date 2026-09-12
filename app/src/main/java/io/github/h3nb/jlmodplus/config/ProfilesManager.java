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
	enum CapabilityStatus {
		ABSENT,
		READY,
		UNAVAILABLE
	}

	static final class Capability {
		@NonNull final CapabilityStatus status;
		@Nullable final String reason;

		Capability(@NonNull CapabilityStatus status, @Nullable String reason) {
			this.status = status;
			this.reason = reason;
		}

		boolean isReady() {
			return status == CapabilityStatus.READY;
		}
	}

	static final class ProfileInfo {
		@NonNull final Profile profile;
		@Nullable final ProfileModel config;
		@NonNull final Capability settings;
		@NonNull final Capability keyboardLayout;

		ProfileInfo(@NonNull Profile profile, @Nullable ProfileModel config,
				@NonNull Capability settings, @NonNull Capability keyboardLayout) {
			this.profile = profile;
			this.config = config;
			this.settings = settings;
			this.keyboardLayout = keyboardLayout;
		}
	}

	/** Performs profile parsing and artifact checks in the caller's worker thread. */
	@NonNull
	static ArrayList<ProfileInfo> inspectProfiles(@Nullable List<Profile> profiles) {
		ArrayList<ProfileInfo> result = new ArrayList<>();
		if (profiles == null) return result;
		for (Profile profile : profiles) {
			result.add(inspectProfile(profile));
		}
		return result;
	}

	/** Computes both independent capabilities once so picker and operation code share the same view. */
	@NonNull
	static ProfileInfo inspectProfile(@NonNull Profile profile) {
		boolean hasConfigArtifact = profile.hasConfig() || profile.hasOldConfig();
		ProfileModel config = hasConfigArtifact ? loadConfig(profile.getDir(), false) : null;
		Capability settings = hasConfigArtifact
				? new Capability(config == null ? CapabilityStatus.UNAVAILABLE : CapabilityStatus.READY,
						config == null ? "configuration cannot be parsed" : null)
				: new Capability(CapabilityStatus.ABSENT, null);
		boolean hasLayoutArtifact = profile.hasKeyLayout();
		String layoutReason = hasLayoutArtifact
				? KeyboardLayoutValidator.validate(profile.getKeyLayout()) : null;
		Capability layout = !hasLayoutArtifact
				? new Capability(CapabilityStatus.ABSENT, null)
				: new Capability(layoutReason == null ? CapabilityStatus.READY : CapabilityStatus.UNAVAILABLE,
						layoutReason);
		return new ProfileInfo(profile, config, settings, layout);
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
		File targetDir = new File(toPath);
		if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
			throw new IOException("Unable to create configuration directory");
		}
		ProfileInfo inspected = inspectProfile(from);
		if (config && !inspected.settings.isReady()) {
			throw new IOException("Profile configuration is not loadable");
		}
		if (keyboard && !inspected.keyboardLayout.isReady()) {
			throw new IOException("Profile keyboard layout is not loadable");
		}
		File staging = new File(targetDir, ".preset-apply.tmp");
		File rollback = new File(targetDir, ".preset-apply.rollback");
		deleteRecursively(staging);
		deleteRecursively(rollback);
		boolean configCommitStarted = false;
		boolean keyboardCommitStarted = false;
		boolean rollbackSucceeded = true;
		try {
			if (!staging.mkdirs() || !rollback.mkdirs()) {
				throw new IOException("Unable to create preset staging directory");
			}
			File stagedConfig = new File(staging, Config.MIDLET_CONFIG_FILE);
			File stagedLayout = new File(staging, Config.MIDLET_KEY_LAYOUT_FILE);
			if (config) {
				File source = from.getConfig();
				if (source.isFile()) {
					FileUtils.copyFileUsingChannel(source, stagedConfig);
				} else {
					ProfileModel params = inspected.config;
					if (params == null) {
						throw new IOException("Profile configuration is not loadable");
					}
					File originalDir = params.dir;
					try {
						params.dir = staging;
						if (!saveConfig(params)) {
							throw new IOException("Unable to materialize profile configuration");
						}
					} finally {
						params.dir = originalDir;
					}
				}
				if (!isValidConfigFile(stagedConfig)) {
					throw new IOException("Profile configuration changed while applying");
				}
			}
			if (keyboard) {
				FileUtils.copyFileUsingChannel(from.getKeyLayout(), stagedLayout);
				String layoutError = KeyboardLayoutValidator.validate(stagedLayout);
				if (layoutError != null) {
					throw new IOException("Profile keyboard layout changed while applying: " + layoutError);
				}
			}
			File dstConfig = new File(targetDir, Config.MIDLET_CONFIG_FILE);
			File dstKeyLayout = new File(targetDir, Config.MIDLET_KEY_LAYOUT_FILE);
			if (config) {
				backupExisting(dstConfig, rollback, "config.json");
				configCommitStarted = true;
				FileUtils.copyFileUsingChannel(stagedConfig, dstConfig);
			}
			if (keyboard) {
				backupExisting(dstKeyLayout, rollback, "VirtualKeyboardLayout");
				keyboardCommitStarted = true;
				FileUtils.copyFileUsingChannel(stagedLayout, dstKeyLayout);
			}
		} catch (IOException | RuntimeException failure) {
			File dstConfig = new File(targetDir, Config.MIDLET_CONFIG_FILE);
			File dstKeyLayout = new File(targetDir, Config.MIDLET_KEY_LAYOUT_FILE);
			if (configCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, dstConfig, rollback, "config.json");
			}
			if (keyboardCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, dstKeyLayout, rollback,
						"VirtualKeyboardLayout");
			}
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			deleteRecursively(staging);
			if (rollbackSucceeded) deleteRecursively(rollback);
		}
	}

	private static boolean backupExisting(File destination, File rollback, String name)
			throws IOException {
		File marker = new File(rollback, name + ".present");
		if (destination.exists()) {
			if (!destination.isFile()) {
				throw new IOException("Profile artifact is not a file: " + destination.getName());
			}
			FileUtils.copyFileUsingChannel(destination, new File(rollback, name));
			if (!marker.createNewFile()) throw new IOException("Unable to mark existing file");
			return true;
		}
		return false;
	}

	private static void restoreExisting(File destination, File rollback, String name)
			throws IOException {
		File saved = new File(rollback, name);
		if (new File(rollback, name + ".present").isFile()) {
			FileUtils.copyFileUsingChannel(saved, destination);
		} else if (destination.exists() && !destination.delete()) {
			throw new IOException("Unable to remove partially applied file");
		}
	}

	private static boolean tryRestore(Throwable failure, File destination, File rollback, String name) {
		try {
			restoreExisting(destination, rollback, name);
			return true;
		} catch (IOException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
			return false;
		}
	}

	private static void prepareRollbackDirectory(File rollback) throws IOException {
		deleteRecursively(rollback);
		if (rollback.exists() || !rollback.mkdirs()) {
			throw new IOException("Unable to create profile rollback directory");
		}
	}

	private static boolean isValidConfigFile(File file) {
		if (file == null || !file.isFile() || file.length() <= 0L) return false;
		try (FileReader reader = new FileReader(file)) {
			return gson.fromJson(reader, ProfileModel.class) != null;
		} catch (Exception e) {
			return false;
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists()) {
			Log.w(TAG, "Unable to remove temporary profile operation file " + file);
		}
	}

	/**
	 * Saves a reusable application preset. Application settings are always copied; the separate
	 * keyboard layout is copied only when explicitly requested, and stale destination layouts are removed.
	 */
	static void saveSnapshot(Profile profile, String fromPath, boolean includeKeyboard) throws IOException {
		File srcConfig = new File(fromPath, Config.MIDLET_CONFIG_FILE);
		if (!isValidConfigFile(srcConfig)) {
			throw new IOException("Current application configuration is not loadable");
		}
		File srcKeyLayout = new File(fromPath, Config.MIDLET_KEY_LAYOUT_FILE);
		if (includeKeyboard) {
			String layoutError = KeyboardLayoutValidator.validate(srcKeyLayout);
			if (layoutError != null) {
				throw new IOException("Current keyboard layout is not loadable: " + layoutError);
			}
		}
		boolean profileExisted = profile.getDir().exists();
		profile.create();
		File legacyConfig = new File(profile.getDir(), "config.xml");
		File dstKeyLayout = profile.getKeyLayout();
		File rollback = new File(profile.getDir(), ".preset-save.rollback");
		boolean configCommitStarted = false;
		boolean legacyCommitStarted = false;
		boolean keyboardCommitStarted = false;
		boolean rollbackSucceeded = true;
		try {
			prepareRollbackDirectory(rollback);
			backupExisting(profile.getConfig(), rollback, "config.json");
			backupExisting(legacyConfig, rollback, "config.xml");
			backupExisting(dstKeyLayout, rollback, "VirtualKeyboardLayout");
			configCommitStarted = true;
			FileUtils.copyFileUsingChannel(srcConfig, profile.getConfig());
			if (legacyConfig.exists()) {
				legacyCommitStarted = true;
				if (!legacyConfig.delete()) {
					throw new IOException("Unable to remove stale legacy profile configuration");
				}
			}
			if (includeKeyboard) {
				keyboardCommitStarted = true;
				FileUtils.copyFileUsingChannel(srcKeyLayout, dstKeyLayout);
			} else if (dstKeyLayout.exists()) {
				keyboardCommitStarted = true;
				if (!dstKeyLayout.delete()) {
					throw new IOException("Unable to remove stale key layout");
				}
			}
		} catch (IOException | RuntimeException failure) {
			if (configCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, profile.getConfig(), rollback, "config.json");
			}
			if (legacyCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, legacyConfig, rollback, "config.xml");
			}
			if (keyboardCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, dstKeyLayout, rollback,
						"VirtualKeyboardLayout");
			}
			if (rollbackSucceeded && !profileExisted) deleteRecursively(profile.getDir());
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			if (rollbackSucceeded) deleteRecursively(rollback);
		}
	}

	/**
	 * Commits a preset editor draft. An unreadable layout in an existing draft is left untouched on
	 * the real profile so editing settings cannot accidentally destroy an artifact the editor could
	 * not understand.
	 */
	static void saveEditedSnapshot(Profile profile, String fromPath) throws IOException {
		File srcConfig = new File(fromPath, Config.MIDLET_CONFIG_FILE);
		if (!isValidConfigFile(srcConfig)) {
			throw new IOException("Preset draft configuration is not loadable");
		}
		boolean profileExisted = profile.getDir().exists();
		profile.create();
		File srcKeyLayout = new File(fromPath, Config.MIDLET_KEY_LAYOUT_FILE);
		File config = profile.getConfig();
		File legacyConfig = new File(profile.getDir(), "config.xml");
		File keyLayout = profile.getKeyLayout();
		File rollback = new File(profile.getDir(), ".preset-save.rollback");
		boolean configCommitStarted = false;
		boolean legacyCommitStarted = false;
		boolean keyboardCommitStarted = false;
		boolean rollbackSucceeded = true;
		try {
			prepareRollbackDirectory(rollback);
			backupExisting(config, rollback, "config.json");
			backupExisting(legacyConfig, rollback, "config.xml");
			backupExisting(keyLayout, rollback, "VirtualKeyboardLayout");
			configCommitStarted = true;
			FileUtils.copyFileUsingChannel(srcConfig, config);
			if (legacyConfig.exists()) {
				legacyCommitStarted = true;
				if (!legacyConfig.delete()) {
					throw new IOException("Unable to remove stale legacy profile configuration");
				}
			}
			if (KeyboardLayoutValidator.validate(srcKeyLayout) == null) {
				keyboardCommitStarted = true;
				FileUtils.copyFileUsingChannel(srcKeyLayout, keyLayout);
			} else if (!srcKeyLayout.exists() && keyLayout.exists()) {
				keyboardCommitStarted = true;
				if (!keyLayout.delete()) {
					throw new IOException("Unable to remove deleted profile keyboard layout");
				}
			}
		} catch (IOException | RuntimeException failure) {
			if (configCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, config, rollback, "config.json");
			}
			if (legacyCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, legacyConfig, rollback, "config.xml");
			}
			if (keyboardCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, keyLayout, rollback,
						"VirtualKeyboardLayout");
			}
			if (rollbackSucceeded && !profileExisted) deleteRecursively(profile.getDir());
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			if (rollbackSucceeded) deleteRecursively(rollback);
		}
	}

	/** Saves only the separate layout artifact, converting an explicitly overwritten entry to layout-only. */
	static void saveLayoutSnapshot(Profile profile, String fromPath) throws IOException {
		File source = new File(fromPath, Config.MIDLET_KEY_LAYOUT_FILE);
		if (KeyboardLayoutValidator.validate(source) != null) {
			throw new IOException("Current keyboard layout is not loadable");
		}
		boolean profileExisted = profile.getDir().exists();
		profile.create();
		File config = profile.getConfig();
		File oldConfig = new File(profile.getDir(), "config.xml");
		File rollback = new File(profile.getDir(), ".preset-save.rollback");
		boolean configCommitStarted = false;
		boolean legacyCommitStarted = false;
		boolean keyboardCommitStarted = false;
		boolean rollbackSucceeded = true;
		try {
			prepareRollbackDirectory(rollback);
			backupExisting(config, rollback, "config.json");
			backupExisting(oldConfig, rollback, "config.xml");
			backupExisting(profile.getKeyLayout(), rollback, "VirtualKeyboardLayout");
			keyboardCommitStarted = true;
			FileUtils.copyFileUsingChannel(source, profile.getKeyLayout());
			if (config.exists()) {
				configCommitStarted = true;
				if (!config.delete()) {
					throw new IOException("Unable to remove stale profile configuration");
				}
			}
			if (oldConfig.exists()) {
				legacyCommitStarted = true;
				if (!oldConfig.delete()) {
					throw new IOException("Unable to remove stale legacy profile configuration");
				}
			}
		} catch (IOException | RuntimeException failure) {
			if (configCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, config, rollback, "config.json");
			}
			if (legacyCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, oldConfig, rollback, "config.xml");
			}
			if (keyboardCommitStarted) {
				rollbackSucceeded &= tryRestore(failure, profile.getKeyLayout(), rollback,
						"VirtualKeyboardLayout");
			}
			if (rollbackSucceeded && !profileExisted) deleteRecursively(profile.getDir());
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			if (rollbackSucceeded) deleteRecursively(rollback);
		}
	}

	/** Resolves a saved collection entry without requiring it to contain application settings. */
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
