/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Streams an app-owned backup bundle without loading retained packages or save trees into memory. */
object LibraryAppBundleExporter {
    private const val PROVIDER_SUFFIX = ".diagnostic-files"
    private const val EXPORT_DIR = "library-export"
    private const val MIME_TYPE = "application/zip"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_FILE_STEM_LENGTH = 72

    data class Progress(
        val completedEntries: Int,
        val totalEntries: Int,
        val currentEntry: String,
        val writtenBytes: Long,
        val totalBytes: Long,
    )

    data class PreparedExport(
        val uri: Uri,
        val fileName: String,
        val mimeType: String = MIME_TYPE,
    )

    suspend fun prepare(
        context: Context,
        emulatorDir: File,
        storageKey: String,
        displayTitle: String,
        onProgress: ((Progress) -> Unit)? = null,
    ): PreparedExport = withContext(Dispatchers.IO) {
        requireSafeStorageKey(storageKey)
        val directory = File(File(context.cacheDir, EXPORT_DIR), storageKey)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create app-bundle cache directory")
        }
        val fileName = safeFileName(displayTitle)
        val target = File(directory, fileName)
        val staging = File(directory, "$fileName.tmp")
        try {
            exportToZip(emulatorDir, storageKey, staging, onProgress)
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace previous app bundle")
            }
            if (!staging.renameTo(target)) {
                throw IOException("Unable to publish app bundle")
            }
            PreparedExport(
                uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + PROVIDER_SUFFIX,
                    target,
                ),
                fileName = fileName,
            )
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    @Throws(IOException::class)
    internal fun exportToZip(
        emulatorDir: File,
        storageKey: String,
        target: File,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        requireSafeStorageKey(storageKey)
        val entries = collectEntries(emulatorDir, storageKey)
        if (entries.isEmpty()) throw IOException("No app-owned files are available to export")
        val totalBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, entry.file.length()) }
        var writtenBytes = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(target, false))).use { zip ->
            entries.forEachIndexed { index, source ->
                val zipEntry = ZipEntry(source.path)
                val modified = source.file.lastModified()
                if (modified > 0L) zipEntry.time = modified
                zip.putNextEntry(zipEntry)
                BufferedInputStream(FileInputStream(source.file)).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        writtenBytes = saturatingAdd(writtenBytes, read.toLong())
                    }
                }
                zip.closeEntry()
                onProgress?.invoke(
                    Progress(
                        completedEntries = index + 1,
                        totalEntries = entries.size,
                        currentEntry = source.path,
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
        }
    }

    internal fun safeFileName(displayTitle: String): String {
        val trimmed = displayTitle.trim()
        val normalized = buildString(trimmed.length.coerceAtMost(MAX_FILE_STEM_LENGTH)) {
            for (character in trimmed) {
                if (length >= MAX_FILE_STEM_LENGTH) break
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == ' ' || character == '-' || character == '_' ||
                            character == '(' || character == ')' || character == '[' || character == ']' -> character
                        else -> '_'
                    },
                )
            }
        }.trim(' ', '_')
        val stem = normalized.ifBlank { "J2ME-App" }
        return "$stem-JLModPlus.zip"
    }

    @Throws(IOException::class)
    private fun collectEntries(emulatorDir: File, storageKey: String): List<SourceEntry> {
        val workdirRoot = emulatorDir.canonicalFile
        val entries = ArrayList<SourceEntry>()
        val converted = File(File(emulatorDir, "converted"), storageKey)
        addIfFile(entries, workdirRoot, File(converted, "res.jar"), "app/res.jar")
        addIfFile(entries, workdirRoot, File(converted, "converted.dex.conf"), "app/converted.dex.conf")
        addIfFile(entries, workdirRoot, File(converted, "icon.png"), "app/icon.png")
        addTree(entries, workdirRoot, File(File(emulatorDir, "configs"), storageKey), "config")
        addTree(entries, workdirRoot, File(File(emulatorDir, "data"), storageKey), "data")
        return entries.sortedBy(SourceEntry::path)
    }

    @Throws(IOException::class)
    private fun addIfFile(
        entries: MutableList<SourceEntry>,
        workdirRoot: File,
        file: File,
        path: String,
    ) {
        if (!file.exists()) return
        val canonical = file.canonicalFile
        if (!insideRoot(workdirRoot, canonical)) {
            throw IOException("App-owned file resolves outside the active workdir: ${file.absolutePath}")
        }
        if (canonical.isFile) entries += SourceEntry(canonical, path)
    }

    @Throws(IOException::class)
    private fun addTree(
        entries: MutableList<SourceEntry>,
        workdirRoot: File,
        root: File,
        prefix: String,
    ) {
        if (!root.exists()) return
        val canonicalRoot = root.canonicalFile
        if (!insideRoot(workdirRoot, canonicalRoot)) {
            throw IOException("App-owned directory resolves outside the active workdir: ${root.absolutePath}")
        }
        if (!canonicalRoot.isDirectory) {
            throw IOException("App-owned path is not a directory: ${root.absolutePath}")
        }
        val visitedDirectories = HashSet<String>()
        val visitedFiles = HashSet<String>()
        walk(entries, canonicalRoot, canonicalRoot, prefix, visitedDirectories, visitedFiles)
    }

    @Throws(IOException::class)
    private fun walk(
        entries: MutableList<SourceEntry>,
        root: File,
        directory: File,
        prefix: String,
        visitedDirectories: MutableSet<String>,
        visitedFiles: MutableSet<String>,
    ) {
        val canonicalDirectory = directory.canonicalFile
        if (!insideRoot(root, canonicalDirectory)) return
        if (!visitedDirectories.add(canonicalDirectory.path)) return
        val children = canonicalDirectory.listFiles()
            ?: throw IOException("Unable to list app-owned directory: ${canonicalDirectory.absolutePath}")
        children.sortedBy(File::getName).forEach { child ->
            val canonicalChild = child.canonicalFile
            if (!insideRoot(root, canonicalChild)) return@forEach
            val relative = canonicalChild.relativeTo(root).invariantSeparatorsPath
            val zipPath = "$prefix/$relative"
            when {
                canonicalChild.isDirectory -> walk(
                    entries,
                    root,
                    canonicalChild,
                    prefix,
                    visitedDirectories,
                    visitedFiles,
                )
                canonicalChild.isFile && visitedFiles.add(canonicalChild.path) ->
                    entries += SourceEntry(canonicalChild, zipPath)
            }
        }
    }

    private fun insideRoot(root: File, candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right <= 0L) left else if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class SourceEntry(val file: File, val path: String)
}
