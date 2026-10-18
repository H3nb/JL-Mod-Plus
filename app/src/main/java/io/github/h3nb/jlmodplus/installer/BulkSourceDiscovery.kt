/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Discovers installable files before handing them to the existing installer.
 * File and directory sources are left in place; archive members are materialized
 * in request-owned cache storage, never beside the user's source files.
 */
internal class BulkSourceDiscovery(
    private val stagingRoot: File,
    private val active: () -> Boolean = { true },
) {
    data class Result(
        val files: List<File>,
        val unreadableArchives: Int,
        val skippedArchiveEntries: Int,
    )

    private val files = LinkedHashSet<File>()
    private val visitedDirectories = HashSet<String>()
    private var archiveSequence = 0
    private var expandedBytes = 0L
    private var unreadableArchives = 0
    private var skippedEntries = 0

    fun discover(sources: List<File>): Result {
        cleanupStaleStages(stagingRoot)
        sources.distinctBy { it.absolutePath }.forEach { source ->
            checkActive()
            visit(source)
        }
        return Result(files.toList(), unreadableArchives, skippedEntries)
    }

    private fun visit(source: File) {
        checkActive()
        if (source.isDirectory) {
            val key = try { source.canonicalPath } catch (_: IOException) { return }
            if (!visitedDirectories.add(key)) return
            val children = source.listFiles()
            if (children == null) {
                skippedEntries++
                return
            }
            // The planner sorts final candidates; avoid sorting every directory twice.
            children.forEach { child ->
                checkActive()
                if (!child.name.startsWith(".")) visit(child)
            }
            return
        }
        if (!source.isFile || !source.canRead()) {
            skippedEntries++
            return
        }
        when (source.extension.lowercase(Locale.ROOT)) {
            "jar", "jad", "kjx" -> files.add(source.canonicalFile)
            "zip" -> inspectArchive(source, 1)
        }
    }

    /** Returns true when this ZIP itself is a MIDlet and must remain a candidate. */
    private fun inspectArchive(source: File, depth: Int): Boolean {
        checkActive()
        try {
            ZipFile(source).use { archive ->
                if (containsMidletManifest(archive)) {
                    // The normal installer accepts ZIP container bytes as a JAR. Avoid
                    // copying an entire renamed MIDlet just to change its extension.
                    files.add(source.canonicalFile)
                    return true
                }

                if (depth > MAX_ARCHIVE_DEPTH) {
                    skippedEntries++
                    return false
                }
                val targetDirectory = "archive-${archiveSequence++}"
                var entries = 0
                val iterator = archive.entries()
                while (iterator.hasMoreElements()) {
                    checkActive()
                    if (++entries > MAX_ARCHIVE_ENTRIES) {
                        skippedEntries++
                        break
                    }
                    val entry = iterator.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension !in SUPPORTED_ARCHIVE_ENTRIES) continue
                    val destination = try {
                        stagedFile(targetDirectory, name)
                    } catch (_: IOException) {
                        skippedEntries++
                        continue
                    }
                    try {
                        archive.getInputStream(entry).use { input ->
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { output ->
                                copyBounded(input, output)
                            }
                        }
                        if (extension == "zip") {
                            if (!inspectArchive(destination, depth + 1)) destination.delete()
                        } else {
                            files.add(destination)
                        }
                    } catch (cancelled: CancellationException) {
                        destination.delete()
                        throw cancelled
                    } catch (error: IOException) {
                        destination.delete()
                        if (isStorageFull(error)) throw error
                        skippedEntries++
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            if (isStorageFull(error)) throw error
            unreadableArchives++
        }
        return false
    }

    private fun isStorageFull(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause.message?.let { message ->
                message.contains("ENOSPC", ignoreCase = true) ||
                    message.contains("No space left on device", ignoreCase = true)
            } == true
        }

    private fun containsMidletManifest(archive: ZipFile): Boolean {
        val entry = archive.getEntry("META-INF/MANIFEST.MF") ?: return false
        if (entry.size > MAX_MANIFEST_BYTES) return false
        return try {
            archive.getInputStream(entry).use { input ->
                val content = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (content.size() <= MAX_MANIFEST_BYTES) {
                    checkActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, (MAX_MANIFEST_BYTES + 1 - content.size()).toInt()))
                    if (count < 0) break
                    if (count > 0) content.write(buffer, 0, count)
                }
                if (content.size() > MAX_MANIFEST_BYTES) return false
                val manifest = Manifest(ByteArrayInputStream(content.toByteArray())).mainAttributes
                !manifest.getValue("MIDlet-Name").isNullOrBlank() &&
                    !manifest.getValue("MIDlet-Vendor").isNullOrBlank() &&
                    !manifest.getValue("MIDlet-Version").isNullOrBlank()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        }
    }

    private fun stagedFile(group: String, relativeName: String): File {
        val normalized = relativeName.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.split('/').any {
                it.isBlank() || it == "." || it == ".."
            }) {
            throw IOException("Unsafe archive entry")
        }
        val root = File(stagingRoot, group).canonicalFile
        val target = File(root, normalized).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw IOException("Archive entry escapes the staging directory")
        }
        return target
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(32 * 1024)
        var entryBytes = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            entryBytes += read
            expandedBytes += read
            if (entryBytes > MAX_ENTRY_BYTES || expandedBytes > MAX_EXPANDED_BYTES) {
                throw IOException("Archive extraction limit exceeded")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun checkActive() {
        if (!active()) throw CancellationException("Source discovery cancelled")
    }

    private companion object {
        private const val STALE_STAGE_MILLIS = 24L * 60 * 60 * 1000
        private val staleCleanupAttempted = AtomicBoolean()

        fun cleanupStaleStages(stage: File) {
            val parent = stage.parentFile ?: return
            if (parent.name != "bulk-discovery" || !staleCleanupAttempted.compareAndSet(false, true)) {
                return
            }
            val cutoff = System.currentTimeMillis() - STALE_STAGE_MILLIS
            parent.listFiles()?.filter { it.isDirectory && it.lastModified() in 1 until cutoff }
                ?.forEach(File::deleteRecursively)
        }

        const val MAX_ARCHIVE_DEPTH = 2
        const val MAX_ARCHIVE_ENTRIES = 10_000
        const val MAX_MANIFEST_BYTES = 256 * 1024L
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        val SUPPORTED_ARCHIVE_ENTRIES = setOf("jar", "jad", "kjx", "zip")
    }
}
