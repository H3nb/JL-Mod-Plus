/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;

import javax.microedition.util.ContextHolder;

/** Binds the current complete default to one proven-fresh installed storage identity. */
public final class FreshInstalledMidletInitializer {
	public enum Result {
		LINKED,
		CUSTOM,
		BUILT_IN,
		FAILED;

		public boolean isSuccess() {
			return this != FAILED;
		}
	}

	private FreshInstalledMidletInitializer() {
	}

	/**
	 * Materializes the current default before the installer publishes a new Library identity.
	 *
	 * <p>The caller must already hold the installer execution permit and generation lease, and must
	 * have proved that {@code configDir} does not exist. Reinstalls must not call this method.</p>
	 */
	@NonNull
	public static Result initialize(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull File configDir,
			boolean darkTheme) {
		return initialize(preferences, profilesRoot, configDir, darkTheme,
				ContextHolder.getAssetAsString("defaults/system.props"));
	}

	@NonNull
	static Result initialize(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull File configDir,
			boolean darkTheme,
			@NonNull String defaultSystemProperties) {
		synchronized (ProfilesManager.presetSourceLock()) {
			if (configDir.exists()) return Result.FAILED;
			try {

				String defaultName = preferences.getString(PREF_DEFAULT_PROFILE, null);
				if (Profile.isValidName(defaultName)) {
					File sourceDir = new File(profilesRoot, defaultName);
					if (sourceDir.isDirectory()
							&& ProfilesManager.isCompleteSnapshotReady(sourceDir)) {
						LinkedPresetActivation.Result activation = LinkedPresetActivation.activate(
								preferences, configDir, sourceDir, defaultName);
						if (activation == LinkedPresetActivation.Result.LINKED) {
							return Result.LINKED;
						}
						if (activation == LinkedPresetActivation.Result.APPLIED_CUSTOM) {
							return Result.CUSTOM;
						}
						if (activation == LinkedPresetActivation.Result.FAILED_UNSAFE) {
							discardFreshInitialization(preferences, configDir);
							return Result.FAILED;
						}
					}
				}

				if (publishBuiltInLocked(preferences, configDir, darkTheme,
						defaultSystemProperties)) {
					return Result.BUILT_IN;
				}
				discardFreshInitialization(preferences, configDir);
				return Result.FAILED;
			} catch (RuntimeException failure) {
				discardFreshInitialization(preferences, configDir);
				return Result.FAILED;
			}
		}
	}

	/** Publishes safe Built-in recovery without consulting today's named default. */
	static boolean publishBuiltIn(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir,
			boolean darkTheme) {
		synchronized (ProfilesManager.presetSourceLock()) {
			return publishBuiltInLocked(preferences, configDir, darkTheme,
					ContextHolder.getAssetAsString("defaults/system.props"));
		}
	}

	/** Discards only a fresh initialization owned by the current failed installer operation. */
	public static boolean discardFreshInitialization(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		synchronized (ProfilesManager.presetSourceLock()) {
			try {
				if (!ProfilesManager.clearMidletOwnershipMetadata(preferences, configDir)) {
					return false;
				}
				return deleteRecursively(configDir);
			} catch (RuntimeException cleanupFailure) {
				return false;
			}
		}
	}

	private static boolean publishBuiltInLocked(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir,
			boolean darkTheme,
			@NonNull String defaultSystemProperties) {
		PresetSourceReplacement.Guard ownership =
				PresetSourceReplacement.begin(preferences, configDir);
		if (!ownership.canWrite()) return false;
		if (!ProfilesManager.removeLocalKeyboardLayout(configDir)) return false;
		if (!ProfilesManager.saveConfig(ProfileModel.createBuiltIn(
				configDir, darkTheme, defaultSystemProperties))) {
			return false;
		}
		return ownership.publishBuiltInOwnership();
	}

	private static boolean deleteRecursively(@NonNull File file) {
		if (!file.exists()) return true;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) return false;
			for (File child : children) {
				if (!deleteRecursively(child)) return false;
			}
		}
		return file.delete();
	}
}
