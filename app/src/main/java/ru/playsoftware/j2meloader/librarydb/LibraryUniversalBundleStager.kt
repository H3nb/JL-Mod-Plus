/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Stages all app payloads from one v2 archive once, leaving the installer free to review and
 * execute them independently. The returned root is cache-owned and must be cleaned after the
 * batch reaches a terminal state.
 */
internal object LibraryUniversalBundleStager {
    private const val STAGING_DIR = "library-bulk-import"
    private const val STALE_STAGING_AGE_MILLIS = 24L * 60L * 60L * 1000L

    internal data class PreparedBundle(
        val root: File,
        val plan: LibraryBundleImportPlan,
        val apps: List<LibraryAppBundleImporter.PreparedUniversalApp>,
    )

    @Throws(IOException::class)
    fun prepare(context: Context, source: Uri): PreparedBundle {
        val importRoot = File(context.cacheDir, STAGING_DIR)
        if (!importRoot.isDirectory && !importRoot.mkdirs()) {
            throw IOException("Unable to create bulk app-bundle staging directory")
        }
        cleanupStale(importRoot, System.currentTimeMillis())
        val root = File(importRoot, UUID.randomUUID().toString())
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Unable to create bulk app-bundle staging directory")
        }
        return try {
            val parsed = openSource(context, source).use(LibraryAppBundleReader::read)
            if (parsed.formatVersion != LibraryAppBundleFormat.UNIVERSAL_VERSION) {
                throw IOException("Bulk app import requires a universal v2 app bundle")
            }
            val plan = LibraryAppBundleImportPlanner.plan(parsed)
            val apps = openSource(context, source).use { input ->
                LibraryAppBundleImporter.extractUniversalBatchToStaging(
                    input = input,
                    staging = root,
                    apps = plan.items.map { it.app },
                    parseSourceMetadata = true,
                )
            }
            PreparedBundle(root = root, plan = plan, apps = apps)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    fun cleanup(bundle: PreparedBundle?) {
        bundle?.root?.deleteRecursively()
    }

    /** Removes abandoned process-death staging roots without touching active/recent imports. */
    internal fun cleanupStale(
        importRoot: File,
        nowMillis: Long,
        maxAgeMillis: Long = STALE_STAGING_AGE_MILLIS,
    ): Int {
        if (!importRoot.isDirectory || maxAgeMillis < 0L) return 0
        val children = importRoot.listFiles() ?: return 0
        var removed = 0
        children.forEach { candidate ->
            if (!candidate.isDirectory || !isUuidName(candidate.name)) return@forEach
            val modified = candidate.lastModified()
            if (modified <= 0L || nowMillis < modified || nowMillis - modified < maxAgeMillis) {
                return@forEach
            }
            if (candidate.deleteRecursively()) removed++
        }
        return removed
    }

    private fun isUuidName(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun openSource(context: Context, source: Uri): InputStream = try {
        context.contentResolver.openInputStream(source)
    } catch (error: SecurityException) {
        throw IOException("Selected app bundle is no longer readable", error)
    } ?: throw IOException("Unable to open selected app bundle")
}
