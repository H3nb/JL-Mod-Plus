/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Owns one active workdir Library at a time.
 *
 * `collectLatest` is the generation boundary: a new workdir request cancels the previous bootstrap,
 * reconciliation, and Room observation; the old database is closed in `finally` before the new
 * request body proceeds. No caller should keep a LibraryDatabase outside this owner.
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val databaseFactory: (File) -> LibraryDatabase,
    private val bootstrapper: LibraryBootstrapper = LibraryBootstrapper(),
    private val reconciler: LibraryReconciler = LibraryReconciler(),
) : AutoCloseable {
    sealed interface State {
        data object Idle : State

        data class Opening(
            val emulatorDir: File,
        ) : State

        data class Indexing(
            val emulatorDir: File,
            val completed: Int,
            val total: Int,
            val storageKey: String,
        ) : State

        data class Ready(
            val emulatorDir: File,
            val apps: List<LibraryAppRow>,
            val bootstrapFailures: List<LibraryScanner.Failure> = emptyList(),
            val legacyImportFailure: String? = null,
            val reconciliationFailures: List<LibraryScanner.Failure> = emptyList(),
        ) : State

        data class Error(
            val emulatorDir: File,
            val message: String,
        ) : State
    }

    private val workdirRequests = MutableStateFlow<File?>(null)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val worker = scope.launch {
        workdirRequests
            .filterNotNull()
            .collectLatest(::runWorkdir)
    }

    fun setEmulatorDirectory(emulatorDir: File) {
        workdirRequests.value = emulatorDir.absoluteFile
    }

    override fun close() {
        worker.cancel()
    }

    private suspend fun runWorkdir(emulatorDir: File) {
        mutableState.value = State.Opening(emulatorDir)
        var database: LibraryDatabase? = null
        try {
            database = databaseFactory(emulatorDir)
            val bootstrap = bootstrapper.ensureReady(database, emulatorDir) { progress ->
                mutableState.value = State.Indexing(
                    emulatorDir = emulatorDir,
                    completed = progress.completed,
                    total = progress.total,
                    storageKey = progress.storageKey,
                )
            }
            currentCoroutineContext().ensureActive()

            val reconciliation = if (bootstrap.alreadyReady) {
                reconciler.reconcile(database, emulatorDir)
            } else {
                LibraryReconciler.Result(0, 0, emptyList())
            }
            currentCoroutineContext().ensureActive()

            database.libraryDao().observeApps().collect { apps ->
                currentCoroutineContext().ensureActive()
                mutableState.value = State.Ready(
                    emulatorDir = emulatorDir,
                    apps = apps,
                    bootstrapFailures = bootstrap.failures,
                    legacyImportFailure = bootstrap.legacyImportFailure,
                    reconciliationFailures = reconciliation.failures,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = State.Error(
                emulatorDir = emulatorDir,
                message = boundedMessage(error),
            )
        } finally {
            val closing = database
            if (closing != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    closing.close()
                }
            }
        }
    }

    private fun boundedMessage(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        val value = if (detail.isEmpty()) error.javaClass.simpleName
        else "${error.javaClass.simpleName}: $detail"
        return value.take(MAX_ERROR_LENGTH)
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 768
    }
}
