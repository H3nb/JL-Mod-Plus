/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/**
 * Replaces one active MIDlet snapshot with one complete named preset and publishes live linkage.
 *
 * <p>The enum describes only the outcome of this operation. Persistent ownership remains solely
 * in {@link PresetLinkage}.</p>
 */
final class LinkedPresetActivation {
	enum Result {
		LINKED,
		APPLIED_CUSTOM,
		FAILED_SAFE,
		FAILED_UNSAFE
	}

	private LinkedPresetActivation() {
	}

	@NonNull
	static Result activate(
			@NonNull SharedPreferences preferences,
			@NonNull File targetConfigDir,
			@NonNull File sourcePresetDir,
			@NonNull String sourcePresetName) {
		synchronized (ProfilesManager.presetSourceLock()) {
		PresetSourceReplacement.Guard previous =
				PresetSourceReplacement.begin(preferences, targetConfigDir);
		if (!previous.canWrite()) {
			return Result.FAILED_SAFE;
		}

		try {
			ProfilesManager.syncSnapshot(sourcePresetDir, targetConfigDir);
		} catch (IOException | RuntimeException syncFailure) {
			try {
				ProfilesManager.recoverInterruptedSnapshotSync(targetConfigDir);
			} catch (IOException | RuntimeException recoveryFailure) {
				syncFailure.addSuppressed(recoveryFailure);
				return Result.FAILED_UNSAFE;
			}
			previous.restoreIfUnchanged();
			return Result.FAILED_SAFE;
		}

		PresetLinkage linkage = new PresetLinkage(preferences, targetConfigDir);
		if (linkage.linkTo(sourcePresetName)) {
			return Result.LINKED;
		}

		// The new snapshot is already authoritative on disk. The old association must never return.
		// clearBeforeReplacement() was durable before publication, so a failed link commit leaves
		// CUSTOM as the last known durable ownership state. setOrigin() also removes any process-
		// visible linked marker that a failed SharedPreferences commit may have exposed in memory.
		linkage.setOrigin(sourcePresetName);
		if (linkage.isLinked()) {
			linkage.detach();
		}
		return Result.APPLIED_CUSTOM;
		}
	}
}
