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
            val filter: String,
            val sortVariant: Int,
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

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = LibraryRepository(
        scope = scope,
        databaseFactory = { emulatorDir -> LibraryDatabase.open(application, emulatorDir) },
    )
    private val filter = MutableStateFlow("")
    private val sortVariant = MutableStateFlow(readSortPreference(preferences))

    val displayState: StateFlow<DisplayState> = combine(
        repository.state,
        filter,
        sortVariant,
    ) { repositoryState, activeFilter, activeSort ->
        Triple(repositoryState, activeFilter, activeSort)
    }.mapLatest { (repositoryState, activeFilter, activeSort) ->
        when (repositoryState) {
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
                        filter = activeFilter,
                        sortVariant = activeSort,
                    )
                }
                DisplayState.Ready(
                    generation = repositoryState.generation,
                    emulatorDir = repositoryState.emulatorDir,
                    apps = projected,
                    filter = activeFilter,
                    sortVariant = activeSort,
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
        repository.setEmulatorDirectory(File(path))
    }

    fun retry() = repository.retry()

    fun setFilter(value: String) {
        filter.value = value.trim()
    }

    fun getFilter(): String = filter.value

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
        return repository.currentApp(generation, appId)
    }

    fun getApp(expectedGeneration: Long, expectedWorkdir: File, appId: Long): LibraryAppRow? =
        repository.currentApp(token(expectedGeneration, expectedWorkdir), appId)

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

    fun renameApp(appId: Long, title: String?, callback: MutationCallback<Unit>) {
        val generation = readyGeneration()
        if (generation == null) {
            callback.complete(null, IllegalStateException("Library is not READY"))
            return
        }
        val app = repository.currentApp(generation, appId)
        if (app == null) {
            callback.complete(null, IllegalStateException("Library app is not available"))
            return
        }
        launchMutation(callback) {
            repository.setCustomTitle(generation, app.id, title)
        }
    }

    /** Resolve the retained-JAR action state only after the user chooses Reinstall. */
    fun resolveReinstallAvailability(appId: Long, callback: MutationCallback<Boolean>) {
        val generation = readyGeneration()
        val app = generation?.let { repository.currentApp(it, appId) }
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

    /** Deletes authoritative installed files first; the Room row is removed only after that succeeds. */
    fun deleteInstalledApp(appId: Long, callback: MutationCallback<LibraryFileOperations.DeleteResult>) {
        val generation = readyGeneration()
        val app = generation?.let { repository.currentApp(it, appId) }
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
