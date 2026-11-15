/*
 * Copyright 2020-2026 Yury Kharchenko
 *
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.installer

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun AppInstaller.loadInfoAwait(): Int =
    withContext(Dispatchers.IO) { loadInfo() }

internal suspend fun AppInstaller.useJarOnlyAwait(): Int =
    withContext(Dispatchers.IO) { useJarOnly() }

internal suspend fun AppInstaller.loadSelectedExternalJarAwait(uri: Uri): Int =
    withContext(Dispatchers.IO) { loadSelectedExternalJar(uri) }

/**
 * The filesystem publish returns an owned permit and runtime lease. Neither may be released
 * when the caller's UI job is cancelled: Room commit, visibility and recovery still need to run.
 * AppInstaller.cancel() remains the cooperative stop signal before the publish boundary.
 */
internal suspend fun AppInstaller.installAwait(): Int {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        val step = withContext(Dispatchers.IO) { installPrepared() }
        val publication = step.publication ?: return@withContext step.status
        publication.use { pending ->
            try {
                val library = pending.library
                val appId = library.recordInstalledApp(
                    pending.generation,
                    pending.workdir,
                    pending.existingId,
                    pending.storageKey,
                    pending.sourceTitle,
                    pending.sourceVendor,
                    pending.sourceVersion,
                    pending.sourceDescription,
                    pending.iconRevision,
                    pending.addedAt,
                )
                withContext(Dispatchers.IO) { pending.committed(appId) }
                library.awaitInstalledAppVisible(
                    pending.generation,
                    pending.workdir,
                    appId,
                    pending.storageKey,
                    VISIBILITY_TIMEOUT_MILLIS,
                )
                AppInstaller.STATUS_SUCCESS
            } catch (error: Exception) {
                throw InstallerFailure("Installed files need Library recovery", error)
            }
        }
    }
}

private const val VISIBILITY_TIMEOUT_MILLIS = 10_000L
