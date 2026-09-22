/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity;
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase;

/** Resolves one converted-app path against the workdir-scoped durable Library catalog. */
final class InstalledIdentityResolver implements PresetAuthority.IdentityResolver {
	@NonNull private final Context context;

	InstalledIdentityResolver(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@Nullable
	@Override
	public PresetAuthority.InstalledApp resolve(@NonNull String appPath) throws IOException {
		PresetAuthority.InstalledPath path = PresetAuthority.InstalledPath.parse(appPath);
		if (path == null) return null;

		File databaseFile = new File(path.workDir, LibraryDatabase.FILE_NAME);
		if (!databaseFile.isFile()) return null;

		LibraryDatabase database = LibraryDatabase.Companion.open(context, path.workDir);
		try {
			LibraryAppEntity row = database.libraryDao().getAppByStorageKeyNow(path.storageKey);
			if (row == null || !path.storageKey.equals(row.getStorageKey())) return null;
			return new PresetAuthority.InstalledApp(path.workDir, path.storageKey, row.getId());
		} finally {
			database.close();
		}
	}
}
