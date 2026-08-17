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
import kotlinx.coroutines.withContext

/**
 * Conservative repair path for manual filesystem changes and interrupted installs.
 *
 * Normal installer/delete/reinstall flows should mutate the Library DB directly. This reconciler
 * exists to recover the catalog from confident converted-directory name differences without
 * re-reading metadata for unchanged applications.
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

        val initialFilesystemKeys = withContext(Dispatchers.IO) {
            scanner.storageKeys(emulatorDir)
        }
        val databaseKeys = dao.getStorageKeys().toSet()
        val initialDiff = difference(databaseKeys, initialFilesystemKeys)
        if (initialDiff.added.isEmpty() && initialDiff.removed.isEmpty()) {
            return Result(0, 0, emptyList())
        }

        val scannedAdded = withContext(Dispatchers.IO) {
            scanner.scanStorageKeys(emulatorDir, initialDiff.added)
        }

        // Revalidate the cheap directory-name snapshot immediately before publishing the DB diff.
        // A removal must be absent from both snapshots; a directory that disappears only during
        // this pass is deferred to the next reconciliation instead of losing Library-owned state
        // after a single observation. Added rows are inserted only when still present at the end.
        val finalFilesystemKeys = withContext(Dispatchers.IO) {
            scanner.storageKeys(emulatorDir)
        }
        val stillMissing = databaseKeys - finalFilesystemKeys
        val finalRemoved = initialDiff.removed.intersect(stillMissing)
        val finalAddedKeys = finalFilesystemKeys - databaseKeys
        val finalAddedApps = scannedAdded.apps.filter { it.storageKey in finalAddedKeys }

        dao.applyFilesystemReconciliation(finalAddedApps, finalRemoved)

        return Result(
            addedCount = finalAddedApps.size,
            removedCount = finalRemoved.size,
            failures = scannedAdded.failures,
        )
    }

    companion object {
        fun difference(databaseKeys: Set<String>, filesystemKeys: Set<String>): Diff = Diff(
            added = filesystemKeys - databaseKeys,
            removed = databaseKeys - filesystemKeys,
        )
    }
}
