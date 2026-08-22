/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
@file:JvmName("LibraryTransferActions")

package ru.playsoftware.j2meloader.librarydb

import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Java-friendly, generation-safe entry points for read-only Library transfer workflows. */
fun interface LibraryExportProgressCallback {
    fun onProgress(progress: LibraryAppBundleExporter.Progress)
}

fun LibraryViewModel.prepareShareApp(
    appId: Long,
    callback: LibraryViewModel.MutationCallback<LibraryShareManager.PreparedShare>,
) {
    launchLibraryTransfer(appId, callback) { generation, app ->
        LibraryShareManager.prepare(
            context = getApplication(),
            emulatorDir = generation.emulatorDir,
            storageKey = app.storageKey,
            displayTitle = app.title,
        )
    }
}

fun LibraryViewModel.prepareExportAppBundle(
    appId: Long,
    progressCallback: LibraryExportProgressCallback?,
    callback: LibraryViewModel.MutationCallback<LibraryAppBundleExporter.PreparedExport>,
) {
    val lastPercent = AtomicInteger(-1)
    launchLibraryTransfer(appId, callback) { generation, app ->
        LibraryAppBundleExporter.prepare(
            context = getApplication(),
            emulatorDir = generation.emulatorDir,
            storageKey = app.storageKey,
            displayTitle = app.title,
            sourceVendor = app.vendor,
            sourceVersion = app.version,
            onProgress = if (progressCallback == null) {
                null
            } else {
                { progress ->
                    val percent = progress.percent()
                    if (lastPercent.getAndSet(percent) != percent) {
                        viewModelScope.launch {
                            if (isReadyGeneration(generation.generation, generation.emulatorDir)) {
                                progressCallback.onProgress(progress)
                            }
                        }
                    }
                }
            },
        )
    }
}

fun LibraryViewModel.prepareShareApps(
    appIds: Set<Long>,
    callback: LibraryViewModel.MutationCallback<LibraryShareManager.PreparedShare>,
) {
    launchLibraryBulkTransfer(appIds, callback) { generation, plan ->
        LibraryBulkTransfer.prepareJarArchive(
            context = getApplication(),
            sources = plan.apps.map { app ->
                LibraryJarArchiveExporter.JarSource(
                    appId = app.id,
                    title = app.title,
                    file = LibraryFileOperations.retainedJar(generation.emulatorDir, app.storageKey),
                )
            },
            displayTitle = "JL-Mod-Plus-Apps",
        )
    }
}

fun LibraryViewModel.prepareExportAppsBundle(
    appIds: Set<Long>,
    progressCallback: LibraryExportProgressCallback?,
    callback: LibraryViewModel.MutationCallback<LibraryAppBundleExporter.PreparedExport>,
) {
    val lastPercent = AtomicInteger(-1)
    launchLibraryBulkTransfer(appIds, callback) { generation, plan ->
        LibraryBulkTransfer.prepareUniversalBundle(
            context = getApplication(),
            sources = plan.apps.mapIndexed { index, app ->
                LibraryUniversalBundleExporter.AppSource(
                    bundleId = "a${(index + 1).toString().padStart(4, '0')}",
                    title = app.title,
                    vendor = app.vendor,
                    version = app.version,
                    emulatorDir = generation.emulatorDir,
                    storageKey = app.storageKey,
                )
            },
            displayTitle = "JL-Mod-Plus-Apps",
            onProgress = if (progressCallback == null) {
                null
            } else {
                { progress ->
                    val percent = progress.percent()
                    if (lastPercent.getAndSet(percent) != percent) {
                        viewModelScope.launch {
                            if (isReadyGeneration(generation.generation, generation.emulatorDir)) {
                                progressCallback.onProgress(progress)
                            }
                        }
                    }
                }
            },
        )
    }
}

private fun LibraryAppBundleExporter.Progress.percent(): Int {
    if (totalBytes > 0L) {
        return ((writtenBytes.coerceIn(0L, totalBytes).toDouble() / totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }
    if (totalEntries <= 0) return 0
    return ((completedEntries.coerceIn(0, totalEntries).toDouble() / totalEntries.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
}

private fun <T> LibraryViewModel.launchLibraryTransfer(
    appId: Long,
    callback: LibraryViewModel.MutationCallback<T>,
    operation: suspend (LibraryGenerationToken, LibraryAppRow) -> T,
) {
    val generation = readyGeneration()
    val app = try {
        generation?.let { getApp(it.generation, it.emulatorDir, appId) }
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

    viewModelScope.launch {
        try {
            val result = operation(generation, app)
            check(isReadyGeneration(generation.generation, generation.emulatorDir)) {
                "Library generation changed while preparing transfer"
            }
            val current = getApp(generation.generation, generation.emulatorDir, app.id)
            check(current?.storageKey == app.storageKey) {
                "Library transfer target changed while preparing output"
            }
            callback.complete(result, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            callback.complete(null, error)
        }
    }
}

private fun <T> LibraryViewModel.launchLibraryBulkTransfer(
    appIds: Set<Long>,
    callback: LibraryViewModel.MutationCallback<T>,
    operation: suspend (LibraryGenerationToken, LibraryBulkSelectionPlan) -> T,
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
            IllegalStateException(
                if (plan?.missingAppIds?.isNotEmpty() == true) {
                    "One or more selected Library apps are no longer available"
                } else {
                    "Library apps are not available in the active READY generation"
                },
            ),
        )
        return
    }

    viewModelScope.launch {
        try {
            val result = operation(generation, plan)
            check(isReadyGeneration(generation.generation, generation.emulatorDir)) {
                "Library generation changed while preparing bulk transfer"
            }
            callback.complete(result, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            callback.complete(null, error)
        }
    }
}
