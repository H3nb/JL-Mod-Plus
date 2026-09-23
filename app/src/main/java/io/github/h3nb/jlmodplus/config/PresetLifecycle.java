/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Preserves workdir-scoped preset references while one saved preset is renamed or deleted.
 *
 * <p>Rename publishes a byte-preserving copy before one durable reference rewrite. Delete clears
 * every matching reference before touching the source. MIDlet-local snapshots are never scanned
 * or rewritten here.</p>
 */
final class PresetLifecycle {
	static final String RENAME_STAGING_PREFIX = ".jlmod-preset-rename-";
	private static final String RENAME_STAGING_NAME = RENAME_STAGING_PREFIX + "pending";
	private static final int COPY_BUFFER_SIZE = 8192;

	enum Result {
		SUCCESS,
		CLEANUP_FAILED,
		FAILED
	}

	interface FileActions {
		boolean publish(@NonNull File staging, @NonNull File published);
		boolean deleteSource(@NonNull File source);
	}

	private static final FileActions DEFAULT_FILE_ACTIONS = new FileActions() {
		@Override
		public boolean publish(@NonNull File staging, @NonNull File published) {
			return staging.renameTo(published);
		}

		@Override
		public boolean deleteSource(@NonNull File source) {
			return deleteRecursively(source);
		}
	};

	private PresetLifecycle() {
	}

	static boolean isInternalRenameStagingName(@NonNull String name) {
		return name.startsWith(RENAME_STAGING_PREFIX);
	}

	@NonNull
	static Result rename(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String oldName,
			@NonNull String newName) {
		return rename(preferences, profilesRoot, oldName, newName, DEFAULT_FILE_ACTIONS);
	}

	@NonNull
	static Result rename(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String oldName,
			@NonNull String newName,
			@NonNull FileActions fileActions) {
		synchronized (ProfilesManager.presetSourceLock()) {
		if (oldName.equals(newName)) return Result.SUCCESS;
		if (!Profile.isValidName(newName) || isInternalRenameStagingName(oldName)) {
			return Result.FAILED;
		}

		File oldSource = new File(profilesRoot, oldName);
		File newSource = new File(profilesRoot, newName);
		File staging = new File(profilesRoot, RENAME_STAGING_NAME);
		try {
			ProfilesManager.recoverInterruptedPresetSave(oldSource);
		} catch (IOException | RuntimeException recoveryFailure) {
			return Result.FAILED;
		}
		final boolean newNameOccupied;
		try {
			newNameOccupied = ProfilesManager.profileNameExistsLocked(profilesRoot, newName);
		} catch (IOException | RuntimeException occupancyFailure) {
			return Result.FAILED;
		}
		if (!oldSource.isDirectory() || newNameOccupied) {
			return Result.FAILED;
		}
		if (staging.exists() && !deleteRecursively(staging)) {
			return Result.FAILED;
		}

		try {
			copyRecursively(oldSource, staging);
		} catch (IOException | RuntimeException copyFailure) {
			deleteRecursively(staging);
			return Result.FAILED;
		}

		try {
			if (!fileActions.publish(staging, newSource)) {
				deleteRecursively(staging);
				return Result.FAILED;
			}
		} catch (RuntimeException publicationFailure) {
			deleteRecursively(staging);
			return Result.FAILED;
		}

		ReferenceSnapshot references;
		try {
			references = captureReferences(preferences, profilesRoot, oldName);
			if (!rewriteForRename(preferences, references, newName)) {
				if (restoreReferences(preferences, references, false)) {
					deleteRecursively(newSource);
				}
				return Result.FAILED;
			}
		} catch (IOException | RuntimeException metadataFailure) {
			// NEW and OLD are both complete here. Keeping both is the conservative crash-safe state
			// when metadata durability cannot be established.
			return Result.FAILED;
		}
		ProfilesManager.invalidatePresetEditSessions(oldSource);

		try {
			return fileActions.deleteSource(oldSource)
					? Result.SUCCESS
					: Result.CLEANUP_FAILED;
		} catch (RuntimeException cleanupFailure) {
			return Result.CLEANUP_FAILED;
		}
		}
	}

	@NonNull
	static Result delete(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String name) {
		return delete(preferences, profilesRoot, name, DEFAULT_FILE_ACTIONS);
	}

	@NonNull
	static Result delete(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String name,
			@NonNull FileActions fileActions) {
		synchronized (ProfilesManager.presetSourceLock()) {
		File source = new File(profilesRoot, name);

		ReferenceSnapshot references;
		try {
			references = captureReferences(preferences, profilesRoot, name);
			if (!clearForDelete(preferences, references)) {
				restoreReferences(preferences, references, true);
				return Result.FAILED;
			}
		} catch (IOException | RuntimeException metadataFailure) {
			return Result.FAILED;
		}
		ProfilesManager.invalidatePresetEditSessions(source);

		if (!source.exists()) return Result.SUCCESS;
		try {
			return fileActions.deleteSource(source)
					? Result.SUCCESS
					: Result.CLEANUP_FAILED;
		} catch (RuntimeException cleanupFailure) {
			return Result.CLEANUP_FAILED;
		}
		}
	}

