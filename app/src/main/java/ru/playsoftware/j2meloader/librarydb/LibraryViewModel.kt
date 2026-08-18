/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private fun token(generation: Long, emulatorDir: File): LibraryGenerationToken =
        LibraryGenerationToken(generation, normalizeWorkdir(emulatorDir))

    private fun normalizeWorkdir(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    } catch (_: SecurityException) {
        file.absoluteFile
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