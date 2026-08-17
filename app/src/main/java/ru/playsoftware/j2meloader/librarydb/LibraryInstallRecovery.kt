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
import ru.playsoftware.j2meloader.util.FileUtils

/**
 * Small filesystem journal for reinstall replacement.
 *
 * A reinstall first moves the old converted directory into [BACKUP_ROOT_NAME], then publishes the
 * newly converted directory at the original storage key. The backup is intentionally retained until
 * the Room mutation succeeds. If the process dies in either half of that sequence, startup can
 * either restore the old directory or re-index the already-published new directory without guessing.
 */
object LibraryInstallRecovery {
    const val STAGING_DIR_NAME = ".tmp"
    const val BACKUP_ROOT_NAME = ".library-install-backup"

    data class Failure(val storageKey: String, val reason: String)
    data class RecoveryResult(
        val refreshStorageKeys: Set<String>,
        val failures: List<Failure>,
    )

    @JvmStatic
    fun isReservedStorageKey(storageKey: String): Boolean =
        storageKey == STAGING_DIR_NAME || storageKey == BACKUP_ROOT_NAME

    /** Move the current installed directory aside before replacing it. */
    @JvmStatic
    @Throws(IOException::class)
    fun createBackup(emulatorDir: File, storageKey: String, targetDir: File): File {
        requireSafeStorageKey(storageKey)
        if (!targetDir.isDirectory) {
            throw IOException("Installed app directory is unavailable for backup: ${targetDir.absolutePath}")
        }
        val root = backupRoot(emulatorDir)
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Unable to create Library install backup directory: ${root.absolutePath}")
        }
        val backup = File(root, storageKey)
        if (backup.exists()) {
            throw IOException("Pending Library install recovery already exists for: $storageKey")
        }
        if (!targetDir.renameTo(backup)) {
            throw IOException("Unable to stage existing app for reinstall: ${targetDir.absolutePath}")
        }
        return backup
    }

    /** Best-effort immediate rollback when publishing the replacement directory itself fails. */
    @JvmStatic
    fun restoreBackup(targetDir: File, backupDir: File): Boolean {
        if (!backupDir.isDirectory || targetDir.exists()) return false
        return backupDir.renameTo(targetDir)
    }

    /** Remove the recovery evidence only after the corresponding Room mutation has succeeded. */
    @JvmStatic
    fun discardBackup(emulatorDir: File, storageKey: String): Boolean {
        requireSafeStorageKey(storageKey)
        val root = backupRoot(emulatorDir)
        val backup = File(root, storageKey)
        if (backup.exists()) {
            FileUtils.deleteDirectory(backup)
        }
        val removed = !backup.exists()
        removeRootIfEmpty(root)
        return removed
    }

    /** Remove a recovery backup when an explicit user delete has already removed the app. */
    @JvmStatic
    fun discardBackupForDelete(emulatorDir: File, storageKey: String) {
        discardBackup(emulatorDir, storageKey)
    }

    /**
     * Recover only interrupted replacement evidence; this is deliberately not a general scanner.
     *
     * - backup exists + target missing: restore the old installed directory (replacement never
     *   published completely);
     * - backup exists + target present: keep the backup and request a targeted metadata refresh;
     *   the caller removes the backup only after that DB refresh succeeds.
     */
    @Throws(IOException::class)
    fun recoverFilesystem(emulatorDir: File): RecoveryResult {
        val root = backupRoot(emulatorDir)
        if (!root.exists()) return RecoveryResult(emptySet(), emptyList())
        if (!root.isDirectory) {
            throw IOException("Library install backup path is not a directory: ${root.absolutePath}")
        }
        val entries = root.listFiles()
            ?: throw IOException("Unable to list Library install backup directory: ${root.absolutePath}")
        if (entries.isEmpty()) {
            removeRootIfEmpty(root)
            return RecoveryResult(emptySet(), emptyList())
        }

        val converted = File(emulatorDir, "converted")
        val refresh = LinkedHashSet<String>()
        val failures = ArrayList<Failure>()
        var restoredAny = false
        entries.sortedBy { it.name }.forEach { backup ->
            val storageKey = backup.name
            if (!backup.isDirectory) {
                failures += Failure(storageKey, "Install recovery entry is not a directory")
                return@forEach
            }
            try {
                requireSafeStorageKey(storageKey)
            } catch (error: IllegalArgumentException) {
                failures += Failure(storageKey, boundedReason(error))
                return@forEach
            }

            val target = File(converted, storageKey)
            when {
                !target.exists() -> {
                    if (backup.renameTo(target)) {
                        restoredAny = true
                    } else {
                        failures += Failure(storageKey, "Unable to restore interrupted reinstall backup")
                    }
                }
                target.isDirectory -> refresh += storageKey
                else -> failures += Failure(storageKey, "Installed recovery target is not a directory")
            }
        }

        // A global .tmp can only be stale at Library startup. If a backup had to be restored, the
        // replacement never became authoritative, so discard the corresponding unfinished staging.
        if (restoredAny) {
            val staging = File(converted, STAGING_DIR_NAME)
            if (staging.exists()) FileUtils.deleteDirectory(staging)
        }
        removeRootIfEmpty(root)
        return RecoveryResult(refresh, failures)
    }

    private fun backupRoot(emulatorDir: File): File =
        File(File(emulatorDir, "converted"), BACKUP_ROOT_NAME)

    private fun removeRootIfEmpty(root: File) {
        if (root.isDirectory && root.listFiles()?.isEmpty() == true) {
            //noinspection ResultOfMethodCallIgnored
            root.delete()
        }
    }

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
        require(!isReservedStorageKey(storageKey)) { "Reserved storageKey: $storageKey" }
    }

    private fun boundedReason(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        val text = if (detail.isEmpty()) error.javaClass.simpleName
        else "${error.javaClass.simpleName}: $detail"
        return text.take(MAX_FAILURE_REASON_LENGTH)
    }

    private const val MAX_FAILURE_REASON_LENGTH = 512
}
