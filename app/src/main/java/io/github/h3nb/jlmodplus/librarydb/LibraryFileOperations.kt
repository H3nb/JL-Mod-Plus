/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.util.FileUtils


/** Filesystem actions scoped explicitly to the captured workdir generation. */
object LibraryFileOperations {
    data class DeleteResult(
        val appPath: String,
        val leftoverConfig: Boolean,
        val leftoverSaveData: Boolean,
        val leftoverOwnership: Boolean,
    )

    internal fun deleteInstalledApp(
        context: Context,
        emulatorDir: File,
        storageKey: String,
    ): DeleteResult {
        // Caller holds the operation permit and generation lease.
        requireSafeStorageKey(storageKey)
        val appDir = File(File(emulatorDir, "converted"), storageKey)
        val configDir = File(File(emulatorDir, "configs"), storageKey)
        val dataDir = File(File(emulatorDir, "data"), storageKey)
        val appPath = appDir.absolutePath

        FileUtils.deleteDirectory(appDir)
        if (appDir.exists()) {
            throw IOException("Unable to delete installed app directory: $appPath")
        }

        // An explicit user delete wins over any leftover reinstall recovery evidence. Otherwise
        // a later startup could restore an app the user intentionally removed.
        LibraryInstallRecovery.discardBackupForDelete(emulatorDir, storageKey)

        // Once converted/<key> is gone, installed-app existence is gone. Ownership/config/save
        // cleanup remains best-effort so a secondary failure cannot make the catalog falsely
        // claim the app is still installed. Fresh-install publication independently clears stale
        // ownership before any future reuse of this identity.
        val ownershipCleared = ProfilesManager.clearMidletOwnershipMetadata(
            PreferenceManager.getDefaultSharedPreferences(context),
            configDir,
        )
        FileUtils.deleteDirectory(configDir)
        FileUtils.deleteDirectory(dataDir)
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(appPath))

        return DeleteResult(
            appPath = appPath,
            leftoverConfig = configDir.exists(),
            leftoverSaveData = dataDir.exists(),
            leftoverOwnership = !ownershipCleared,
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
        WorkDirLayout.requireStorageKey(storageKey)
    }
}
