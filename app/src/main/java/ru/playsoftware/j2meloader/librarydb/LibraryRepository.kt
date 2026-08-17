/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns one active workdir Library at a time and serializes targeted mutations with DB close. */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val databaseFactory: (File) -> LibraryDatabase,
    private val bootstrapper: LibraryBootstrapper = LibraryBootstrapper(),
    private val reconciler: LibraryReconciler = LibraryReconciler(),
) : AutoCloseable {
    sealed interface State {
        data object Idle : State
        data class Opening(val emulatorDir: File) : State
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
        data class Error(val emulatorDir: File, val message: String) : State
    }

    private data class WorkdirRequest(val generation: Long, val emulatorDir: File)
    private data class ActiveDatabase(val emulatorDir: File, val database: LibraryDatabase)

    private val nextGeneration = AtomicLong()
    private val workdirRequests = MutableStateFlow<WorkdirRequest?>(null)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val activeMutex = Mutex()
    private var activeDatabase: ActiveDatabase? = null
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val worker = scope.launch {
        workdirRequests.filterNotNull().collectLatest { request ->
            runWorkdir(request.emulatorDir)
        }
    }

    fun setEmulatorDirectory(emulatorDir: File) {
        val normalized = normalizeWorkdir(emulatorDir)
        if (
            workdirRequests.value?.emulatorDir == normalized &&
            mutableState.value !is State.Error
        ) {
            return
        }
        request(normalized)
    }

    /** Explicitly creates a new generation for the current workdir after a recoverable failure. */
    fun retry() {
        val current = workdirRequests.value?.emulatorDir ?: return
        request(current)
    }

    fun currentApp(appId: Long): LibraryAppRow? =
        (mutableState.value as? State.Ready)?.apps?.firstOrNull { it.id == appId }

    fun findBySourceIdentity(sourceTitle: String, sourceVendor: String): List<LibraryAppRow> =
        (mutableState.value as? State.Ready)?.apps.orEmpty().filter {
            it.sourceTitle == sourceTitle && it.sourceVendor == sourceVendor
        }

    suspend fun setCustomTitle(expectedWorkdir: File, appId: Long, title: String?) {
        withActiveDatabase(expectedWorkdir) { dao ->
            val app = dao.getApp(appId) ?: error("Library app disappeared: $appId")
            val normalized = title?.trim()?.takeIf { it.isNotEmpty() && it != app.sourceTitle }
            check(dao.updateCustomTitle(appId, normalized) == 1) {
                "Unable to update Library title for app $appId"
            }
        }
    }

    suspend fun recordInstalledApp(
        expectedWorkdir: File,
        existingId: Long?,
        metadata: InstalledAppMetadata,
    ): Long = withActiveDatabase(expectedWorkdir) { dao ->
        dao.recordInstalledApp(existingId, metadata)
    }

    /** Call only after the authoritative filesystem delete has succeeded. */
    suspend fun removeCatalogApp(expectedWorkdir: File, storageKey: String) {
        withActiveDatabase(expectedWorkdir) { dao ->
            dao.deleteAppByStorageKey(storageKey)
        }
    }

    override fun close() {
        worker.cancel()
    }

    private fun request(emulatorDir: File) {
        workdirRequests.value = WorkdirRequest(
            generation = nextGeneration.incrementAndGet(),
            emulatorDir = emulatorDir,
        )
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

            activeMutex.withLock {
                currentCoroutineContext().ensureActive()
                activeDatabase = ActiveDatabase(emulatorDir, database)
            }

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
            mutableState.value = State.Error(emulatorDir, boundedMessage(error))
        } finally {
            database?.let { closing ->
                withContext(NonCancellable) {
                    activeMutex.withLock {
                        if (activeDatabase?.database === closing) {
                            activeDatabase = null
                        }
                        withContext(Dispatchers.IO) { closing.close() }
                    }
                }
            }
        }
    }

    private suspend fun <T> withActiveDatabase(
        expectedWorkdir: File,
        block: suspend (LibraryDao) -> T,
    ): T {
        val normalized = normalizeWorkdir(expectedWorkdir)
        return activeMutex.withLock {
            val active = activeDatabase
                ?: error("Library database is not READY")
            check(active.emulatorDir == normalized) {
                "Stale Library mutation for ${normalized.absolutePath}; active=${active.emulatorDir.absolutePath}"
            }
            block(active.database.libraryDao())
        }
    }

    private fun normalizeWorkdir(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    } catch (_: SecurityException) {
        file.absoluteFile
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
