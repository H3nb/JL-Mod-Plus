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

/** Read-only view of one emulator work directory's installed converted applications. */
class LibraryScanner {
    data class Failure(val storageKey: String, val reason: String)
    data class Result(val apps: List<LibraryAppEntity>, val failures: List<Failure>)

    @Throws(IOException::class)
    fun scan(
        emulatorDir: File,
        onProgress: ((completed: Int, total: Int, storageKey: String) -> Unit)? = null,
    ): Result = scanDirectories(installedDirectories(emulatorDir), onProgress)

    @Throws(IOException::class)
    fun scanStorageKeys(
        emulatorDir: File,
        storageKeys: Set<String>,
        onProgress: ((completed: Int, total: Int, storageKey: String) -> Unit)? = null,
    ): Result {
        if (storageKeys.isEmpty()) return Result(emptyList(), emptyList())
        val requested = storageKeys.toHashSet()
        val installed = installedDirectories(emulatorDir)
        val matching = installed.filter { it.name in requested }
        val result = scanDirectories(matching, onProgress)
        if (matching.size == requested.size) return result

        val found = matching.mapTo(HashSet()) { it.name }
        val missing = requested.minus(found).sorted().map { storageKey ->
            Failure(storageKey, "Installed directory disappeared during reconciliation")
        }
        return Result(result.apps, result.failures + missing)
    }

    @Throws(IOException::class)
    fun storageKeys(emulatorDir: File): Set<String> =
        installedDirectories(emulatorDir).mapTo(LinkedHashSet()) { it.name }

    private fun scanDirectories(
        directories: List<File>,
        onProgress: ((completed: Int, total: Int, storageKey: String) -> Unit)?,
    ): Result {
        val apps = ArrayList<LibraryAppEntity>(directories.size)
        val failures = ArrayList<Failure>()
        directories.forEachIndexed { index, appDir ->
            val storageKey = appDir.name
            try {
                apps += parseDirectory(appDir)
            } catch (error: Exception) {
                failures += Failure(storageKey, boundedReason(error))
            } catch (error: LinkageError) {
                failures += Failure(storageKey, boundedReason(error))
            } finally {
                onProgress?.invoke(index + 1, directories.size, storageKey)
            }
        }
        return Result(apps, failures)
    }

    @Throws(IOException::class)
    private fun parseDirectory(appDir: File): LibraryAppEntity {
        requireConvertedPayload(appDir)
        val descriptorFile = File(appDir, MANIFEST_FILE)
        if (!descriptorFile.isFile) throw IOException("Missing $MANIFEST_FILE")
        val descriptor = Descriptor(descriptorFile, false)
        return LibraryAppEntity(
            storageKey = appDir.name,
            sourceTitle = descriptor.name,
            sourceVendor = descriptor.vendor,
            sourceVersion = descriptor.version,
            sourceDescription = descriptor.attrs[Descriptor.MIDLET_DESCRIPTION],
            iconRevision = LibraryIconRevision.fromFile(File(appDir, ICON_FILE)),
        )
    }

    @Throws(IOException::class)
    private fun installedDirectories(emulatorDir: File): List<File> {
        val convertedDir = File(emulatorDir, CONVERTED_DIR)
        if (!convertedDir.exists()) return emptyList()
        if (!convertedDir.isDirectory) {
            throw IOException("Converted path is not a directory: ${convertedDir.absolutePath}")
        }
        val entries = convertedDir.listFiles()
            ?: throw IOException("Unable to list converted directory: ${convertedDir.absolutePath}")
        return entries.asSequence()
            .filter { it.isDirectory && it.name != LibraryInstallRecovery.STAGING_DIR_NAME }
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
        const val DEX_ARCHIVE = "converted.zip"
        const val DEX_FILE = "converted.dex"
        const val MANIFEST_FILE = "converted.dex.conf"
        const val ICON_FILE = "icon.png"
        const val MAX_FAILURE_REASON_LENGTH = 512
    }
}
