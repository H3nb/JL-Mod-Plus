/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Validates an exported JL-Mod Plus bundle and restores only authoritative app-owned state. */
object LibraryAppBundleImporter {
    private const val IMPORT_DIR = "library-import"
    private const val JAR_ENTRY = "app/res.jar"
    private const val CONVERTED_CONFIG_ENTRY = "app/converted.dex.conf"
    private const val DERIVED_ICON_ENTRY = "app/icon.png"
    private const val CONFIG_PREFIX = "config/"
    private const val DATA_PREFIX = "data/"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L

    data class PreparedImport internal constructor(
        val stagingDir: File,
        val jarFile: File,
        internal val convertedConfigFile: File?,
        internal val configDir: File?,
        internal val dataDir: File?,
    )

    data class RestoreResult(val iconRevision: Long?)

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, source: Uri): PreparedImport {
        val importRoot = File(context.cacheDir, IMPORT_DIR)
        ensureDirectory(importRoot)
        val staging = File(importRoot, UUID.randomUUID().toString())
        ensureDirectory(staging)
        return try {
            val input = try {
                context.contentResolver.openInputStream(source)
            } catch (error: SecurityException) {
                throw IOException("Selected app bundle is no longer readable", error)
            } ?: throw IOException("Unable to open selected app bundle")
            input.use { extractToStaging(it, staging) }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Throws(IOException::class)
    internal fun extractToStaging(input: InputStream, staging: File): PreparedImport {
        ensureDirectory(staging)
        val canonicalRoot = staging.canonicalFile
        val jarFile = File(canonicalRoot, "res.jar")
        val convertedConfigFile = File(canonicalRoot, "converted.dex.conf")
        val configDir = File(canonicalRoot, "config")
        val dataDir = File(canonicalRoot, "data")
        val seen = HashSet<String>()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var entryCount = 0
        var totalBytes = 0L
        var hasConfig = false
        var hasData = false
        var hasConvertedConfig = false

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw IOException("App bundle contains too many entries")
                val name = normalizeEntryName(entry.name)
                if (!seen.add(name)) throw IOException("Duplicate app bundle entry: $name")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val target = when {
                    name == JAR_ENTRY -> jarFile
                    name == CONVERTED_CONFIG_ENTRY -> {
                        hasConvertedConfig = true
                        convertedConfigFile
                    }
                    name == DERIVED_ICON_ENTRY -> null
                    name.startsWith(CONFIG_PREFIX) -> {
                        val relative = name.removePrefix(CONFIG_PREFIX)
                        if (relative.isEmpty()) throw IOException("Invalid config entry")
                        hasConfig = true
                        safeChild(configDir, relative)
                    }
                    name.startsWith(DATA_PREFIX) -> {
                        val relative = name.removePrefix(DATA_PREFIX)
                        if (relative.isEmpty()) throw IOException("Invalid data entry")
                        hasData = true
                        safeChild(dataDir, relative)
                    }
                    else -> throw IOException("Unsupported app bundle entry: $name")
                }

                var entryBytes = 0L
                val output = target?.let {
                    ensureDirectory(it.parentFile ?: throw IOException("Bundle entry has no parent"))
                    BufferedOutputStream(FileOutputStream(it, false))
                }
                try {
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw IOException("App bundle is too large to import safely")
                        }
                        output?.write(buffer, 0, read)
                    }
                    output?.flush()
                } finally {
                    output?.close()
                }
                zip.closeEntry()
            }
        }

        if (!jarFile.isFile || jarFile.length() <= 0L) {
            throw IOException("App bundle does not contain a retained JAR")
        }
        return PreparedImport(
            stagingDir = canonicalRoot,
            jarFile = jarFile,
            convertedConfigFile = convertedConfigFile.takeIf { hasConvertedConfig && it.isFile },
            configDir = configDir.takeIf { hasConfig && it.isDirectory },
            dataDir = dataDir.takeIf { hasData && it.isDirectory },
        )
    }

    /**
     * Replaces only namespaces that were present in the bundle. All replacements are staged first,
     * then published with same-parent backups so a failure can roll every already-published target back.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun restore(prepared: PreparedImport, emulatorDir: File, storageKey: String): RestoreResult {
        requireSafeStorageKey(storageKey)
        verifyPrepared(prepared)
        val replacements = ArrayList<Replacement>(3)
        prepared.configDir?.let { source ->
            replacements += stageDirectory(source, File(File(emulatorDir, "configs"), storageKey))
        }
        prepared.dataDir?.let { source ->
            replacements += stageDirectory(source, File(File(emulatorDir, "data"), storageKey))
        }
        prepared.convertedConfigFile?.let { source ->
            replacements += stageFile(
                source,
                File(File(File(emulatorDir, "converted"), storageKey), "converted.dex.conf"),
            )
        }

        publishReplacements(replacements)
        val iconRevision = LibraryIconOverride.reapplyPersistedOverride(emulatorDir, storageKey)
        return RestoreResult(iconRevision)
    }

    @JvmStatic
    fun cleanup(prepared: PreparedImport?) {
        prepared?.stagingDir?.deleteRecursively()
    }

    private fun verifyPrepared(prepared: PreparedImport) {
        val root = prepared.stagingDir.canonicalFile
        check(root.isDirectory) { "Import staging directory is unavailable" }
        check(insideRoot(root, prepared.jarFile.canonicalFile) && prepared.jarFile.isFile) {
            "Prepared import JAR is unavailable"
        }
        listOfNotNull(prepared.convertedConfigFile, prepared.configDir, prepared.dataDir).forEach { candidate ->
            check(insideRoot(root, candidate.canonicalFile)) { "Prepared import escaped its staging directory" }
        }
    }

    @Throws(IOException::class)
    private fun stageDirectory(source: File, target: File): Replacement {
        if (!source.isDirectory) throw IOException("Import source directory is unavailable: ${source.path}")
        val parent = target.parentFile ?: throw IOException("Import target has no parent")
        ensureDirectory(parent)
        val staged = File(parent, ".${target.name}.${UUID.randomUUID()}.import.tmp")
        if (!source.copyRecursively(staged, overwrite = false)) {
            staged.deleteRecursively()
            throw IOException("Unable to stage imported directory: ${target.path}")
        }
        return Replacement(target, staged)
    }

    @Throws(IOException::class)
    private fun stageFile(source: File, target: File): Replacement {
        if (!source.isFile) throw IOException("Import source file is unavailable: ${source.path}")
        val parent = target.parentFile ?: throw IOException("Import target has no parent")
        ensureDirectory(parent)
        val staged = File(parent, ".${target.name}.${UUID.randomUUID()}.import.tmp")
        source.inputStream().use { input ->
            FileOutputStream(staged).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        return Replacement(target, staged)
    }

    @Throws(IOException::class)
    private fun publishReplacements(replacements: List<Replacement>) {
        try {
            replacements.forEach { replacement ->
                val target = replacement.target
                if (target.exists()) {
                    val parent = target.parentFile ?: throw IOException("Import target has no parent")
                    val backup = File(parent, ".${target.name}.${UUID.randomUUID()}.import.bak")
                    if (!target.renameTo(backup)) {
                        throw IOException("Unable to preserve existing app data: ${target.path}")
                    }
                    replacement.backup = backup
                }
                if (!replacement.staged.renameTo(target)) {
                    throw IOException("Unable to publish imported app data: ${target.path}")
                }
                replacement.published = true
            }
        } catch (error: Throwable) {
            replacements.asReversed().forEach(::rollbackReplacement)
            throw error
        }
        replacements.forEach { replacement -> replacement.backup?.deleteRecursively() }
    }

    private fun rollbackReplacement(replacement: Replacement) {
        if (replacement.published && replacement.target.exists()) {
            replacement.target.deleteRecursively()
        }
        replacement.backup?.takeIf(File::exists)?.renameTo(replacement.target)
        replacement.staged.deleteRecursively()
    }

    private fun normalizeEntryName(raw: String): String {
        if (raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') || raw.contains('\\')) {
            throw IOException("Unsafe app bundle entry: $raw")
        }
        val parts = raw.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Unsafe app bundle entry: $raw")
        }
        return parts.joinToString("/")
    }

    private fun safeChild(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, relative).canonicalFile
        if (!insideRoot(canonicalRoot, child)) throw IOException("Bundle entry resolves outside its namespace")
        return child
    }

    private fun insideRoot(root: File, candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create directory: ${directory.path}")
        }
    }

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
    }

    private data class Replacement(
        val target: File,
        val staged: File,
        var backup: File? = null,
        var published: Boolean = false,
    )
}
