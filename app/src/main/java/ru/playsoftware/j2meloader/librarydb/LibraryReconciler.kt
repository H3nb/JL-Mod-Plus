/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.librarydb

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Conservative repair path for manual filesystem changes and interrupted installs.
 *
 * Normal installer/delete/reinstall flows should mutate the Library DB directly. This reconciler
 * exists to recover the catalog from confident converted-directory differences and the small
 * reinstall recovery journal without re-reading metadata for unchanged applications.
 */
class LibraryReconciler(
    private val scanner: LibraryScanner = LibraryScanner(),
) {
    data class Diff(
        val added: Set<String>,
        val removed: Set<String>,
    )

    data class Result(
        val addedCount: Int,
        val removedCount: Int,
        val failures: List<LibraryScanner.Failure>,
    )

    suspend fun reconcile(database: LibraryDatabase, emulatorDir: File): Result {
        val dao = database.libraryDao()
        check(dao.getLibraryState()?.bootstrapState == LibraryBootstrapState.READY) {
            "Library reconciliation requires a READY database"
        }

        val recovery = withContext(Dispatchers.IO) {
            LibraryInstallRecovery.recoverFilesystem(emulatorDir)
        }
        currentCoroutineContext().ensureActive()
        val recoveryFailures = recovery.failures.map {
            LibraryScanner.Failure(it.storageKey, it.reason)
        }
        val protectedRecoveryKeys = recovery.failures.mapTo(HashSet()) { it.storageKey }

        val initialFilesystemKeys = withContext(Dispatchers.IO) {
            scanner.storageKeys(emulatorDir)
        }
        currentCoroutineContext().ensureActive()
        val databaseKeys = dao.getStorageKeys().toSet()
        val initialDiff = difference(databaseKeys, initialFilesystemKeys)
        val keysToScan = initialDiff.added + recovery.refreshStorageKeys
        if (keysToScan.isEmpty() && initialDiff.removed.isEmpty()) {
            return Result(0, 0, recoveryFailures)
        }

        val scanned = withContext(Dispatchers.IO) {
            val scanContext = currentCoroutineContext()
            scanner.scanStorageKeys(emulatorDir, keysToScan) { _, _, _ ->
                scanContext.ensureActive()
            }
        }
        currentCoroutineContext().ensureActive()

        // Revalidate the cheap directory-name snapshot immediately before publishing the DB diff.
        // A removal must be absent from both snapshots. Keys with failed recovery evidence are never
        // removed automatically because doing so could erase Library-owned state while a backup is
        // still the only surviving copy of the installed directory.
        val finalFilesystemKeys = withContext(Dispatchers.IO) {
            scanner.storageKeys(emulatorDir)
        }
        currentCoroutineContext().ensureActive()
        val stillMissing = databaseKeys - finalFilesystemKeys
        val finalRemoved = initialDiff.removed
            .intersect(stillMissing)
            .minus(protectedRecoveryKeys)
        val finalAddedKeys = finalFilesystemKeys - databaseKeys
        val finalRefreshKeys = recovery.refreshStorageKeys.intersect(finalFilesystemKeys)
        val publishKeys = finalAddedKeys + finalRefreshKeys
        val scannedForPublish = scanned.apps.filter { it.storageKey in publishKeys }

        dao.applyFilesystemReconciliation(scannedForPublish, finalRemoved)
        currentCoroutineContext().ensureActive()

        val publishedRecoveryKeys = scannedForPublish.asSequence()
            .map { it.storageKey }
            .filter { it in finalRefreshKeys }
            .toSet()
        val cleanupFailures = withContext(Dispatchers.IO) {
            publishedRecoveryKeys.mapNotNull { storageKey ->
                if (LibraryInstallRecovery.discardBackup(emulatorDir, storageKey)) {
                    null
                } else {
                    LibraryScanner.Failure(
                        storageKey,
                        "Reinstall metadata recovered but backup cleanup failed",
                    )
                }
            }
        }

        return Result(
            addedCount = scannedForPublish.count { it.storageKey in finalAddedKeys },
            removedCount = finalRemoved.size,
            failures = recoveryFailures + scanned.failures + cleanupFailures,
        )
    }

    companion object {
        fun difference(databaseKeys: Set<String>, filesystemKeys: Set<String>): Diff = Diff(
            added = filesystemKeys - databaseKeys,
            removed = databaseKeys - filesystemKeys,
        )
    }
}
