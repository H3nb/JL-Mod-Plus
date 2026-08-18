/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.util.FileUtils

/** Filesystem actions scoped explicitly to the captured workdir generation. */
object LibraryFileOperations {
    data class DeleteResult(
        val appPath: String,
        val leftoverConfig: Boolean,
        val leftoverSaveData: Boolean,
    )

    suspend fun deleteInstalledApp(
        context: Context,
        emulatorDir: File,
        storageKey: String,
    ): DeleteResult = withContext(Dispatchers.IO) {
        requireSafeStorageKey(storageKey)
        val appDir = File(File(emulatorDir, "converted"), storageKey)
        val configDir = File(File(emulatorDir, "configs"), storageKey)
        val dataDir = File(File(emulatorDir, "data"), storageKey)
        val appPath = appDir.absolutePath

        FileUtils.deleteDirectory(appDir)
        if (appDir.exists()) {
            throw IOException("Unable to delete installed app directory: $appPath")
        }

        // An explicit user delete wins over any leftover reinstall recovery evidence. Otherwise a
        // later startup could restore an app the user intentionally removed.
        LibraryInstallRecovery.discardBackupForDelete(emulatorDir, storageKey)

        // Once converted/<key> is gone, installed-app existence is gone. Config/save cleanup remains
        // best-effort so a leftover side directory cannot make the catalog falsely claim the app is
        // still installed; a later user/manual cleanup can remove those remnants safely.
        FileUtils.deleteDirectory(configDir)
        FileUtils.deleteDirectory(dataDir)
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(appPath))

        DeleteResult(
            appPath = appPath,
            leftoverConfig = configDir.exists(),
            leftoverSaveData = dataDir.exists(),
        )
    }

    /** Resolve volatile reinstall availability only when the user requests that action. */
    suspend fun hasRetainedJar(emulatorDir: File, storageKey: String): Boolean =
        withContext(Dispatchers.IO) {
            retainedJar(emulatorDir, storageKey).isFile
        }

    fun retainedJar(emulatorDir: File, storageKey: String): File {
        requireSafeStorageKey(storageKey)
        return File(File(File(emulatorDir, "converted"), storageKey), "res.jar")
    }

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
    }
}
