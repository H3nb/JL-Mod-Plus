/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One terminal MIDlet session ready to be routed to one workdir-scoped Library database. */
data class LibraryPlayStatRecord(
    val sessionId: String,
    val workdirLocator: String,
    val storageKey: String,
    val reachedRunning: Boolean,
    val firstRunningWallTimeMillis: Long?,
    val accumulatedActiveMillis: Long,
)

/**
 * Imports terminal session stats into one READY workdir without redirecting records across roots.
 *
 * The Room receipt transaction remains the exactly-once authority. Filesystem probing is used only
 * to decide whether a missing app row should be re-indexed before that receipt is allowed to commit.
 */
class LibraryPlayStatReconciler(
    private val scanner: LibraryScanner = LibraryScanner(),
) {
    data class Result(
        val reconciledSessionIds: List<String>,
        val pendingSessionIds: List<String>,
    )

    suspend fun reconcile(
        dao: LibraryDao,
        emulatorDir: File,
        records: List<LibraryPlayStatRecord>,
    ): Result {
        check(dao.getLibraryState()?.bootstrapState == LibraryBootstrapState.READY) {
            "Play-stat reconciliation requires a READY Library database"
        }
        val activeRoot = normalizeWorkdir(emulatorDir)
        val relevant = records.filter { record ->
            normalizeWorkdir(File(record.workdirLocator)) == activeRoot
        }
        if (relevant.isEmpty()) return Result(emptyList(), emptyList())

        // Take one cheap converted-directory snapshot for this pass. Targeted descriptor scanning is
        // only needed when a valid installed directory exists but its Room row is unexpectedly absent.
        val installedKeys = withContext(Dispatchers.IO) { scanner.storageKeys(activeRoot) }
        val reconciled = ArrayList<String>(relevant.size)
        val pending = ArrayList<String>()

        for (record in relevant) {
            require(record.accumulatedActiveMillis >= 0L) {
                "Play-stat duration must not be negative"
            }
            val installed = record.storageKey in installedKeys
            var result = dao.reconcilePlayStat(
                sessionId = record.sessionId,
                storageKey = record.storageKey,
                reachedRunning = record.reachedRunning,
                firstRunningWallTimeMillis = record.firstRunningWallTimeMillis,
                accumulatedActiveMillis = record.accumulatedActiveMillis,
                allowMissingTarget = !installed,
            )

            if (result == LibraryPlayStatReconcileResult.TargetMissing && installed) {
                val scanned = withContext(Dispatchers.IO) {
                    scanner.scanStorageKeys(activeRoot, setOf(record.storageKey))
                }
                val target = scanned.apps.singleOrNull { it.storageKey == record.storageKey }
                if (target == null || scanned.failures.isNotEmpty()) {
                    pending += record.sessionId
                    continue
                }
                dao.applyFilesystemReconciliation(listOf(target), emptySet())
                result = dao.reconcilePlayStat(
                    sessionId = record.sessionId,
                    storageKey = record.storageKey,
                    reachedRunning = record.reachedRunning,
                    firstRunningWallTimeMillis = record.firstRunningWallTimeMillis,
                    accumulatedActiveMillis = record.accumulatedActiveMillis,
                    allowMissingTarget = false,
                )
            }

            when (result) {
                LibraryPlayStatReconcileResult.Applied,
                LibraryPlayStatReconcileResult.AlreadyReceipted -> reconciled += record.sessionId
                LibraryPlayStatReconcileResult.TargetMissing -> pending += record.sessionId
            }
        }
        return Result(reconciled, pending)
    }

    private fun normalizeWorkdir(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    } catch (_: SecurityException) {
        file.absoluteFile
    }
}