	@NonNull
	private static ReferenceSnapshot captureReferences(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String name) throws IOException {
		Map<String, ?> all = preferences.getAll();
		boolean defaultMatches = name.equals(all.get(PREF_DEFAULT_PROFILE));
		String originPrefix = PresetLinkage.originPreferencePrefix();
		File workdir = profilesRoot.getCanonicalFile().getParentFile();
		if (workdir == null) throw new IOException("Preset root has no workdir");
		File configRoot = new File(workdir, "configs").getCanonicalFile();
		List<OriginReference> origins = new ArrayList<>();
		for (Map.Entry<String, ?> entry : all.entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith(originPrefix) || !name.equals(entry.getValue())) {
				continue;
			}
			String path = key.substring(originPrefix.length());
			File configDir = new File(path);
			if (!configDir.isAbsolute() || configDir.getName().isEmpty()) continue;
			try {
				if (!configRoot.equals(configDir.getCanonicalFile().getParentFile())) continue;
			} catch (IOException | RuntimeException malformedKey) {
				continue;
			}
			String linkedKey = PresetLinkage.linkedPreferenceKeyForOriginKey(key);
			Object linkedValue = all.get(linkedKey);
			origins.add(new OriginReference(
					key,
					linkedKey,
					linkedValue instanceof Boolean,
					linkedValue instanceof Boolean && (Boolean) linkedValue));
		}
		return new ReferenceSnapshot(name, defaultMatches, origins);
	}

	private static boolean rewriteForRename(
			@NonNull SharedPreferences preferences,
			@NonNull ReferenceSnapshot references,
			@NonNull String newName) {
		SharedPreferences.Editor editor = preferences.edit();
		if (references.defaultMatches) {
			editor.putString(PREF_DEFAULT_PROFILE, newName);
		}
		for (OriginReference origin : references.origins) {
			editor.putString(origin.originKey, newName);
		}
		return editor.commit();
	}

	private static boolean clearForDelete(
			@NonNull SharedPreferences preferences,
			@NonNull ReferenceSnapshot references) {
		SharedPreferences.Editor editor = preferences.edit();
		if (references.defaultMatches) {
			editor.remove(PREF_DEFAULT_PROFILE);
		}
		for (OriginReference origin : references.origins) {
			editor.remove(origin.originKey);
			editor.remove(origin.linkedKey);
		}
		return editor.commit();
	}

	private static boolean restoreReferences(
			@NonNull SharedPreferences preferences,
			@NonNull ReferenceSnapshot references,
			boolean restoreLinkedMarkers) {
		try {
			SharedPreferences.Editor editor = preferences.edit();
			if (references.defaultMatches) {
				editor.putString(PREF_DEFAULT_PROFILE, references.name);
			}
			for (OriginReference origin : references.origins) {
				editor.putString(origin.originKey, references.name);
				if (restoreLinkedMarkers) {
					if (origin.linkedMarkerPresent) {
						editor.putBoolean(origin.linkedKey, origin.linkedMarkerValue);
					} else {
						editor.remove(origin.linkedKey);
					}
				}
			}
			return editor.commit();
		} catch (RuntimeException restoreFailure) {
			return false;
		}
	}

	private static void copyRecursively(@NonNull File source, @NonNull File destination)
			throws IOException {
		if (source.isDirectory()) {
			if (!destination.mkdir() && !destination.isDirectory()) {
				throw new IOException("Unable to create rename staging directory: " + destination);
			}
			File[] children = source.listFiles();
			if (children == null) {
				throw new IOException("Unable to enumerate preset source: " + source);
			}
			for (File child : children) {
				copyRecursively(child, new File(destination, child.getName()));
			}
			return;
		}
		if (!source.isFile()) {
			throw new IOException("Unsupported preset source entry: " + source);
		}

		try (FileInputStream input = new FileInputStream(source);
				FileOutputStream output = new FileOutputStream(destination)) {
			byte[] buffer = new byte[COPY_BUFFER_SIZE];
			int count;
			while ((count = input.read(buffer)) != -1) {
				output.write(buffer, 0, count);
			}
			output.flush();
			output.getFD().sync();
		}
	}

	private static boolean deleteRecursively(@NonNull File source) {
		if (!source.exists()) return true;
		if (source.isDirectory()) {
			File[] children = source.listFiles();
			if (children == null) return false;
			for (File child : children) {
				if (!deleteRecursively(child)) return false;
			}
		}
		return source.delete() || !source.exists();
	}

	private static final class ReferenceSnapshot {
		@NonNull final String name;
		final boolean defaultMatches;
		@NonNull final List<OriginReference> origins;

		ReferenceSnapshot(
				@NonNull String name,
				boolean defaultMatches,
				@NonNull List<OriginReference> origins) {
			this.name = name;
			this.defaultMatches = defaultMatches;
			this.origins = origins;
		}
	}

	private static final class OriginReference {
		@NonNull final String originKey;
		@NonNull final String linkedKey;
		final boolean linkedMarkerPresent;
		final boolean linkedMarkerValue;

		OriginReference(
				@NonNull String originKey,
				@NonNull String linkedKey,
				boolean linkedMarkerPresent,
				boolean linkedMarkerValue) {
			this.originKey = originKey;
			this.linkedKey = linkedKey;
			this.linkedMarkerPresent = linkedMarkerPresent;
			this.linkedMarkerValue = linkedMarkerValue;
		}
	}
}
