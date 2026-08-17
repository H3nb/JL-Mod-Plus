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
import java.io.IOException
import ru.woesss.j2me.jar.Descriptor

/**
 * Read-only view of one emulator work directory's installed converted applications.
 *
 * This intentionally does not reuse AppUtils.getApps(): the legacy helper may repair icons or
 * delete malformed/incomplete application directories. Library indexing must preserve filesystem
 * evidence so a later retry can recover an application whose descriptor was temporarily invalid.
 */
class LibraryScanner {
    data class Failure(
        val storageKey: String,
        val reason: String,
    )

    data class Result(
        val apps: List<LibraryAppEntity>,
        val failures: List<Failure>,
    )

    /**
     * Scans metadata only. A failure for one installed directory is isolated to that directory.
     * Failure to establish the converted directory itself is fatal so callers cannot mistake an
     * unreadable filesystem for an empty Library.
     */
    @Throws(IOException::class)
    fun scan(
        emulatorDir: File,
        onProgress: ((completed: Int, total: Int, storageKey: String) -> Unit)? = null,
    ): Result {
        val directories = installedDirectories(emulatorDir)
        val apps = ArrayList<LibraryAppEntity>(directories.size)
        val failures = ArrayList<Failure>()

        directories.forEachIndexed { index, appDir ->
            val storageKey = appDir.name
            try {
                requireConvertedPayload(appDir)
                val descriptorFile = File(appDir, MANIFEST_FILE)
                if (!descriptorFile.isFile) {
                    throw IOException("Missing $MANIFEST_FILE")
                }
                val descriptor = Descriptor(descriptorFile, false)
                apps += LibraryAppEntity(
                    storageKey = storageKey,
                    sourceTitle = descriptor.name,
                    sourceVendor = descriptor.vendor,
                    sourceVersion = descriptor.version,
                    sourceDescription = descriptor.attrs[Descriptor.MIDLET_DESCRIPTION],
                )
            } catch (error: Exception) {
                failures += Failure(storageKey, boundedReason(error))
            } catch (error: LinkageError) {
                // Keep a broken converted package from aborting indexing, but do not catch OOM or
                // other VM-wide failures that should abort the operation.
                failures += Failure(storageKey, boundedReason(error))
            } finally {
                onProgress?.invoke(index + 1, directories.size, storageKey)
            }
        }

        return Result(apps, failures)
    }

    /**
     * Cheap O(n) snapshot for normal-startup reconciliation. No descriptor/package contents are
     * parsed. A root-listing failure throws instead of returning an empty set, preventing accidental
     * mass removal of catalog rows when storage is unavailable.
     */
    @Throws(IOException::class)
    fun storageKeys(emulatorDir: File): Set<String> =
        installedDirectories(emulatorDir).mapTo(LinkedHashSet()) { it.name }

    @Throws(IOException::class)
    private fun installedDirectories(emulatorDir: File): List<File> {
        val convertedDir = File(emulatorDir, CONVERTED_DIR)
        if (!convertedDir.exists()) {
            return emptyList()
        }
        if (!convertedDir.isDirectory) {
            throw IOException("Converted path is not a directory: ${convertedDir.absolutePath}")
        }
        val entries = convertedDir.listFiles()
            ?: throw IOException("Unable to list converted directory: ${convertedDir.absolutePath}")
        return entries.asSequence()
            .filter { it.isDirectory && it.name != INSTALL_STAGING_DIR }
            .sortedBy { it.name }
            .toList()
    }

    @Throws(IOException::class)
    private fun requireConvertedPayload(appDir: File) {
        if (!File(appDir, DEX_ARCHIVE).isFile && !File(appDir, DEX_FILE).isFile) {
            throw IOException("Missing converted MIDlet payload")
        }
    }

    private fun boundedReason(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        val text = if (detail.isEmpty()) error.javaClass.simpleName
        else "${error.javaClass.simpleName}: $detail"
        return text.take(MAX_FAILURE_REASON_LENGTH)
    }

    private companion object {
        const val CONVERTED_DIR = "converted"
        const val INSTALL_STAGING_DIR = ".tmp"
        const val DEX_ARCHIVE = "converted.zip"
        const val DEX_FILE = "converted.dex"
        const val MANIFEST_FILE = "converted.dex.conf"
        const val MAX_FAILURE_REASON_LENGTH = 512
    }
}
