/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.playsoftware.j2meloader.crashes.MidletSessionStatsHandoff
import ru.playsoftware.j2meloader.util.Constants.PREF_APP_SORT
import ru.playsoftware.j2meloader.util.Constants.PREF_EMULATOR_DIR

/** Activity-scoped Room 3 Library facade with Java-friendly observation/mutation boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application),
    SharedPreferences.OnSharedPreferenceChangeListener {

    sealed interface DisplayState {
        data object Idle : DisplayState
        data class Loading(val emulatorDir: File) : DisplayState
        data class Indexing(
            val emulatorDir: File,
            val completed: Int,
            val total: Int,
            val storageKey: String,
        ) : DisplayState
        data class Ready(
            val generation: Long,
            val emulatorDir: File,
            val apps: List<LibraryAppRow>,
            val collections: List<LibraryCollectionRow>,
            val filter: String,
            val sortVariant: Int,
            val quickView: LibraryQuickView,
            val bootstrapFailures: List<LibraryScanner.Failure>,
            val legacyImportFailure: String?,
            val reconciliationFailures: List<LibraryScanner.Failure>,
        ) : DisplayState
        data class Error(val emulatorDir: File, val message: String) : DisplayState
    }

    fun interface StateObserver {
        fun onState(state: DisplayState)
    }

    fun interface MutationCallback<T> {
        fun complete(value: T?, error: Throwable?)
    }

    private data class DisplayInputs(
        val repositoryState: LibraryRepository.State,
        val filter: String,
        val sortVariant: Int,
        val quickView: LibraryQuickView,
    )

    private data class ImportRestoreOutcome(
        val iconRevision: Long?,
        val sourceMetadata: LibraryAppBundleImporter.SourceMetadata?,
    )

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generationCommitLock = ReentrantLock()
    private val repository = LibraryRepository(
        scope = scope,
        databaseFactory = { emulatorDir -> LibraryDatabase.open(application, emulatorDir) },
    )
    private val filter = MutableStateFlow("")
    private val sortVariant = MutableStateFlow(readSortPreference(preferences))
    private val quickView = MutableStateFlow(LibraryQuickView.All)
    private val playStatRefreshMutex = Mutex()

    private val playStatReadyWorker = scope.launch {
        repository.state
            .filterIsInstance<LibraryRepository.State.Ready>()
            .map { LibraryGenerationToken(it.generation, it.emulatorDir) }
            .distinctUntilChanged()
            .collectLatest { generation -> reconcilePlayStats(generation) }
    }

    val displayState: StateFlow<DisplayState> = combine(
        repository.state,
        filter,
        sortVariant,
        quickView,
    ) { repositoryState, activeFilter, activeSort, activeQuickView ->
        DisplayInputs(repositoryState, activeFilter, activeSort, activeQuickView)
    }.mapLatest { input ->
        when (val repositoryState = input.repositoryState) {
            LibraryRepository.State.Idle -> DisplayState.Idle
            is LibraryRepository.State.Opening -> DisplayState.Loading(repositoryState.emulatorDir)
            is LibraryRepository.State.Indexing -> DisplayState.Indexing(
                emulatorDir = repositoryState.emulatorDir,
                completed = repositoryState.completed,
                total = repositoryState.total,
                storageKey = repositoryState.storageKey,
            )
            is LibraryRepository.State.Error -> DisplayState.Error(
                repositoryState.emulatorDir,
                repositoryState.message,
            )
            is LibraryRepository.State.Ready -> {
                val projected = withContext(Dispatchers.Default) {
                    LibraryListProjection.project(
                        rows = repositoryState.apps,
                        filter = input.filter,
                        sortVariant = input.sortVariant,
                        quickView = input.quickView,
                    )
                }
                DisplayState.Ready(
                    generation = repositoryState.generation,
                    emulatorDir = repositoryState.emulatorDir,
                    apps = projected,
                    collections = repositoryState.collections,
                    filter = input.filter,
                    sortVariant = input.sortVariant,
                    quickView = input.quickView,
                    bootstrapFailures = repositoryState.bootstrapFailures,
                    legacyImportFailure = repositoryState.legacyImportFailure,
                    reconciliationFailures = repositoryState.reconciliationFailures,
                )
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, DisplayState.Idle)

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    fun setEmulatorDirectory(path: String) {
        generationCommitLock.withLock {
            repository.setEmulatorDirectory(File(path))
        }
    }

    fun retry() {
        generationCommitLock.withLock {
            repository.retry()
        }
    }

    fun setFilter(value: String) {
        filter.value = value.trim()
    }

    fun getFilter(): String = filter.value

    fun setQuickView(value: LibraryQuickView) {
        quickView.value = value
    }

    fun getQuickView(): LibraryQuickView = quickView.value

    fun setSort(value: Int) {
        if (sortVariant.value == value) return
        sortVariant.value = value
        preferences.edit().putInt(PREF_APP_SORT, value).apply()
    }

    fun observe(owner: LifecycleOwner, observer: StateObserver) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                displayState.collect(observer::onState)
            }
        }
    }

    fun readyGeneration(): LibraryGenerationToken? = repository.currentReadyToken()

    fun readyWorkdir(): File? = readyGeneration()?.emulatorDir

    fun refreshPlayStats() {
        val generation = readyGeneration() ?: return
        scope.launch { reconcilePlayStats(generation) }
    }

    fun getApp(appId: Long): LibraryAppRow? {
        val generation = readyGeneration() ?: return null
        return try {
            repository.currentApp(generation, appId)
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun getApp(expectedGeneration: Long, expectedWorkdir: File, appId: Long): LibraryAppRow? =
        repository.currentApp(token(expectedGeneration, expectedWorkdir), appId)

    fun getApps(appIds: Set<Long>): List<LibraryAppRow> {
        val generation = readyGeneration() ?: return emptyList()
        return try {
            repository.currentApps(generation, appIds)
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    fun getAllApps(): List<LibraryAppRow> {
        val generation = readyGeneration() ?: return emptyList()
        return try {
            repository.currentApps(generation)
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    fun storageKeys(expectedGeneration: Long, expectedWorkdir: File): Set<String> =
        repository.currentStorageKeys(token(expectedGeneration, expectedWorkdir))

    fun findBySourceIdentity(
        expectedGeneration: Long,
        expectedWorkdir: File,
        sourceTitle: String,
        sourceVendor: String,
    ): List<LibraryAppRow> = repository.findBySourceIdentity(
        token(expectedGeneration, expectedWorkdir),
        sourceTitle,
        sourceVendor,
    )

    fun isReadyGeneration(expectedGeneration: Long, expectedWorkdir: File): Boolean =
        repository.isReadyGeneration(token(expectedGeneration, expectedWorkdir))

    /**
     * Waits for Room's observable projection to include a committed installer result.
     *
     * This Java-friendly boundary is intentionally blocking: AppInstaller calls it from its
     * dedicated visibility executor while retaining the process-wide installer permit. StateFlow
     * suspension avoids timing sleeps and wakes immediately on either visibility or generation
     * drift.
     */
    @Throws(IOException::class)
    fun awaitInstalledAppVisible(
        expectedGeneration: Long,
        expectedWorkdir: File,
        appId: Long,
        storageKey: String,
        timeoutMillis: Long,
    ) {
        val expected = token(expectedGeneration, expectedWorkdir)
        try {
            runBlocking {
                withTimeout(timeoutMillis) {
                    repository.state.first { state ->
                        check(repository.isReadyGeneration(expected)) {
                            "Library generation changed before committed install became visible"
                        }
                        state is LibraryRepository.State.Ready &&
                            state.apps.any { app -> app.id == appId && app.storageKey == storageKey }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IOException(
                "Committed install did not become visible in the READY Library projection: $storageKey",
                error,
            )
        } catch (error: IllegalStateException) {
            throw IOException(error.message ?: "Library generation changed during visibility wait", error)
        }
    }

    fun acquireGenerationLease(
        expectedGeneration: Long,
        expectedWorkdir: File,
    ): LibraryGenerationLease {
        generationCommitLock.lock()
        try {
            val expected = token(expectedGeneration, expectedWorkdir)
            check(repository.isReadyGeneration(expected)) {
                "Library generation changed before filesystem publish"
            }
            return LibraryGenerationLease(generationCommitLock)
        } catch (error: Throwable) {
            generationCommitLock.unlock()
            throw error
        }
    }

    fun renameApp(appId: Long, title: String?, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        if (generation == null) {
            callback.complete(null, IllegalStateException("Library is not READY"))
            return
        }
        val app = try {
            repository.currentApp(generation, appId)
        } catch (_: IllegalStateException) {
            null
        }
        if (app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            repository.setCustomTitle(generation, app.id, title)
        }
    }

    fun updateMetadata(
        appId: Long,
        title: String,
        vendor: String,
        version: String,
        description: String,
        callback: MutationCallback<Unit>,
    ) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            repository.setMetadataOverrides(
                expected = generation,
                appId = app.id,
                title = title,
                vendor = vendor,
                version = version,
                description = description,
            )
        }
    }

    fun resetMetadata(appId: Long, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            repository.resetMetadataOverrides(generation, app.id)
        }
    }

    fun updateIcon(appId: Long, source: Uri, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            val prepared = withContext(Dispatchers.IO) {
                LibraryIconOverride.prepare(getApplication(), source)
            }
            try {
                val fileRevision = withContext(Dispatchers.IO) {
                    acquireGenerationLease(generation.generation, generation.emulatorDir).use {
                        val current = repository.currentApp(generation, app.id)
                        check(current?.storageKey == app.storageKey) {
                            "Library icon target changed before filesystem publish"
                        }
                        LibraryIconOverride.installPrepared(
                            generation.emulatorDir,
                            app.storageKey,
                            prepared,
                        )
                    }
                }
                repository.setIconRevision(
                    generation,
                    app.id,
                    distinctIconRevision(fileRevision, app.iconRevision),
                )
            } finally {
                withContext(Dispatchers.IO) { prepared.delete() }
            }
        }
    }

    fun resetIcon(appId: Long, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            val fileRevision = withContext(Dispatchers.IO) {
                acquireGenerationLease(generation.generation, generation.emulatorDir).use {
                    val current = repository.currentApp(generation, app.id)
                    check(current?.storageKey == app.storageKey) {
                        "Library icon target changed before reset"
                    }
                    LibraryIconOverride.resetToOriginal(generation.emulatorDir, app.storageKey)
                }
            }
            repository.setIconRevision(
                generation,
                app.id,
                distinctIconRevision(fileRevision, app.iconRevision),
            )
        }
    }

    fun restoreImportedBundle(
        appId: Long,
        prepared: LibraryAppBundleImporter.PreparedImport,
        callback: MutationCallback<Unit>,
    ) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            restoreImportedBundleAwait(
                expectedGeneration = generation.generation,
                expectedWorkdir = generation.emulatorDir,
                appId = app.id,
                storageKey = app.storageKey,
                prepared = prepared,
            )
        }
    }

    /**
     * Restores an app-owned bundle while holding the same generation and database checks used by
     * the single-app import path. Bulk import uses this boundary so filesystem publication and
     * Room reconciliation cannot drift apart when the active Library changes mid-batch.
     */
    suspend fun restoreImportedBundleAwait(
        expectedGeneration: Long,
        expectedWorkdir: File,
        appId: Long,
        storageKey: String,
        prepared: LibraryAppBundleImporter.PreparedImport,
    ) {
        val generation = token(expectedGeneration, expectedWorkdir)
        val app = requireNotNull(repository.currentApp(generation, appId)) {
            "Library import target disappeared before restore"
        }
        check(app.storageKey == storageKey) {
            "Library import target changed before restore"
        }
        val outcome = withContext(Dispatchers.IO) {
            acquireGenerationLease(expectedGeneration, expectedWorkdir).use {
                val current = requireNotNull(repository.currentApp(generation, appId)) {
                    "Library import target disappeared before restore"
                }
                check(current.storageKey == storageKey) {
                    "Library import target changed before restore"
                }
                val sourceMetadata = LibraryAppBundleImporter.readSourceMetadata(prepared)
                if (
                    sourceMetadata != null &&
                    (sourceMetadata.title != current.sourceTitle ||
                        sourceMetadata.vendor != current.sourceVendor)
                ) {
                    throw IOException(
                        "App bundle descriptor identity does not match the retained JAR",
                    )
                }
                val result = LibraryAppBundleImporter.restore(
                    prepared,
                    generation.emulatorDir,
                    storageKey,
                )
                ImportRestoreOutcome(result.iconRevision, sourceMetadata)
            }
        }
        val resolvedIconRevision = outcome.iconRevision?.let { revision ->
            distinctIconRevision(revision, app.iconRevision)
        } ?: app.iconRevision
        val sourceMetadata = outcome.sourceMetadata
        if (sourceMetadata != null) {
            repository.recordInstalledApp(
                expected = generation,
                existingId = app.id,
                metadata = InstalledAppMetadata(
                    storageKey = storageKey,
                    sourceTitle = sourceMetadata.title,
                    sourceVendor = sourceMetadata.vendor,
                    sourceVersion = sourceMetadata.version,
                    sourceDescription = sourceMetadata.description,
                    iconRevision = resolvedIconRevision,
                    addedAt = app.addedAt ?: System.currentTimeMillis(),
                ),
            )
        } else if (outcome.iconRevision != null) {
            repository.setIconRevision(
                generation,
                app.id,
                resolvedIconRevision,
            )
        }
    }

    fun setFavorite(appId: Long, favorite: Boolean, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            repository.setFavorite(generation, app.id, favorite)
        }
    }

    fun createCollection(name: String, callback: MutationCallback<Long>) {
        val generation = readyGeneration()
        if (generation == null) {
            callback.complete(null, IllegalStateException("Library is not READY"))
            return
        }
        launchMutation(callback) {
            repository.createCollection(generation, name, System.currentTimeMillis())
        }
    }

    fun renameCollection(collectionId: Long, name: String, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val collection = try {
            generation?.let { repository.currentCollection(it, collectionId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || collection == null) {
            callback.complete(null, IllegalStateException("Collection is not available"))
            return
        }
        launchMutation(callback) {
            repository.renameCollection(generation, collection.id, name)
        }
    }

    fun deleteCollection(collectionId: Long, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        val collection = try {
            generation?.let { repository.currentCollection(it, collectionId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || collection == null) {
            callback.complete(null, IllegalStateException("Collection is not available"))
            return
        }
        launchMutation(callback) {
            repository.deleteCollection(generation, collection.id)
        }
    }

    fun setCollectionMembership(
        collectionId: Long,
        appId: Long,
        included: Boolean,
        callback: MutationCallback<Unit>,
    ) {
        val generation = readyGeneration()
        if (generation == null) {
            callback.complete(null, IllegalStateException("Library is not READY"))
            return
        }
        val app = try {
            repository.currentApp(generation, appId)
        } catch (_: IllegalStateException) {
            null
        }
        val collection = try {
            repository.currentCollection(generation, collectionId)
        } catch (_: IllegalStateException) {
            null
        }
        if (app == null || collection == null) {
            callback.complete(null, IllegalStateException("Collection membership target is not available"))
            return
        }
        launchMutation(callback) {
            repository.setCollectionMembership(
                expected = generation,
                collectionId = collection.id,
                appId = app.id,
                included = included,
                addedAt = System.currentTimeMillis(),
            )
        }
    }

    fun getCollectionAppIds(collectionId: Long, callback: MutationCallback<Set<Long>>) {
        val generation = readyGeneration()
        val collection = try {
            generation?.let { repository.currentCollection(it, collectionId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || collection == null) {
            callback.complete(null, IllegalStateException("Collection is not available"))
            return
        }
        launchMutation(callback) {
            repository.collectionAppIds(generation, collection.id)
        }
    }

    fun resolveReinstallAvailability(appId: Long, callback: MutationCallback<Boolean>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(
                null,
                IllegalStateException("Library app is not available in the active READY generation"),
            )
            return
        }
        launchMutation(callback) {
            val available = LibraryFileOperations.hasRetainedJar(
                generation.emulatorDir,
                app.storageKey,
            )
            check(repository.isReadyGeneration(generation)) {
                "Library generation changed while resolving reinstall availability"
            }
            val currentApp = repository.currentApp(generation, appId)
            check(currentApp?.storageKey == app.storageKey) {
                "Library reinstall target changed while resolving availability"
            }
            available
        }
    }

    fun recordInstalledApp(
        expectedGeneration: Long,
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
        val generation = token(expectedGeneration, expectedWorkdir)
        launchMutation(callback) {
            repository.recordInstalledApp(
                expected = generation,
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

    fun deleteInstalledApp(appId: Long, callback: MutationCallback<LibraryFileOperations.DeleteResult>) {
        val generation = readyGeneration()
        val app = try {
            generation?.let { repository.currentApp(it, appId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || app == null) {
            callback.complete(null, IllegalStateException("Library app is not available in the active READY generation"))
            return
        }
        launchMutation(callback) {
            check(repository.isReadyGeneration(generation)) {
                "Library generation changed before delete"
            }
            val result = LibraryFileOperations.deleteInstalledApp(
                context = getApplication(),
                emulatorDir = generation.emulatorDir,
                storageKey = app.storageKey,
            )
            repository.removeCatalogApp(generation, app.storageKey)
            result
        }
    }

    fun addAppsToCollection(
        collectionId: Long,
        appIds: Set<Long>,
        callback: MutationCallback<Unit>,
    ) {
        val generation = readyGeneration()
        val plan = try {
            generation?.let { token ->
                LibraryBulkSelectionPlanner.plan(
                    generation = token.generation,
                    requestedAppIds = appIds,
                    availableApps = getApps(appIds),
                )
            }
        } catch (_: IllegalStateException) {
            null
        }
        val collection = try {
            generation?.let { token -> repository.currentCollection(token, collectionId) }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || plan == null || !plan.isComplete || collection == null) {
            callback.complete(null, IllegalStateException("Bulk collection target is no longer available"))
            return
        }
        launchMutation(callback) {
            repository.setCollectionMemberships(
                expected = generation,
                collectionId = collection.id,
                appIds = plan.apps.map(LibraryAppRow::id),
                included = true,
                addedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Deletes selected apps sequentially and preserves per-app outcomes for retry UI. */
    fun deleteInstalledApps(
        appIds: Set<Long>,
        callback: MutationCallback<LibraryBulkOperationResult>,
    ) {
        val generation = readyGeneration()
        val plan = try {
            generation?.let { token ->
                LibraryBulkSelectionPlanner.plan(
                    generation = token.generation,
                    requestedAppIds = appIds,
                    availableApps = getApps(appIds),
                )
            }
        } catch (_: IllegalStateException) {
            null
        }
        if (generation == null || plan == null || !plan.isComplete) {
            callback.complete(
                null,
                IllegalStateException("Selected Library apps are not available in the active generation"),
            )
            return
        }
        launchMutation(callback) {
            withContext(Dispatchers.IO) {
                val results = ArrayList<LibraryBulkItemResult>(plan.apps.size)
                acquireGenerationLease(generation.generation, generation.emulatorDir).use {
                    for ((index, app) in plan.apps.withIndex()) {
                        if (!repository.isReadyGeneration(generation)) {
                            plan.apps.drop(index).forEach { remaining ->
                                results += LibraryBulkItemResult(
                                    appId = remaining.id,
                                    storageKey = remaining.storageKey,
                                    title = remaining.title,
                                    status = LibraryBulkItemStatus.Skipped,
                                    detail = "Library generation changed before deletion",
                                )
                            }
                            break
                        }
                        try {
                            LibraryFileOperations.deleteInstalledApp(
                                context = getApplication(),
                                emulatorDir = generation.emulatorDir,
                                storageKey = app.storageKey,
                            )
                            repository.removeCatalogApp(generation, app.storageKey)
                            results += LibraryBulkItemResult(
                                appId = app.id,
                                storageKey = app.storageKey,
                                title = app.title,
                                status = LibraryBulkItemStatus.Succeeded,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            results += LibraryBulkItemResult(
                                appId = app.id,
                                storageKey = app.storageKey,
                                title = app.title,
                                status = LibraryBulkItemStatus.Failed,
                                detail = error.message,
                            )
                        }
                    }
                }
                LibraryBulkOperationResult(
                    generation = generation.generation,
                    items = results,
                    missingAppIds = plan.missingAppIds,
                )
            }
        }
    }

    fun removeCatalogApp(
        expectedGeneration: Long,
        expectedWorkdir: File,
        storageKey: String,
        callback: MutationCallback<Unit>,
    ) {
        val generation = token(expectedGeneration, expectedWorkdir)
        launchMutation(callback) {
            repository.removeCatalogApp(generation, storageKey)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            PREF_APP_SORT -> sortVariant.value = readSortPreference(sharedPreferences)
            PREF_EMULATOR_DIR -> sharedPreferences.getString(PREF_EMULATOR_DIR, null)?.let {
                setEmulatorDirectory(it)
            }
        }
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        repository.close()
        scope.cancel()
    }

    private suspend fun reconcilePlayStats(expected: LibraryGenerationToken) {
        try {
            playStatRefreshMutex.withLock {
                if (!repository.isReadyGeneration(expected)) return@withLock
                val application = getApplication<Application>()
                val records = withContext(Dispatchers.IO) {
                    MidletSessionStatsHandoff.loadTerminalRecords(application).map { record ->
                        LibraryPlayStatRecord(
                            sessionId = record.sessionId,
                            workdirLocator = record.workdirLocator,
                            storageKey = record.storageKey,
                            reachedRunning = record.reachedRunning,
                            firstRunningWallTimeMillis = record.firstRunningWallTimeMillis,
                            accumulatedActiveMillis = record.accumulatedActiveMillis,
                        )
                    }
                }
                if (records.isEmpty()) return@withLock
                val result = try {
                    withContext(Dispatchers.Default) {
                        repository.reconcilePlayStats(expected, records)
                    }
                } catch (error: IllegalStateException) {
                    if (!repository.isReadyGeneration(expected)) return@withLock
                    throw error
                }
                withContext(Dispatchers.IO) {
                    result.reconciledSessionIds.forEach { sessionId ->
                        MidletSessionStatsHandoff.markReconciled(application, sessionId)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w(
                "LibraryViewModel",
                "Unable to reconcile play statistics; leaving session journals pending",
                error,
            )
        }
    }

    private fun token(generation: Long, emulatorDir: File): LibraryGenerationToken =
        LibraryGenerationToken(generation, normalizeWorkdir(emulatorDir))

    private fun normalizeWorkdir(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    } catch (_: SecurityException) {
        file.absoluteFile
    }

    private fun distinctIconRevision(fileRevision: Long, previousRevision: Long): Long {
        if (fileRevision == 0L || fileRevision != previousRevision) return fileRevision
        return fileRevision xor Long.MIN_VALUE
    }

    private fun <T> launchMutation(callback: MutationCallback<T>, block: suspend () -> T) {
        scope.launch {
            try {
                callback.complete(block(), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                callback.complete(null, error)
            }
        }
    }

    private fun readSortPreference(sharedPreferences: SharedPreferences): Int = try {
        sharedPreferences.getInt(PREF_APP_SORT, LibraryListProjection.SORT_TITLE)
    } catch (_: ClassCastException) {
        val legacy = sharedPreferences.getString(PREF_APP_SORT, "name")
        val migrated = if (legacy == "name") LibraryListProjection.SORT_TITLE
        else LibraryListProjection.SORT_DATE
        sharedPreferences.edit().putInt(PREF_APP_SORT, migrated).apply()
        migrated
    }
}
