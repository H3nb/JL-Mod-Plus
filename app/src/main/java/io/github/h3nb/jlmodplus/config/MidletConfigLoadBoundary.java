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
			@NonNull File configDir) {
		return prepare(preferences, configDir, new File(Config.getProfilesDir()));
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
