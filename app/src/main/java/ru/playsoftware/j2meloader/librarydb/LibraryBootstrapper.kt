/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Creates the initial indexed catalog for one workdir without ever mutating converted/config/save
 * data. An existing READY database is authoritative and is never rebuilt from the filesystem here.
 */
class LibraryBootstrapper(
    private val scanner: LibraryScanner = LibraryScanner(),
    private val legacyImporter: LegacyLibraryImporter = LegacyLibraryImporter(),
) {
    data class Progress(
        val completed: Int,
        val total: Int,
        val storageKey: String,
    )

    data class Result(
        val alreadyReady: Boolean,
        val indexedCount: Int,
        val failures: List<LibraryScanner.Failure>,
        val legacyTitlesImported: Int = 0,
        val legacyImportFailure: String? = null,
    )

    suspend fun ensureReady(
        database: LibraryDatabase,
        emulatorDir: File,
        onProgress: ((Progress) -> Unit)? = null,
    ): Result {
        val dao = database.libraryDao()
        when (val bootstrapState = dao.getLibraryState()?.bootstrapState) {
            LibraryBootstrapState.READY -> {
                return Result(alreadyReady = true, indexedCount = 0, failures = emptyList())
            }
            LibraryBootstrapState.CREATING,
            LibraryBootstrapState.INDEXING,
            null -> {
                // A fresh/incomplete bootstrap cannot contain durable Library-owned rows: the
                // initial catalog and READY transition are published together in one transaction.
                // If any persistent data exists here, this is an established/corrupt catalog whose
                // state marker is inconsistent. Fail closed instead of clearing user-owned data.
                check(!dao.hasPersistentLibraryData()) {
                    "Library bootstrap state is incomplete or missing while persistent Library data exists"
                }
            }
            else -> error("Unsupported Library bootstrap state: $bootstrapState")
        }

        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.INDEXING))

        val scan = withContext(Dispatchers.IO) {
            val scanContext = currentCoroutineContext()
            scanner.scan(emulatorDir) { completed, total, storageKey ->
                // latest-wins workdir switching can cancel an obsolete bootstrap between apps.
                scanContext.ensureActive()
                onProgress?.invoke(Progress(completed, total, storageKey))
            }
        }
        currentCoroutineContext().ensureActive()

        val legacyRead = withContext(Dispatchers.IO) {
            legacyImporter.read(emulatorDir)
        }
        currentCoroutineContext().ensureActive()
        val merged = legacyImporter.merge(scan.apps, legacyRead.rows)

        // The scanner and optional legacy read run outside a long-lived transaction. Only the final
        // lightweight catalog replacement and READY transition are atomic, so a killed/failed or
        // cancelled bootstrap remains visibly incomplete and can safely retry on the next launch.
        dao.replaceIncompleteCatalog(merged.apps)

        return Result(
            alreadyReady = false,
            indexedCount = merged.apps.size,
            failures = scan.failures,
            legacyTitlesImported = merged.customTitlesImported,
            legacyImportFailure = legacyRead.failure,
        )
    }
}
