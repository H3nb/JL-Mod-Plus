/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/** Read-only, first-bootstrap bridge from the legacy Room 2 `J2ME-apps.db`. */
class LegacyLibraryImporter {
    data class LegacyRow(
        val id: Long,
        val storageKey: String,
        val title: String?,
    )

    data class ReadResult(
        val rows: List<LegacyRow>,
        val failure: String? = null,
    )

    data class MergeResult(
        val apps: List<LibraryAppEntity>,
        val customTitlesImported: Int,
    )

    /**
     * Reads only the fields whose legacy meaning is useful to the new Library. Opening the old DB
     * read-only avoids schema migration, Room callbacks, or writes to user data during bootstrap.
     */
    fun read(emulatorDir: File): ReadResult {
        val databaseFile = File(emulatorDir, LEGACY_DATABASE_NAME)
        if (!databaseFile.isFile) {
            return ReadResult(emptyList())
        }

        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val rows = ArrayList<LegacyRow>()
            database.rawQuery(
                "SELECT id, path, title FROM apps ORDER BY id ASC",
                null,
            ).use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow("id")
                val pathColumn = cursor.getColumnIndexOrThrow("path")
                val titleColumn = cursor.getColumnIndexOrThrow("title")
                while (cursor.moveToNext()) {
                    if (cursor.isNull(pathColumn)) {
                        continue
                    }
                    rows += LegacyRow(
                        id = cursor.getLong(idColumn),
                        storageKey = cursor.getString(pathColumn),
                        title = if (cursor.isNull(titleColumn)) null else cursor.getString(titleColumn),
                    )
                }
            }
            ReadResult(rows)
        } catch (error: SQLiteException) {
            ReadResult(emptyList(), boundedFailure(error))
        } catch (error: IllegalArgumentException) {
            // Includes a legacy schema whose expected columns are missing. The old DB is optional;
            // preserve it untouched and let descriptor metadata bootstrap the new Library.
            ReadResult(emptyList(), boundedFailure(error))
        } finally {
            database?.close()
        }
    }

    /**
     * Maps legacy user presentation state onto descriptor-derived rows. Vendor/version/imagePath are
     * intentionally ignored because the old model exposed only title as mutable presentation data.
     */
    fun merge(scannedApps: List<LibraryAppEntity>, legacyRows: List<LegacyRow>): MergeResult {
        if (legacyRows.isEmpty()) {
            return MergeResult(scannedApps, 0)
        }
        val legacyByStorageKey = legacyRows.associateBy { it.storageKey }
        var imported = 0
        val merged = scannedApps.map { app ->
            val legacy = legacyByStorageKey[app.storageKey] ?: return@map app
            val customTitle = legacy.title?.takeUnless { it == app.sourceTitle }
            if (customTitle != null) {
                imported++
            }
            app.copy(customTitle = customTitle)
        }.sortedWith(
            compareBy<LibraryAppEntity> { legacyByStorageKey[it.storageKey]?.id ?: Long.MAX_VALUE }
                .thenBy { it.storageKey },
        )
        return MergeResult(merged, imported)
    }

    private fun boundedFailure(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        val value = if (detail.isEmpty()) error.javaClass.simpleName
        else "${error.javaClass.simpleName}: $detail"
        return value.take(MAX_FAILURE_LENGTH)
    }

    private companion object {
        const val LEGACY_DATABASE_NAME = "J2ME-apps.db"
        const val MAX_FAILURE_LENGTH = 512
    }
}
