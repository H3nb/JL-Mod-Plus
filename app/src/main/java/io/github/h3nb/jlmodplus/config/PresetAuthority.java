/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import io.github.h3nb.jlmodplus.installer.InstallerExecutionCoordinator;

/**
 * Main-process authority for runtime preset ownership and local preset materialization.
 *
 * <p>The IPC layer is intentionally absent here so host tests can exercise identity, lock ordering,
 * ownership, and publication semantics without Android Binder machinery.</p>
 */
final class PresetAuthority {
	interface IdentityResolver {
		@Nullable InstalledApp resolve(@NonNull String appPath) throws IOException;
	}

	static final class InstalledPath {
		@NonNull final File workDir;
		@NonNull final String storageKey;

		InstalledPath(@NonNull File workDir, @NonNull String storageKey) {
			this.workDir = workDir;
			this.storageKey = storageKey;
		}

		@Nullable
		static InstalledPath parse(@NonNull String rawPath) throws IOException {
			String value = rawPath.trim();
			if (value.isEmpty()) return null;
			final File requested;
			if (value.regionMatches(true, 0, "file:", 0, 5)) {
				try {
					URI uri = URI.create(value);
					if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
					requested = new File(uri);
				} catch (IllegalArgumentException invalidUri) {
					return null;
				}
			} else {
				// Runtime authority accepts filesystem identity only. Other URI schemes must never
				// become arbitrary relative paths under the provider process.
				if (value.contains("://")) return null;
				requested = new File(value);
			}
			File appDir = requested.getCanonicalFile();
			File converted = appDir.getParentFile();
			if (converted == null || !"converted".equals(converted.getName())) return null;
			File workDir = converted.getParentFile();
			String storageKey = appDir.getName();
			if (workDir == null || storageKey.isEmpty() || ".".equals(storageKey)
					|| "..".equals(storageKey) || !workDir.isDirectory()) {
				return null;
			}
			return new InstalledPath(workDir.getCanonicalFile(), storageKey);
		}
	}

	static final class InstalledApp {
		@NonNull final File workDir;
		@NonNull final String storageKey;
		final long appId;

		InstalledApp(@NonNull File workDir, @NonNull String storageKey, long appId) {
			this.workDir = workDir;
			this.storageKey = storageKey;
			this.appId = appId;
		}

		@NonNull File configDir() {
			return new File(new File(workDir, "configs"), storageKey);
		}

		@NonNull File profilesRoot() {
			return new File(workDir, "templates");
		}
	}

	static final class PrepareResult {
		final int code;
		final long appId;
		final boolean builtInThemeLinked;

		PrepareResult(int code, long appId, boolean builtInThemeLinked) {
			this.code = code;
			this.appId = appId;
			this.builtInThemeLinked = builtInThemeLinked;
		}
	}

	static final class TargetResult {
		final int code;
		@Nullable final String name;

		TargetResult(int code, @Nullable String name) {
			this.code = code;
			this.name = name;
		}
	}

	static final class SaveResult {
		final int code;
		final int updateOutcome;

		SaveResult(int code, int updateOutcome) {
			this.code = code;
			this.updateOutcome = updateOutcome;
		}
	}

	@NonNull private final SharedPreferences preferences;
	@NonNull private final IdentityResolver identities;

	PresetAuthority(@NonNull SharedPreferences preferences, @NonNull IdentityResolver identities) {
		this.preferences = preferences;
		this.identities = identities;
	}

	@NonNull
	static PresetAuthority create(@NonNull Context context) {
		Context app = context.getApplicationContext();
		return new PresetAuthority(
				PreferenceManager.getDefaultSharedPreferences(app),
				new InstalledIdentityResolver(app));
	}

	@NonNull
	PrepareResult prepareRuntime(@NonNull String appPath, long expectedAppId) {
		if (expectedAppId < 0L) return new PrepareResult(PresetAuthorityContract.RESULT_INVALID, 0L, false);
		try (InstallerExecutionCoordinator.Permit ignored = InstallerExecutionCoordinator.acquire()) {
			InstalledApp app = resolveCurrent(appPath);
			int identity = validateExpected(app, expectedAppId, true);
			if (identity != PresetAuthorityContract.RESULT_OK) {
				return new PrepareResult(identity, 0L, false);
			}
			synchronized (ProfilesManager.presetSourceLock()) {
				File configDir = app.configDir();
				if (!MidletConfigLoadBoundary.prepare(preferences, configDir, app.profilesRoot())) {
					return new PrepareResult(PresetAuthorityContract.RESULT_FAILED, app.appId, false);
				}
				ProfilesManager.prepareRuntimeLayout(configDir);
				boolean builtIn = preferences.getBoolean(
						ProfileModel.builtInThemePreferenceKey(configDir), false);
				ProfileModel prepared = ProfilesManager.loadConfig(
						configDir, true,
						ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG, builtIn);
				if (prepared == null) {
					return new PrepareResult(PresetAuthorityContract.RESULT_FAILED, app.appId, builtIn);
				}
				return new PrepareResult(PresetAuthorityContract.RESULT_OK, app.appId, builtIn);
			}
		} catch (IOException | RuntimeException failure) {
			return new PrepareResult(PresetAuthorityContract.RESULT_FAILED, 0L, false);
		}
	}

