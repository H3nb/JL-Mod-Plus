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
        data class Opening(
            val generation: Long,
            val emulatorDir: File,
        ) : State
        data class Indexing(
            val generation: Long,
            val emulatorDir: File,
            val completed: Int,
            val total: Int,
            val storageKey: String,
        ) : State
        data class Ready(
            val generation: Long,
            val emulatorDir: File,
            val apps: List<LibraryAppRow>,
            val bootstrapFailures: List<LibraryScanner.Failure> = emptyList(),
            val legacyImportFailure: String? = null,
            val reconciliationFailures: List<LibraryScanner.Failure> = emptyList(),
        ) : State
        data class Error(
            val generation: Long,
            val emulatorDir: File,
            val message: String,
        ) : State
    }

    private data class WorkdirRequest(val generation: Long, val emulatorDir: File) {
        fun token() = LibraryGenerationToken(generation, emulatorDir)
    }

    private data class ActiveDatabase(
        val token: LibraryGenerationToken,
        val database: LibraryDatabase,
    )

    private val nextGeneration = AtomicLong()
    private val workdirRequests = MutableStateFlow<WorkdirRequest?>(null)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val activeMutex = Mutex()
    private var activeDatabase: ActiveDatabase? = null
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val worker = scope.launch {
        workdirRequests.filterNotNull().collectLatest { request ->
            runWorkdir(request)
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

    fun currentReadyToken(): LibraryGenerationToken? {
        val ready = mutableState.value as? State.Ready ?: return null
        if (workdirRequests.value?.generation != ready.generation) return null
        return LibraryGenerationToken(ready.generation, ready.emulatorDir)
    }

    fun currentApp(expected: LibraryGenerationToken, appId: Long): LibraryAppRow? {
        val ready = requireReadyGeneration(expected)
        return ready.apps.firstOrNull { it.id == appId }
    }

    fun currentStorageKeys(expected: LibraryGenerationToken): Set<String> =
        requireReadyGeneration(expected).apps.mapTo(LinkedHashSet()) { it.storageKey }

    fun findBySourceIdentity(
        expected: LibraryGenerationToken,
        sourceTitle: String,
        sourceVendor: String,
    ): List<LibraryAppRow> {
        val ready = requireReadyGeneration(expected)
        return ready.apps.filter {
            it.sourceTitle == sourceTitle && it.sourceVendor == sourceVendor
        }
    }

    suspend fun setCustomTitle(
        expected: LibraryGenerationToken,
        appId: Long,
        title: String?,
    ) {
        withActiveDatabase(expected) { dao ->
            val app = dao.getApp(appId) ?: error("Library app disappeared: $appId")
            val normalized = title?.trim()?.takeIf { it.isNotEmpty() && it != app.sourceTitle }
            check(dao.updateCustomTitle(appId, normalized) == 1) {
                "Unable to update Library title for app $appId"
            }
        }
    }

    suspend fun setMetadataOverrides(
        expected: LibraryGenerationToken,
        appId: Long,
        title: String,
        vendor: String,
        version: String,
        description: String,
    ) {
        withActiveDatabase(expected) { dao ->
            val app = dao.getApp(appId) ?: error("Library app disappeared: $appId")
            val customTitle = normalizeOverride(title, app.sourceTitle, requireNonBlank = true)
            val customVendor = normalizeOverride(vendor, app.sourceVendor)
            val customVersion = normalizeOverride(version, app.sourceVersion)
            val customDescription = normalizeOverride(description, app.sourceDescription.orEmpty())
            check(
                dao.updateCustomMetadata(
                    appId = appId,
                    customTitle = customTitle,
                    customVendor = customVendor,
                    customVersion = customVersion,
                    customDescription = customDescription,
                ) == 1,
            ) { "Unable to update Library metadata for app $appId" }
        }
    }

    suspend fun resetMetadataOverrides(expected: LibraryGenerationToken, appId: Long) {
        withActiveDatabase(expected) { dao ->
            check(dao.getApp(appId) != null) { "Library app disappeared: $appId" }
            check(dao.resetCustomMetadata(appId) == 1) {
                "Unable to reset Library metadata for app $appId"
            }
        }
    }

    suspend fun setFavorite(
        expected: LibraryGenerationToken,
        appId: Long,
        favorite: Boolean,
    ) {
        withActiveDatabase(expected) { dao ->
            val app = dao.getApp(appId) ?: error("Library app disappeared: $appId")
            if (app.favorite == favorite) return@withActiveDatabase
            check(dao.updateFavorite(appId, favorite) == 1) {
                "Unable to update favorite state for app $appId"
            }
        }
    }

    suspend fun recordInstalledApp(
        expected: LibraryGenerationToken,
        existingId: Long?,
        metadata: InstalledAppMetadata,
    ): Long = withActiveDatabase(expected) { dao ->
        dao.recordInstalledApp(existingId, metadata)
    }

    /** Call only after the authoritative filesystem delete has succeeded. */
    suspend fun removeCatalogApp(expected: LibraryGenerationToken, storageKey: String) {
        withActiveDatabase(expected) { dao ->
            dao.deleteAppByStorageKey(storageKey)
        }
    }

    fun isReadyGeneration(expected: LibraryGenerationToken): Boolean {
        if (workdirRequests.value?.generation != expected.generation) return false
        val ready = mutableState.value as? State.Ready ?: return false
        return ready.generation == expected.generation && ready.emulatorDir == expected.emulatorDir
    }

    override fun close() {
        // Activity/ViewModel destruction is an authority boundary just like a workdir switch.
        // Invalidate synchronously so stale Rx callbacks cannot mutate the still-open DB while the
        // worker is winding down; runWorkdir's finally block remains responsible for closing it.
        workdirRequests.value = null
        mutableState.value = State.Idle
        worker.cancel()
    }

    private fun request(emulatorDir: File) {
        // Publishing the request and Opening state together is the synchronous invalidation boundary.
        // UI/actions stop seeing the previous READY generation immediately; DB close can follow on
        // the worker without leaving a window where stale rows still look actionable.
        val request = WorkdirRequest(
            generation = nextGeneration.incrementAndGet(),
            emulatorDir = emulatorDir,
        )
        workdirRequests.value = request
        mutableState.value = State.Opening(request.generation, request.emulatorDir)
    }

    private suspend fun runWorkdir(request: WorkdirRequest) {
        val emulatorDir = request.emulatorDir
        publishIfCurrent(request, State.Opening(request.generation, emulatorDir))
        var database: LibraryDatabase? = null
        try {
            database = withContext(Dispatchers.IO) { databaseFactory(emulatorDir) }
            currentCoroutineContext().ensureActive()
            ensureCurrent(request)
            val bootstrap = bootstrapper.ensureReady(database, emulatorDir) { progress ->
                publishIfCurrent(
                    request,
                    State.Indexing(
                        generation = request.generation,
                        emulatorDir = emulatorDir,
                        completed = progress.completed,
                        total = progress.total,
                        storageKey = progress.storageKey,
                    ),
                )
            }
            currentCoroutineContext().ensureActive()
            ensureCurrent(request)

            val reconciliation = if (bootstrap.alreadyReady) {
                reconciler.reconcile(database, emulatorDir)
            } else {
                LibraryReconciler.Result(0, 0, emptyList())
            }
            currentCoroutineContext().ensureActive()
            ensureCurrent(request)

            activeMutex.withLock {
                currentCoroutineContext().ensureActive()
                ensureCurrent(request)
                activeDatabase = ActiveDatabase(request.token(), database)
            }

            database.libraryDao().observeApps().collect { apps ->
                currentCoroutineContext().ensureActive()
                ensureCurrent(request)
                mutableState.value = State.Ready(
                    generation = request.generation,
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
            if (isCurrent(request)) {
                mutableState.value = State.Error(
                    generation = request.generation,
                    emulatorDir = emulatorDir,
                    message = boundedMessage(error),
                )
            }
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

    private fun publishIfCurrent(request: WorkdirRequest, state: State) {
        if (isCurrent(request)) mutableState.value = state
    }

    private fun ensureCurrent(request: WorkdirRequest) {
        check(isCurrent(request)) {
            "Stale Library generation ${request.generation} for ${request.emulatorDir.absolutePath}"
        }
    }

    private fun isCurrent(request: WorkdirRequest): Boolean =
        workdirRequests.value?.generation == request.generation

    private fun requireReadyGeneration(expected: LibraryGenerationToken): State.Ready {
        check(workdirRequests.value?.generation == expected.generation) {
            "Stale Library request generation=${expected.generation}"
        }
        val ready = mutableState.value as? State.Ready
            ?: error("Library is not READY")
        check(ready.generation == expected.generation && ready.emulatorDir == expected.emulatorDir) {
            "Stale Library snapshot generation=${expected.generation} workdir=${expected.emulatorDir.absolutePath}; " +
                "active=${ready.generation}:${ready.emulatorDir.absolutePath}"
        }
        return ready
    }

    private suspend fun <T> withActiveDatabase(
        expected: LibraryGenerationToken,
        block: suspend (LibraryDao) -> T,
    ): T {
        val normalized = LibraryGenerationToken(
            expected.generation,
            normalizeWorkdir(expected.emulatorDir),
        )
        return activeMutex.withLock {
            val active = activeDatabase
                ?: error("Library database is not READY")
            check(workdirRequests.value?.generation == active.token.generation) {
                "Library mutation belongs to an invalidated generation ${active.token.generation}"
            }
            check(active.token == normalized) {
                "Stale Library mutation generation=${normalized.generation} " +
                    "workdir=${normalized.emulatorDir.absolutePath}; " +
                    "active=${active.token.generation}:${active.token.emulatorDir.absolutePath}"
            }
            block(active.database.libraryDao())
        }
    }

    private fun normalizeOverride(value: String, source: String, requireNonBlank: Boolean = false): String? {
        val normalized = value.trim()
        require(!requireNonBlank || normalized.isNotEmpty()) { "Library title must not be blank" }
        return normalized.takeUnless { it == source }
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