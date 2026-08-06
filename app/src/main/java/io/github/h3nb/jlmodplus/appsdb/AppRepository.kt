/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
 * Copyright 2026 H3NB
 *
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

package io.github.h3nb.jlmodplus.appsdb

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.h3nb.jlmodplus.applist.AppItem
import io.github.h3nb.jlmodplus.util.AppUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA

/**
 * Owns the app database for the activity-scoped app list model.
 *
 * The scope is created for each database path and is cancelled before the
 * database is closed. This keeps Room Flow collection and write operations
 * attached to the repository owner instead of a process-wide scope.
 */
class AppRepository {
    private val appList = MutableLiveData<List<AppItem>>()
    private var database: AppDatabase? = null
    private var scope: CoroutineScope? = null
    private var observationJob: Job? = null
    private var initialSyncJob: Job? = null
    private var query = AppListSQLiteQuery()

    fun setDatabaseFile(file: String, refreshApplications: Boolean = true) {
        val currentDatabase = database
        if (currentDatabase != null) {
            if (file == currentDatabase.openHelper.readableDatabase.path) {
                return
            }
            close()
        }
        initDatabase(file, refreshApplications)
    }

    private fun initDatabase(file: String, refreshApplications: Boolean) {
        val openedDatabase = AppDatabase.open(file)
        database = openedDatabase
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (refreshApplications) {
            val currentScope = checkNotNull(scope)
            val initialQuery = query.copy()
            initialSyncJob = currentScope.launch {
                try {
                    openedDatabase.appItemDao().getAll(initialQuery)
                        .first()
                        .let { syncWithFilesystem(openedDatabase, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    reportError(error)
                }
            }
        }
        restartObservation(openedDatabase)
    }

    private fun restartObservation(openedDatabase: AppDatabase = checkNotNull(database)) {
        observationJob?.cancel()
        val currentScope = scope ?: return
        val querySnapshot = query.copy()
        observationJob = currentScope.launch {
            var firstEmission = true
            try {
                openedDatabase.appItemDao().getAll(querySnapshot)
                    .distinctUntilChanged()
                    .catch { error ->
                        if (error !is CancellationException) {
                            reportError(error)
                        }
                    }
                    .collect { list ->
                        if (firstEmission) {
                            firstEmission = false
                            initialSyncJob?.join()
                        }
                        appList.postValue(list)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun insert(item: AppItem) {
        launchDatabaseWork { it.insert(item) }
    }

    fun update(item: AppItem) {
        launchDatabaseWork { it.update(item) }
    }

    fun delete(item: AppItem) {
        launchDatabaseWork {
            it.delete(item)
            withContext(Dispatchers.IO) {
                AppUtils.deleteApp(item)
            }
        }
    }

    private fun launchDatabaseWork(operation: suspend (AppItemDao) -> Unit) {
        val openedDatabase = database ?: return reportError(
            IllegalStateException("App database is not open")
        )
        val currentScope = scope ?: return reportError(
            IllegalStateException("App repository scope is not active")
        )
        currentScope.launch {
            try {
                operation(openedDatabase.appItemDao())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportError(error)
            }
        }
    }

    fun get(name: String, vendor: String): AppItem? =
        checkNotNull(database).appItemDao().get(name, vendor)

    fun get(id: Int): AppItem? = checkNotNull(database).appItemDao().get(id)

    fun setFilter(filter: String) {
        if (query.setFilter(filter)) {
            restartObservation()
        }
    }

    fun getFilter(): String = query.filter

    fun setSort(sort: Int) {
        if (query.setSort(sort)) {
            restartObservation()
        }
    }

    fun getAppList(): LiveData<List<AppItem>> = appList

    fun close() {
        val oldDatabase = database
        database = null
        initialSyncJob?.cancel()
        initialSyncJob = null
        observationJob?.cancel()
        observationJob = null
        scope?.cancel()
        scope = null
        oldDatabase?.close()
    }

    private suspend fun syncWithFilesystem(
        openedDatabase: AppDatabase,
        list: List<AppItem>
    ) {
        val items = ArrayList(list)
        val paths = ArrayList(withContext(Dispatchers.IO) {
            AppUtils.getAppDirectories()
        })
        // Incomplete installation must not be added to the database.
        paths.remove(".tmp")
        val dao = openedDatabase.appItemDao()
        if (paths.isEmpty()) {
            if (items.isNotEmpty()) {
                dao.deleteAll()
                withContext(Dispatchers.IO) {
                    AppUtils.removeFromRecentShortcuts(items)
                }
            }
            return
        }

        val iterator = items.iterator()
        while (iterator.hasNext() && paths.isNotEmpty()) {
            val item = iterator.next()
            if (paths.remove(item.path)) {
                iterator.remove()
            }
        }
        if (items.isNotEmpty()) {
            dao.delete(items)
            withContext(Dispatchers.IO) {
                AppUtils.removeFromRecentShortcuts(items)
            }
        }
        if (paths.isNotEmpty()) {
            val apps = withContext(Dispatchers.IO) {
                AppUtils.getApps(paths)
            }
            if (apps.isNotEmpty()) {
                dao.insert(apps)
            }
        }
    }

    private fun reportError(error: Throwable) {
        Log.e("AppRepository", error.toString(), error)
        ACRA.errorReporter.handleException(error)
    }
}
