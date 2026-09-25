/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase;

/** Resolves one converted-app path against the workdir-scoped durable Library catalog. */
final class InstalledIdentityResolver implements PresetAuthority.IdentityResolver {
	@Nullable
	@Override
	public PresetAuthority.InstalledApp resolve(@NonNull String appPath) throws IOException {
		PresetAuthority.InstalledPath path = PresetAuthority.InstalledPath.parse(appPath);
		if (path == null) return null;

		File databaseFile = new File(path.workDir, LibraryDatabase.FILE_NAME);
		if (!databaseFile.isFile()) return null;

		try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
				databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
				Cursor cursor = database.query(
						"apps",
						new String[]{"id", "storage_key"},
						"storage_key = ?",
						new String[]{path.storageKey},
						null, null, null, "1")) {
			if (!cursor.moveToFirst()) return null;
			long appId = cursor.getLong(0);
			String storageKey = cursor.getString(1);
			if (appId <= 0L || !path.storageKey.equals(storageKey)) return null;
			return new PresetAuthority.InstalledApp(path.workDir, path.storageKey, appId);
		}
	}
}
