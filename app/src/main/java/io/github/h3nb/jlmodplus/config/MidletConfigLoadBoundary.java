/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Prepares one active MIDlet's materialized config snapshot before any local config/layout read.
 *
 * <p>Exact-sync recovery is always attempted first, independent of named-preset linkage. A linked
 * source failure is recoverable when the local snapshot can be proven safe again; local recovery
 * failure is not.</p>
 */
public final class MidletConfigLoadBoundary {
	private static final String TAG = MidletConfigLoadBoundary.class.getSimpleName();

	private MidletConfigLoadBoundary() {
	}

	public static boolean prepare(@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		return prepare(preferences, configDir, new File(Config.getProfilesDir()));
	}

	static boolean prepare(@NonNull SharedPreferences preferences,
			@NonNull File configDir, @NonNull File profilesRoot) {
		try {
			ProfilesManager.recoverInterruptedSnapshotSync(configDir);
		} catch (IOException | RuntimeException recoveryFailure) {
			Log.e(TAG, "Unable to recover MIDlet preset snapshot before load", recoveryFailure);
			return false;
		}

		PresetLinkage linkage = new PresetLinkage(preferences, configDir);
		if (!linkage.isLinked()) {
			return true;
		}

		String origin = linkage.getOrigin();
		File sourceDir = resolveProfileDirectory(profilesRoot, origin);
		if (sourceDir == null) {
			Log.w(TAG, "Linked preset is unavailable: " + origin);
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
				Log.e(TAG, "Linked preset sync left an unsafe local snapshot: " + origin, syncFailure);
				return false;
			}
			Log.e(TAG, "Unable to refresh linked preset; using last-known-good local snapshot: "
					+ origin, syncFailure);
			return true;
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
