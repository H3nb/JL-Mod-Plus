/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import android.content.Context;

import androidx.annotation.NonNull;

import io.github.h3nb.jlmodplus.installer.InstallerExecutionCoordinator;

/**
 * Fences one installed-app write with the durable Library identity that opened the editor.
 *
 * <p>The fixed order is installer permit, current identity resolution, then the caller's logical
 * write. Callers may acquire {@link ProfilesManager#presetSourceLock()} only inside the supplied
 * operation.</p>
 */
public final class InstalledAppWriteGuard {
	public enum Result {
		SUCCESS,
		STALE,
		FAILED
	}

	@FunctionalInterface
	public interface WriteOperation {
		boolean run() throws Exception;
	}

	@FunctionalInterface
	interface PermitFactory {
		AutoCloseable acquire() throws Exception;
	}

	@FunctionalInterface
	interface IdentityLookup {
		long resolveAppId(@NonNull String appPath) throws Exception;
	}

	@NonNull private final PermitFactory permits;
	@NonNull private final IdentityLookup identities;

	InstalledAppWriteGuard(@NonNull PermitFactory permits,
			@NonNull IdentityLookup identities) {
		this.permits = permits;
		this.identities = identities;
	}

	@NonNull
	public static InstalledAppWriteGuard create(@NonNull Context context) {
		InstalledIdentityResolver resolver = new InstalledIdentityResolver();
		return new InstalledAppWriteGuard(
				InstallerExecutionCoordinator::acquire,
				appPath -> {
					PresetAuthority.InstalledApp app = resolver.resolve(appPath);
					return app == null ? 0L : app.appId;
				});
	}

	/** Resolves the identity captured by a newly opened editor while install/delete is excluded. */
	public long resolveCurrentAppId(@NonNull String appPath) {
		try (AutoCloseable ignored = permits.acquire()) {
			long appId = identities.resolveAppId(appPath);
			return appId > 0L ? appId : 0L;
		} catch (Exception failure) {
			return 0L;
		}
	}

	@NonNull
	public Result run(@NonNull String appPath, long expectedAppId,
			@NonNull WriteOperation operation) {
		if (expectedAppId <= 0L) return Result.STALE;
		try (AutoCloseable ignored = permits.acquire()) {
			long currentAppId = identities.resolveAppId(appPath);
			if (currentAppId <= 0L || currentAppId != expectedAppId) return Result.STALE;
			return operation.run() ? Result.SUCCESS : Result.FAILED;
		} catch (Exception failure) {
			return Result.FAILED;
		}
	}

}