	@NonNull
	TargetResult resolveUpdateTarget(@NonNull String appPath, long expectedAppId) {
		if (expectedAppId <= 0L) return new TargetResult(PresetAuthorityContract.RESULT_INVALID, null);
		try {
			InstalledApp app = resolveCurrent(appPath);
			int identity = validateExpected(app, expectedAppId, false);
			if (identity != PresetAuthorityContract.RESULT_OK) return new TargetResult(identity, null);
			String target = PresetRuntimeUpdate.resolveUpdateTarget(
					preferences, app.configDir(), app.profilesRoot());
			return new TargetResult(PresetAuthorityContract.RESULT_OK, target);
		} catch (IOException | RuntimeException failure) {
			return new TargetResult(PresetAuthorityContract.RESULT_FAILED, null);
		}
	}

	@NonNull
	SaveResult saveVirtualKeyboardLayout(
			@NonNull String appPath,
			long expectedAppId,
			@NonNull byte[] layoutPayload,
			@Nullable String requestedPresetName) {
		if (expectedAppId <= 0L || layoutPayload.length == 0
				|| layoutPayload.length > PresetAuthorityContract.MAX_LAYOUT_PAYLOAD_BYTES
				|| requestedPresetName != null && !Profile.isValidName(requestedPresetName)) {
			return new SaveResult(PresetAuthorityContract.RESULT_INVALID, PresetAuthorityContract.UPDATE_NONE);
		}
		try (InstallerExecutionCoordinator.Permit ignored = InstallerExecutionCoordinator.acquire()) {
			InstalledApp app = resolveCurrent(appPath);
			int identity = validateExpected(app, expectedAppId, false);
			if (identity != PresetAuthorityContract.RESULT_OK) {
				return new SaveResult(identity, PresetAuthorityContract.UPDATE_NONE);
			}
			if (KeyboardLayoutValidator.validateBytes(layoutPayload) != null) {
				return new SaveResult(
						PresetAuthorityContract.RESULT_INVALID,
						PresetAuthorityContract.UPDATE_NONE);
			}
			synchronized (ProfilesManager.presetSourceLock()) {
				PresetLocalOverride.Guard ownership =
						PresetLocalOverride.detachBeforeWrite(preferences, app.configDir());
				if (!ownership.canWrite()) {
					return new SaveResult(PresetAuthorityContract.RESULT_FAILED, PresetAuthorityContract.UPDATE_NONE);
				}
				try {
					ProfilesManager.publishRuntimeLayout(app.configDir(), layoutPayload);
				} catch (IOException | RuntimeException publicationFailure) {
					boolean localStateRecovered = false;
					try {
						ProfilesManager.recoverInterruptedSnapshotSync(app.configDir());
						localStateRecovered = true;
					} catch (IOException | RuntimeException recoveryFailure) {
						publicationFailure.addSuppressed(recoveryFailure);
					}
					if (localStateRecovered) {
						ownership.restoreIfUnchanged();
					}
					return new SaveResult(
							PresetAuthorityContract.RESULT_FAILED,
							PresetAuthorityContract.UPDATE_NONE);
				}

				int update = PresetAuthorityContract.UPDATE_NONE;
				if (requestedPresetName != null) {
					update = switch (PresetRuntimeUpdate.updateExisting(
							preferences, app.configDir(), app.profilesRoot(), requestedPresetName)) {
						case LINKED -> PresetAuthorityContract.UPDATE_LINKED;
						case SAVED_UNLINKED -> PresetAuthorityContract.UPDATE_SAVED_UNLINKED;
						case FAILED -> PresetAuthorityContract.UPDATE_FAILED;
					};
				}
				return new SaveResult(PresetAuthorityContract.RESULT_OK, update);
			}
		} catch (IOException | RuntimeException failure) {
			return new SaveResult(PresetAuthorityContract.RESULT_FAILED, PresetAuthorityContract.UPDATE_NONE);
		}
	}

	@Nullable
	private InstalledApp resolveCurrent(@NonNull String appPath) throws IOException {
		return identities.resolve(appPath);
	}

	private static int validateExpected(
			@Nullable InstalledApp app, long expectedAppId, boolean allowUnknown) {
		if (app == null || app.appId <= 0L) return PresetAuthorityContract.RESULT_STALE;
		if (expectedAppId == 0L) {
			return allowUnknown ? PresetAuthorityContract.RESULT_OK : PresetAuthorityContract.RESULT_INVALID;
		}
		return app.appId == expectedAppId
				? PresetAuthorityContract.RESULT_OK
				: PresetAuthorityContract.RESULT_STALE;
	}
}
