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
 * Creates the initial indexed catalog for one workdir without ever mutating converted/config/save
 * data. An existing READY database is authoritative and is never rebuilt from the filesystem here.
 */
class LibraryBootstrapper(
    private val scanner: LibraryScanner = LibraryScanner(),
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
    )

    suspend fun ensureReady(
        database: LibraryDatabase,
        emulatorDir: File,
        onProgress: ((Progress) -> Unit)? = null,
    ): Result {
        val dao = database.libraryDao()
        if (dao.getLibraryState()?.bootstrapState == LibraryBootstrapState.READY) {
            return Result(alreadyReady = true, indexedCount = 0, failures = emptyList())
        }

        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.INDEXING))

        val scan = withContext(Dispatchers.IO) {
            scanner.scan(emulatorDir) { completed, total, storageKey ->
                onProgress?.invoke(Progress(completed, total, storageKey))
            }
        }

        // The scanner runs outside a long-lived transaction. Only the final lightweight catalog
        // replacement and READY transition are atomic, so a killed/failed bootstrap remains
        // visibly incomplete and can safely retry on the next launch.
        dao.replaceIncompleteCatalog(scan.apps)

        return Result(
            alreadyReady = false,
            indexedCount = scan.apps.size,
            failures = scan.failures,
        )
    }
}
