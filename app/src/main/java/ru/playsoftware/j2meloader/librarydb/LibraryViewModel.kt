/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.playsoftware.j2meloader.config.Config

/** Activity-scoped Library facade with Java-friendly non-blocking mutation callbacks. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    fun interface MutationCallback<T> {
        fun complete(value: T?, error: Throwable?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = LibraryRepository(scope) { emulatorDir ->
        LibraryDatabase.open(application, emulatorDir)
    }

    val state: StateFlow<LibraryRepository.State> = repository.state

    init {
        repository.setEmulatorDirectory(File(Config.getEmulatorDir()))
    }

    fun setEmulatorDirectory(path: String) {
        repository.setEmulatorDirectory(File(path))
    }

    fun retry() = repository.retry()

    /** Returns only the current in-memory projection; no database/filesystem I/O occurs here. */
    fun getApp(appId: Long): LibraryAppRow? = repository.currentApp(appId)

    /** Source identity is intentionally non-unique; installer callers must handle 0/1/many. */
    fun findBySourceIdentity(sourceTitle: String, sourceVendor: String): List<LibraryAppRow> =
        repository.findBySourceIdentity(sourceTitle, sourceVendor)

    fun readyWorkdir(): File? =
        (state.value as? LibraryRepository.State.Ready)?.emulatorDir

    fun renameApp(appId: Long, title: String?, callback: MutationCallback<Unit>) {
        val workdir = readyWorkdir()
        if (workdir == null) {
            callback.complete(null, IllegalStateException("Library is not READY"))
            return
        }
        launchMutation(callback) {
            repository.setCustomTitle(workdir, appId, title)
        }
    }

    fun recordInstalledApp(
        expectedWorkdir: File,
        existingId: Long?,
        storageKey: String,
        sourceTitle: String,
        sourceVendor: String,
        sourceVersion: String,
        sourceDescription: String?,
        iconRevision: Long,
        addedAt: Long,
        callback: MutationCallback<Long>,
    ) {
        launchMutation(callback) {
            repository.recordInstalledApp(
                expectedWorkdir = expectedWorkdir,
                existingId = existingId,
                metadata = InstalledAppMetadata(
                    storageKey = storageKey,
                    sourceTitle = sourceTitle,
                    sourceVendor = sourceVendor,
                    sourceVersion = sourceVersion,
                    sourceDescription = sourceDescription,
                    iconRevision = iconRevision,
                    addedAt = addedAt,
                ),
            )
        }
    }

    fun removeCatalogApp(
        expectedWorkdir: File,
        storageKey: String,
        callback: MutationCallback<Unit>,
    ) {
        launchMutation(callback) {
            repository.removeCatalogApp(expectedWorkdir, storageKey)
        }
    }

    override fun onCleared() {
        repository.close()
        scope.cancel()
    }

    private fun <T> launchMutation(callback: MutationCallback<T>, block: suspend () -> T) {
        scope.launch {
            try {
                callback.complete(block(), null)
            } catch (error: Throwable) {
                callback.complete(null, error)
            }
        }
    }
}
