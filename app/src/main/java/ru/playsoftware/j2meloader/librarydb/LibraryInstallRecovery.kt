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
 * Small filesystem journal for install/reinstall replacement.
 *
 * Current staging and reinstall backup both live as workdir siblings rather than under converted/.
 * That keeps the installed-app namespace pure and avoids relying on filesystem-specific characters
 * to manufacture an impossible storage key. The legacy converted/.tmp staging name remains cleanup
 * input only for compatibility with interrupted installs from older builds.
 */
object LibraryInstallRecovery {
    const val STAGING_DIR_NAME = ".library-install-staging"
    const val LEGACY_STAGING_DIR_NAME = ".tmp"
    const val BACKUP_ROOT_NAME = ".library-install-backup"

    data class Failure(val storageKey: String, val reason: String)
    data class RecoveryResult(
        val refreshStorageKeys: Set<String>,
        val failures: List<Failure>,
    )

    /** Current staging is outside converted/, so only the historical in-namespace name is reserved. */
    @JvmStatic
    fun isReservedStorageKey(storageKey: String): Boolean = storageKey == LEGACY_STAGING_DIR_NAME

    @JvmStatic
    fun stagingDirectory(emulatorDir: File): File = File(emulatorDir, STAGING_DIR_NAME)

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

    /**
     * Remove a recovery backup when an explicit user delete has already removed the app. Failing
     * closed is intentional: the catalog row must remain until every authoritative converted copy
     * is absent, otherwise startup recovery could resurrect an app the user explicitly deleted.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun discardBackupForDelete(emulatorDir: File, storageKey: String) {
        if (!discardBackup(emulatorDir, storageKey)) {
            throw IOException("Unable to delete Library install recovery backup for: $storageKey")
        }
    }

    /**
     * Recover only interrupted replacement evidence; this is deliberately not a general scanner.
     * Any leftover current/legacy staging directory is an interrupted conversion at this point:
     * the READY gate has not yet opened an installer for this generation.
     *
     * - backup exists + target missing: restore the old installed directory (replacement never
     *   published completely);
     * - backup exists + target present: keep the backup and request a targeted metadata refresh;
     *   the caller removes the backup only after that DB refresh succeeds.
     */
    @Throws(IOException::class)
    fun recoverFilesystem(emulatorDir: File): RecoveryResult {
        val converted = File(emulatorDir, "converted")
        discardStaging(emulatorDir)

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

        val refresh = LinkedHashSet<String>()
        val failures = ArrayList<Failure>()
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
                    if (!backup.renameTo(target)) {
                        failures += Failure(storageKey, "Unable to restore interrupted reinstall backup")
                    }
                }
                target.isDirectory -> refresh += storageKey
                else -> failures += Failure(storageKey, "Installed recovery target is not a directory")
            }
        }

        removeRootIfEmpty(root)
        return RecoveryResult(refresh, failures)
    }

    /** Delete current workdir-sibling staging and the historical converted/.tmp leftover. */
    fun discardStaging(emulatorDir: File) {
        val current = stagingDirectory(emulatorDir)
        if (current.exists()) FileUtils.deleteDirectory(current)
        val legacy = File(File(emulatorDir, "converted"), LEGACY_STAGING_DIR_NAME)
        if (legacy.exists()) FileUtils.deleteDirectory(legacy)
    }

    private fun backupRoot(emulatorDir: File): File = File(emulatorDir, BACKUP_ROOT_NAME)

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
