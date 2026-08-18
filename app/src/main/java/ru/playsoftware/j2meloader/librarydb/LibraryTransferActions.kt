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
