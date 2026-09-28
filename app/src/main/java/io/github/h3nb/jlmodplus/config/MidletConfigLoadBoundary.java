/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;

/**
 * Prepares one active MIDlet's materialized config snapshot before any local config/layout read.
 *
 * <p>Exact-sync recovery is always attempted first, independent of named-preset linkage. A linked
 * source failure is recoverable when the local snapshot can be proven safe again; local recovery
 * failure is not.</p>
 */
public final class MidletConfigLoadBoundary {
	private static final Logger LOGGER = Logger.getLogger(MidletConfigLoadBoundary.class.getName());

	private MidletConfigLoadBoundary() {
	}

	public static boolean prepare(@NonNull SharedPreferences preferences,
			@NonNull File configDir, @NonNull File profilesRoot) {
		synchronized (ProfilesManager.presetSourceLock()) {
		try {
			ProfilesManager.recoverInterruptedSnapshotSync(configDir);
		} catch (IOException | RuntimeException recoveryFailure) {
			LOGGER.log(Level.SEVERE, "Unable to recover MIDlet preset snapshot before load", recoveryFailure);
			return false;
		}

		PresetLinkage linkage = new PresetLinkage(preferences, configDir);
		if (!normalizeLegacyBuiltIn(preferences, configDir, linkage)) {
			return false;
		}
		if (!linkage.isLinked()) {
			return true;
		}

		String origin = linkage.getOrigin();
		File sourceDir = resolveProfileDirectory(profilesRoot, origin);
		if (sourceDir == null) {
			LOGGER.warning("Linked preset is unavailable: " + origin);
			return true;
		}

		try {
			ProfilesManager.syncSnapshot(sourceDir, configDir);
			return true;
		} catch (IOException | RuntimeException syncFailure) {
			try {
				ProfilesManager.recoverInterruptedSnapshotSync(configDir);
			} catch (IOException | RuntimeException recoveryFailure) {
				syncFailure.addSuppressed(recoveryFailure);
				LOGGER.log(Level.SEVERE, "Linked preset sync left an unsafe local snapshot: " + origin, syncFailure);
				return false;
			}
			LOGGER.log(Level.WARNING,
					"Unable to refresh linked preset; using last-known-good local snapshot: " + origin,
					syncFailure);
			return true;
		}
		}
	}

	private static boolean normalizeLegacyBuiltIn(@NonNull SharedPreferences preferences,
			@NonNull File configDir, @NonNull PresetLinkage linkage) {
		String builtInKey = ProfileModel.builtInThemePreferenceKey(configDir);
		if (!preferences.getBoolean(builtInKey, false)) return true;
		// A named origin is a separate owner; conflicting Built-in metadata cannot redefine it.
		if (linkage.getOrigin() != null) {
			return preferences.edit().remove(builtInKey).commit();
		}
		// Keep the marker as evidence until any version-sensitive config migration is durable.
		ProfileModel config = ProfilesManager.loadBuiltInConfigForNormalization(configDir);
		if (config == null) return false;
		// An existing layout is user data, including a layout awaiting recovery from .bak.
		if (ProfilesManager.hasRecoverableLocalKeyboardLayout(configDir)) {
			return preferences.edit().remove(builtInKey).commit();
		}
		if (config.vkType == VirtualKeyboard.TYPE_NUMBERS_ARROWS) return true;
		if (config.vkType != VirtualKeyboard.TYPE_CUSTOM) {
			return preferences.edit().remove(builtInKey).commit();
		}
		config.vkType = VirtualKeyboard.TYPE_NUMBERS_ARROWS;
		return ProfilesManager.saveConfig(config);
	}

	@Nullable
	private static File resolveProfileDirectory(@NonNull File profilesRoot, @Nullable String origin) {
		if (origin == null) {
			return null;
		}
		File[] entries = profilesRoot.listFiles();
		if (entries == null) {
			return null;
		}
		for (File entry : entries) {
			if (entry.isDirectory() && origin.equals(entry.getName())) {
				return entry;
			}
		}
		return null;
	}
}
