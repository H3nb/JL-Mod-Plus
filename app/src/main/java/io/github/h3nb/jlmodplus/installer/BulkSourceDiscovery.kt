/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
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
            children.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
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

    private fun inspectArchive(source: File, depth: Int) {
        checkActive()
        try {
            ZipFile(source).use { archive ->
                if (containsMidletManifest(archive)) {
                    val target = stagedFile("archive-${archiveSequence++}", source.nameWithoutExtension + ".jar")
                    copyBounded(source, target)
                    files.add(target)
                    return
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
                    if (extension == "zip" && depth >= MAX_ARCHIVE_DEPTH) {
                        skippedEntries++
                        continue
                    }
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
                            inspectArchive(destination, depth + 1)
                            destination.delete()
                        } else {
                            files.add(destination)
                        }
                    } catch (cancelled: CancellationException) {
                        destination.delete()
                        throw cancelled
                    } catch (_: IOException) {
                        destination.delete()
                        skippedEntries++
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            unreadableArchives++
        }
    }

    private fun containsMidletManifest(archive: ZipFile): Boolean {
        val entry = archive.getEntry("META-INF/MANIFEST.MF") ?: return false
        if (entry.size > MAX_MANIFEST_BYTES) return false
        return try {
            archive.getInputStream(entry).use { input ->
                val manifest = Manifest(input).mainAttributes
                !manifest.getValue("MIDlet-Name").isNullOrBlank() &&
                    !manifest.getValue("MIDlet-Vendor").isNullOrBlank() &&
                    !manifest.getValue("MIDlet-Version").isNullOrBlank() &&
                    !manifest.getValue("MIDlet-1").isNullOrBlank()
            }
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

    private fun copyBounded(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> copyBounded(input, output) }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
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
        const val MAX_ARCHIVE_DEPTH = 2
        const val MAX_ARCHIVE_ENTRIES = 10_000
        const val MAX_MANIFEST_BYTES = 256 * 1024L
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        val SUPPORTED_ARCHIVE_ENTRIES = setOf("jar", "jad", "kjx", "zip")
    }
}
