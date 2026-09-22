/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/**
 * Coordinates one whole-device preset source save with the current MIDlet ownership transition.
 *
 * <p>Source publication commits first. The current local snapshot is never rolled back by this
 * coordinator, and other followers remain lazy.</p>
 */
final class PresetSourceSave {
	enum Result {
		LINKED,
		SAVED_UNLINKED,
		FAILED
	}

	private PresetSourceSave() {
	}

	@NonNull
	static Result saveAsNew(
			@NonNull SharedPreferences preferences,
			@NonNull File currentConfigDir,
			@NonNull File profilesRoot,
			@NonNull String rawName) {
		String name = rawName.trim();
		synchronized (ProfilesManager.presetSourceLock()) {
			try {
				ProfilesManager.saveNewCompleteSnapshot(profilesRoot, name, currentConfigDir);
			} catch (IOException | RuntimeException sourceFailure) {
				return Result.FAILED;
			}
			return relinkCommittedSource(preferences, currentConfigDir, name);
		}
	}

	@NonNull
	static Result updateExisting(
			@NonNull SharedPreferences preferences,
			@NonNull File currentConfigDir,
			@NonNull File profilesRoot,
			@NonNull String name) {
		synchronized (ProfilesManager.presetSourceLock()) {
			try {
				ProfilesManager.updateCompleteSnapshot(new File(profilesRoot, name), currentConfigDir);
			} catch (IOException | RuntimeException sourceFailure) {
				return Result.FAILED;
			}
			return relinkCommittedSource(preferences, currentConfigDir, name);
		}
	}

	@NonNull
	private static Result relinkCommittedSource(
			@NonNull SharedPreferences preferences,
			@NonNull File currentConfigDir,
			@NonNull String name) {
		PresetSourceReplacement.Guard ownership =
				PresetSourceReplacement.begin(preferences, currentConfigDir);
		if (!ownership.canWrite()) {
			return Result.SAVED_UNLINKED;
		}

		PresetLinkage linkage = new PresetLinkage(preferences, currentConfigDir);
		if (linkage.linkTo(name)) {
			return Result.LINKED;
		}

		// Publication is already committed. Never revive the previous owner; force toward CUSTOM
		// while retaining the new source name as best-effort provenance.
		linkage.setOrigin(name);
		if (linkage.isLinked()) {
			linkage.detach();
		}
		return Result.SAVED_UNLINKED;
	}
}
