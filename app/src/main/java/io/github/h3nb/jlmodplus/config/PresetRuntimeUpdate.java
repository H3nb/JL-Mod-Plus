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

/**
 * Narrow runtime bridge for explicit whole-device preset updates.
 *
 * <p>Runtime code never reads linkage preference keys or writes a preset source directly.</p>
 */
public final class PresetRuntimeUpdate {
	public enum Result {
		LINKED,
		SAVED_UNLINKED,
		FAILED
	}

	private PresetRuntimeUpdate() {
	}

	@Nullable
	static String resolveUpdateTarget(
			@NonNull SharedPreferences preferences,
			@NonNull File currentConfigDir,
			@NonNull File profilesRoot) {
		synchronized (ProfilesManager.presetSourceLock()) {
			String origin = new PresetLinkage(preferences, currentConfigDir).getOrigin();
			if (origin == null || !Profile.isValidName(origin)) {
				return null;
			}
			File sourceDir = new File(profilesRoot, origin);
			if (!sourceDir.isDirectory()) {
				return null;
			}
			try {
				// Recovery safety, not config/layout equality or parseability, decides eligibility.
				ProfilesManager.recoverInterruptedPresetSave(sourceDir);
			} catch (IOException | RuntimeException recoveryFailure) {
				return null;
			}
			return sourceDir.isDirectory() ? origin : null;
		}
	}

	@NonNull
	static Result updateExisting(
			@NonNull SharedPreferences preferences,
			@NonNull File currentConfigDir,
			@NonNull File profilesRoot,
			@NonNull String name) {
		synchronized (ProfilesManager.presetSourceLock()) {
			if (!Profile.isValidName(name)) {
				return Result.FAILED;
			}
			// The dialog target is only a hint. Re-read provenance under the source lock so a
			// rename/delete that happened while the dialog was open cannot relink a stale name.
			String currentOrigin = new PresetLinkage(preferences, currentConfigDir).getOrigin();
			if (!name.equals(currentOrigin)) {
				return Result.FAILED;
			}
			// Task 3C remains the single authoritative whole-device source replacement path.
			return switch (PresetSourceSave.updateExisting(
					preferences, currentConfigDir, profilesRoot, name)) {
				case LINKED -> Result.LINKED;
				case SAVED_UNLINKED -> Result.SAVED_UNLINKED;
				case FAILED -> Result.FAILED;
			};
		}
	}

}
