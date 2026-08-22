/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import ru.woesss.j2me.jar.Descriptor

/** Validates an exported JL-Mod Plus bundle and restores only authoritative app-owned state. */
object LibraryAppBundleImporter {
    private const val IMPORT_DIR = "library-import"
    private const val TRANSACTION_DIR = ".library-import-transactions"
    private const val TRANSACTION_VERSION = 1
    private const val TRANSACTION_SUFFIX = ".txn"
    private const val COMMIT_SUFFIX = ".commit"
    private const val JAR_ENTRY = "app/res.jar"
    private const val CONVERTED_CONFIG_ENTRY = "app/converted.dex.conf"
    private const val DERIVED_ICON_ENTRY = "app/icon.png"
    private const val CONFIG_PREFIX = "config/"
    private const val DATA_PREFIX = "data/"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 4L * 1024L
    private const val MAX_DESCRIPTOR_BYTES = 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
    private const val MAX_REPLACEMENTS = 3
    private const val STALE_STAGING_AGE_MILLIS = 24L * 60L * 60L * 1000L

    class PreparedImport internal constructor(
        val stagingDir: File,
        val jarFile: File,
        internal val convertedConfigFile: File?,
        internal val configDir: File?,
        internal val dataDir: File?,
        internal val formatVersion: Int,
        internal val preflightSourceMetadata: SourceMetadata? = null,
    )

    internal data class SourceMetadata(
        val title: String,
        val vendor: String,
        val version: String,
        val description: String?,
    )

    data class RestoreResult(val iconRevision: Long?)

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, source: Uri): PreparedImport {
        val importRoot = File(context.cacheDir, IMPORT_DIR)
        ensureDirectory(importRoot)
        cleanupStaleStaging(importRoot, System.currentTimeMillis())
        val staging = File(importRoot, UUID.randomUUID().toString())
        ensureDirectory(staging)
        return try {
            val parsed = openSource(context, source).use(LibraryAppBundleReader::read)
            val plan = LibraryAppBundleImportPlanner.plan(parsed)
            if (plan.isBatch) {
                throw IOException(
                    "This app bundle contains ${plan.items.size} apps; bulk import is required",
                )
            }
            val item = plan.items.single().app
            val prepared = if (parsed.formatVersion == LibraryAppBundleFormat.UNIVERSAL_VERSION) {
                openSource(context, source).use { input ->
                    extractUniversalSingleToStaging(
                        input = input,
                        staging = staging,
                        app = item,
                        parseSourceMetadata = true,
                    )
                }
            } else {
                openSource(context, source).use { input ->
                    extractToStaging(input, staging, parseSourceMetadata = true)
                }
            }
            item.sourceSha256?.let { expected ->
                LibraryAppBundleReader.verifySourceHash(prepared.jarFile, expected)
            }
            prepared
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Extracts one v2 namespace into the legacy PreparedImport shape used by the installer. */
    @Throws(IOException::class)
    internal fun extractUniversalSingleToStaging(
        input: InputStream,
        staging: File,
        app: BundleApp,
        parseSourceMetadata: Boolean = false,
    ): PreparedImport {
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
        var hasJar = false
        var hasConfig = false
        var hasData = false
        var hasConvertedConfig = false

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw IOException("App bundle contains too many entries")
                val name = normalizeUniversalEntryName(entry.name)
                if (!seen.add(name)) throw IOException("Duplicate app bundle entry: $name")
                if (name == LibraryAppBundleFormat.MANIFEST_ENTRY) {
                    zip.closeEntry()
                    continue
                }
                if (!name.startsWith(app.payloadRoot)) {
                    throw IOException("Universal app bundle contains an unexpected namespace")
                }
                val relative = name.removePrefix(app.payloadRoot)
                if (relative.isEmpty()) {
                    zip.closeEntry()
                    continue
                }
                val target = when {
                    relative == "app/res.jar" -> {
                        hasJar = true
                        jarFile
                    }
                    relative == "app/converted.dex.conf" -> {
                        hasConvertedConfig = true
                        convertedConfigFile
                    }
                    relative == "app/icon.png" -> null
                    relative == "config/" -> {
                        hasConfig = true
                        ensureDirectory(configDir)
                        null
                    }
                    relative.startsWith("config/") -> {
                        hasConfig = true
                        safeChild(configDir, relative.removePrefix("config/"))
                    }
                    relative == "data/" -> {
                        hasData = true
                        ensureDirectory(dataDir)
                        null
                    }
                    relative.startsWith("data/") -> {
                        hasData = true
                        safeChild(dataDir, relative.removePrefix("data/"))
                    }
                    else -> throw IOException("Unsupported app bundle entry: $name")
                }
                if (entry.isDirectory) {
                    target?.let(::ensureDirectory)
                    zip.closeEntry()
                    continue
                }

                var entryBytes = 0L
                val entryLimit = when (relative) {
                    "app/converted.dex.conf" -> MAX_DESCRIPTOR_BYTES
                    else -> MAX_ENTRY_BYTES
                }
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
                        if (entryBytes > entryLimit || totalBytes > MAX_TOTAL_BYTES) {
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

        if (!hasJar || !jarFile.isFile || jarFile.length() <= 0L) {
            throw IOException("App bundle does not contain a retained JAR")
        }
        val sourceMetadata = if (parseSourceMetadata && hasConvertedConfig) {
            readSourceMetadataFile(convertedConfigFile)
        } else {
            null
        }
        return PreparedImport(
            stagingDir = canonicalRoot,
            jarFile = jarFile,
            convertedConfigFile = convertedConfigFile.takeIf { hasConvertedConfig && it.isFile },
            configDir = configDir.takeIf { hasConfig && it.isDirectory },
            dataDir = dataDir.takeIf { hasData && it.isDirectory },
            formatVersion = LibraryAppBundleFormat.UNIVERSAL_VERSION,
            preflightSourceMetadata = sourceMetadata,
        )
    }

    @Throws(IOException::class)
    internal fun extractToStaging(
        input: InputStream,
        staging: File,
        parseSourceMetadata: Boolean = false,
    ): PreparedImport {
        ensureDirectory(staging)
        val canonicalRoot = staging.canonicalFile
        val manifestFile = File(canonicalRoot, "bundle.json")
        val jarFile = File(canonicalRoot, "res.jar")
        val convertedConfigFile = File(canonicalRoot, "converted.dex.conf")
        val configDir = File(canonicalRoot, "config")
        val dataDir = File(canonicalRoot, "data")
        val seen = HashSet<String>()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var entryCount = 0
        var totalBytes = 0L
        var hasManifest = false
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
                    name == LibraryAppBundleFormat.MANIFEST_ENTRY -> {
                        hasManifest = true
                        manifestFile
                    }
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
                val entryLimit = when (name) {
                    LibraryAppBundleFormat.MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    CONVERTED_CONFIG_ENTRY -> MAX_DESCRIPTOR_BYTES
                    else -> MAX_ENTRY_BYTES
                }
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
                        if (entryBytes > entryLimit || totalBytes > MAX_TOTAL_BYTES) {
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
        val formatVersion = if (hasManifest) {
            readAndValidateFormatVersion(manifestFile)
        } else {
            // PR2 preview builds exported the same payload before an explicit manifest existed.
            // Keep those bundles readable while every new export writes a versioned manifest.
            0
        }
        val sourceMetadata = if (parseSourceMetadata && hasConvertedConfig) {
            readSourceMetadataFile(convertedConfigFile)
        } else {
            null
        }
        return PreparedImport(
            stagingDir = canonicalRoot,
            jarFile = jarFile,
            convertedConfigFile = convertedConfigFile.takeIf { hasConvertedConfig && it.isFile },
            configDir = configDir.takeIf { hasConfig && it.isDirectory },
            dataDir = dataDir.takeIf { hasData && it.isDirectory },
            formatVersion = formatVersion,
            preflightSourceMetadata = sourceMetadata,
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateSourceIdentity(prepared: PreparedImport, title: String, vendor: String) {
        val metadata = prepared.preflightSourceMetadata ?: return
        if (metadata.title != title || metadata.vendor != vendor) {
            throw IOException("App bundle descriptor identity does not match the retained JAR")
        }
    }

    @Throws(IOException::class)
    internal fun readSourceMetadata(prepared: PreparedImport): SourceMetadata? {
        verifyPrepared(prepared)
        val file = prepared.convertedConfigFile ?: return null
        return readSourceMetadataFile(file)
    }

    @Throws(IOException::class)
    private fun readSourceMetadataFile(file: File): SourceMetadata {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_DESCRIPTOR_BYTES) {
            throw IOException("Invalid imported source descriptor")
        }
        val descriptor = Descriptor(file, false)
        return SourceMetadata(
            title = descriptor.name,
            vendor = descriptor.vendor,
            version = descriptor.version,
            description = descriptor.attrs[Descriptor.MIDLET_DESCRIPTION],
        )
    }

    /**
     * Replaces only namespaces that were present in the bundle. Publication is guarded by a durable
     * transaction marker. If the process dies before commit, the next Library startup restores the
     * old namespaces; if it dies after commit, startup only finishes cleanup of the new state.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun restore(prepared: PreparedImport, emulatorDir: File, storageKey: String): RestoreResult =
        restoreInternal(prepared, emulatorDir, storageKey, null)

    @Throws(IOException::class)
    internal fun restoreWithPublishHook(
        prepared: PreparedImport,
        emulatorDir: File,
        storageKey: String,
        afterPublished: (Int) -> Unit,
    ): RestoreResult = restoreInternal(prepared, emulatorDir, storageKey, afterPublished)

    @JvmStatic
    @Throws(IOException::class)
    internal fun recoverInterruptedRestores(emulatorDir: File) {
        val root = emulatorDir.canonicalFile
        val transactionRoot = transactionRoot(root)
        if (!transactionRoot.exists()) return
        if (!transactionRoot.isDirectory) {
            throw IOException("Library import transaction path is not a directory")
        }
        val markers = transactionRoot.listFiles()
            ?: throw IOException("Unable to inspect Library import recovery state")
        markers
            .filter { it.isFile && it.name.endsWith(TRANSACTION_SUFFIX) }
            .sortedBy(File::getName)
            .forEach { marker ->
                val transaction = readTransaction(root, marker)
                if (transaction.commitMarker.isFile) {
                    finalizeCommittedTransaction(transaction)
                } else {
                    rollbackTransaction(transaction, root)
                }
            }
        transactionRoot.listFiles()?.forEach { orphan ->
            if (orphan.isFile && orphan.name.endsWith(COMMIT_SUFFIX)) orphan.delete()
        }
        transactionRoot.delete()
    }

    @JvmStatic
    fun cleanup(prepared: PreparedImport?) {
        prepared?.stagingDir?.deleteRecursively()
    }

    internal fun cleanupStaleStaging(
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
            if (modified <= 0L || nowMillis < modified || nowMillis - modified < maxAgeMillis) return@forEach
            if (candidate.deleteRecursively()) removed++
        }
        return removed
    }

    private fun restoreInternal(
        prepared: PreparedImport,
        emulatorDir: File,
        storageKey: String,
        afterPublished: ((Int) -> Unit)?,
    ): RestoreResult {
        requireSafeStorageKey(storageKey)
        verifyPrepared(prepared)
        recoverInterruptedRestores(emulatorDir)
        val root = emulatorDir.canonicalFile
        val transactionId = UUID.randomUUID().toString()
        val replacements = ArrayList<Replacement>(MAX_REPLACEMENTS)
        prepared.configDir?.let { source ->
            replacements += prepareReplacement(
                source = source,
                sourceIsDirectory = true,
                root = root,
                storageKey = storageKey,
                target = File(File(root, "configs"), storageKey),
                transactionId = transactionId,
            )
        }
        prepared.dataDir?.let { source ->
            replacements += prepareReplacement(
                source = source,
                sourceIsDirectory = true,
                root = root,
                storageKey = storageKey,
                target = File(File(root, "data"), storageKey),
                transactionId = transactionId,
            )
        }
        prepared.convertedConfigFile?.let { source ->
            replacements += prepareReplacement(
                source = source,
                sourceIsDirectory = false,
                root = root,
                storageKey = storageKey,
                target = File(File(File(root, "converted"), storageKey), "converted.dex.conf"),
                transactionId = transactionId,
            )
        }
        if (replacements.isEmpty()) return RestoreResult(null)

        val transactionRoot = transactionRoot(root)
        ensureDirectory(transactionRoot)
        val transaction = RestoreTransaction(
            storageKey = storageKey,
            syncIcon = prepared.configDir != null,
            marker = File(transactionRoot, "$transactionId$TRANSACTION_SUFFIX"),
            commitMarker = File(transactionRoot, "$transactionId$COMMIT_SUFFIX"),
            replacements = replacements,
        )
        // Publish the durable marker before copying any replacement into the workdir. Recovery can
        // now remove a partially staged namespace even if the process dies during the copy itself.
        writeTransaction(transaction)
        try {
            replacements.forEach(::stageReplacement)
            publishReplacements(replacements, afterPublished)
            val iconRevision = if (prepared.configDir != null) {
                LibraryIconOverride.reapplyPersistedOverride(root, storageKey)
            } else {
                null
            }
            writeCommitMarker(transaction.commitMarker)
            finalizeCommittedTransaction(transaction)
            return RestoreResult(iconRevision)
        } catch (error: Throwable) {
            if (!transaction.commitMarker.exists()) {
                try {
                    rollbackTransaction(transaction, root)
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
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
    private fun prepareReplacement(
        source: File,
        sourceIsDirectory: Boolean,
        root: File,
        storageKey: String,
        target: File,
        transactionId: String,
    ): Replacement {
        if (sourceIsDirectory) {
            if (!source.isDirectory) throw IOException("Import source directory is unavailable: ${source.path}")
        } else if (!source.isFile) {
            throw IOException("Import source file is unavailable: ${source.path}")
        }
        val canonicalTarget = target.canonicalFile
        if (!isAllowedTransactionTarget(root, storageKey, canonicalTarget)) {
            throw IOException("Import target resolves outside its allowed workdir namespace")
        }
        val parent = canonicalTarget.parentFile ?: throw IOException("Import target has no parent")
        ensureDirectory(parent)
        val staged = File(parent, ".${canonicalTarget.name}.$transactionId.import.tmp")
        val backup = File(parent, ".${canonicalTarget.name}.$transactionId.import.bak")
        return Replacement(
            target = canonicalTarget,
            staged = staged,
            backup = backup,
            hadOriginal = canonicalTarget.exists(),
            source = source.canonicalFile,
            sourceIsDirectory = sourceIsDirectory,
        )
    }

    @Throws(IOException::class)
    private fun stageReplacement(replacement: Replacement) {
        val source = replacement.source ?: throw IOException("Import replacement has no source")
        deleteIfExists(replacement.staged, "stale import staging")
        deleteIfExists(replacement.backup, "stale import backup")
        if (replacement.sourceIsDirectory) {
            if (!source.copyRecursively(replacement.staged, overwrite = false)) {
                replacement.staged.deleteRecursively()
                throw IOException("Unable to stage imported directory: ${replacement.target.path}")
            }
            return
        }
        source.inputStream().use { input ->
            FileOutputStream(replacement.staged).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
    }

    @Throws(IOException::class)
    private fun publishReplacements(
        replacements: List<Replacement>,
        afterPublished: ((Int) -> Unit)?,
    ) {
        replacements.forEachIndexed { index, replacement ->
            if (replacement.hadOriginal) {
                if (!replacement.target.exists()) {
                    throw IOException("Import target disappeared before publish: ${replacement.target.path}")
                }
                if (!replacement.target.renameTo(replacement.backup)) {
                    throw IOException("Unable to preserve existing app data: ${replacement.target.path}")
                }
            } else if (replacement.target.exists()) {
                throw IOException("Import target appeared unexpectedly: ${replacement.target.path}")
            }
            if (!replacement.staged.renameTo(replacement.target)) {
                throw IOException("Unable to publish imported app data: ${replacement.target.path}")
            }
            afterPublished?.invoke(index + 1)
        }
    }

    @Throws(IOException::class)
    private fun rollbackTransaction(transaction: RestoreTransaction, emulatorDir: File) {
        transaction.replacements.asReversed().forEach(::rollbackReplacement)
        if (transaction.syncIcon) {
            LibraryIconOverride.reapplyPersistedOverride(emulatorDir, transaction.storageKey)
        }
        deleteIfExists(transaction.marker, "completed rollback marker")
        deleteIfExists(transaction.commitMarker, "completed rollback commit marker")
        transaction.marker.parentFile?.delete()
    }

    @Throws(IOException::class)
    private fun rollbackReplacement(replacement: Replacement) {
        if (replacement.hadOriginal) {
            if (replacement.backup.exists()) {
                deleteIfExists(replacement.target, "partially imported target")
                if (!replacement.backup.renameTo(replacement.target)) {
                    throw IOException("Unable to restore import backup: ${replacement.target.path}")
                }
            } else if (!replacement.target.exists()) {
                throw IOException("Import rollback lost original target: ${replacement.target.path}")
            }
        } else {
            deleteIfExists(replacement.target, "partially imported target")
        }
        deleteIfExists(replacement.staged, "unused import staging")
        if (replacement.backup.exists()) {
            throw IOException("Import rollback left an unexpected backup: ${replacement.backup.path}")
        }
    }

    @Throws(IOException::class)
    private fun finalizeCommittedTransaction(transaction: RestoreTransaction) {
        transaction.replacements.forEach { replacement ->
            deleteIfExists(replacement.backup, "committed import backup")
            deleteIfExists(replacement.staged, "committed import staging")
        }
        deleteIfExists(transaction.marker, "committed import transaction marker")
        deleteIfExists(transaction.commitMarker, "committed import marker")
        transaction.marker.parentFile?.delete()
    }

    @Throws(IOException::class)
    private fun writeTransaction(transaction: RestoreTransaction) {
        val properties = Properties().apply {
            setProperty("version", TRANSACTION_VERSION.toString())
            setProperty("storageKey", transaction.storageKey)
            setProperty("syncIcon", transaction.syncIcon.toString())
            setProperty("count", transaction.replacements.size.toString())
            transaction.replacements.forEachIndexed { index, replacement ->
                setProperty("target.$index", replacement.target.absolutePath)
                setProperty("staged.$index", replacement.staged.absolutePath)
                setProperty("backup.$index", replacement.backup.absolutePath)
                setProperty("hadOriginal.$index", replacement.hadOriginal.toString())
            }
        }
        writeAtomically(transaction.marker) { output ->
            properties.store(output, null)
        }
    }

    @Throws(IOException::class)
    private fun writeCommitMarker(marker: File) {
        writeAtomically(marker) { output ->
            output.write('1'.code)
        }
    }

    @Throws(IOException::class)
    private fun readTransaction(root: File, marker: File): RestoreTransaction {
        val properties = Properties()
        marker.inputStream().use { input -> properties.load(input) }
        val version = properties.getProperty("version")?.toIntOrNull()
            ?: throw IOException("Invalid Library import transaction version")
        if (version != TRANSACTION_VERSION) {
            throw IOException("Unsupported Library import transaction version: $version")
        }
        val storageKey = properties.getProperty("storageKey")
            ?: throw IOException("Library import transaction is missing storageKey")
        requireSafeStorageKey(storageKey)
        val syncIcon = properties.getProperty("syncIcon")?.toBooleanStrictOrNull()
            ?: throw IOException("Invalid Library import icon-sync marker")
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: throw IOException("Library import transaction is missing replacement count")
        if (count !in 1..MAX_REPLACEMENTS) {
            throw IOException("Invalid Library import replacement count: $count")
        }
        val baseName = marker.name.removeSuffix(TRANSACTION_SUFFIX)
        if (baseName.isBlank() || baseName.contains('/') || baseName.contains('\\')) {
            throw IOException("Invalid Library import transaction id")
        }
        val replacements = ArrayList<Replacement>(count)
        val seenTargets = HashSet<String>(count)
        repeat(count) { index ->
            val target = transactionPath(root, properties, "target.$index")
            val staged = transactionPath(root, properties, "staged.$index")
            val backup = transactionPath(root, properties, "backup.$index")
            if (!isAllowedTransactionTarget(root, storageKey, target)) {
                throw IOException("Library import transaction targets an unsupported path")
            }
            if (!seenTargets.add(target.path)) {
                throw IOException("Library import transaction contains a duplicate target")
            }
            val parent = target.parentFile
                ?: throw IOException("Library import transaction target has no parent")
            val expectedStaged = File(parent, ".${target.name}.$baseName.import.tmp").canonicalFile
            val expectedBackup = File(parent, ".${target.name}.$baseName.import.bak").canonicalFile
            if (staged != expectedStaged || backup != expectedBackup) {
                throw IOException("Library import transaction staging paths are invalid")
            }
            val hadOriginal = properties.getProperty("hadOriginal.$index")?.toBooleanStrictOrNull()
                ?: throw IOException("Invalid Library import original-state marker")
            replacements += Replacement(target, staged, backup, hadOriginal)
        }
        return RestoreTransaction(
            storageKey = storageKey,
            syncIcon = syncIcon,
            marker = marker.canonicalFile,
            commitMarker = File(marker.parentFile, "$baseName$COMMIT_SUFFIX").canonicalFile,
            replacements = replacements,
        )
    }

    private fun transactionPath(root: File, properties: Properties, key: String): File {
        val raw = properties.getProperty(key) ?: throw IOException("Library import transaction is missing $key")
        val candidate = File(raw).canonicalFile
        if (!insideRoot(root, candidate)) throw IOException("Library import transaction escaped the workdir")
        return candidate
    }

    private fun isAllowedTransactionTarget(root: File, storageKey: String, target: File): Boolean {
        if (!insideRoot(root, target)) return false
        val allowed = listOf(
            File(File(root, "configs"), storageKey).canonicalFile,
            File(File(root, "data"), storageKey).canonicalFile,
            File(File(File(root, "converted"), storageKey), "converted.dex.conf").canonicalFile,
        )
        return target in allowed
    }

    private fun transactionRoot(root: File): File {
        val candidate = File(root, TRANSACTION_DIR).canonicalFile
        if (candidate.parentFile != root) {
            throw IOException("Library import transaction directory escaped the workdir")
        }
        return candidate
    }

    @Throws(IOException::class)
    private fun writeAtomically(target: File, writer: (FileOutputStream) -> Unit) {
        val parent = target.parentFile ?: throw IOException("Library import marker has no parent")
        ensureDirectory(parent)
        val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp, false).use { output ->
                writer(output)
                output.fd.sync()
            }
            if (target.exists()) {
                throw IOException("Library import marker already exists: ${target.path}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Unable to publish Library import marker: ${target.path}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun readAndValidateFormatVersion(manifestFile: File): Int {
        if (!manifestFile.isFile || manifestFile.length() <= 0L || manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw IOException("Invalid app bundle manifest")
        }
        val version = try {
            manifestFile.reader(Charsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                root.get("formatVersion")?.asInt
            }
        } catch (error: RuntimeException) {
            throw IOException("Invalid app bundle manifest", error)
        } ?: throw IOException("App bundle manifest is missing formatVersion")
        if (version != LibraryAppBundleFormat.CURRENT_VERSION) {
            throw IOException("Unsupported app bundle format version: $version")
        }
        return version
    }

    private fun openSource(context: Context, source: Uri): InputStream = try {
        context.contentResolver.openInputStream(source)
    } catch (error: SecurityException) {
        throw IOException("Selected app bundle is no longer readable", error)
    } ?: throw IOException("Unable to open selected app bundle")

    private fun normalizeUniversalEntryName(raw: String): String {
        if (raw.isBlank() || raw.contains('\u0000') || raw.startsWith('/') ||
            raw.startsWith('\\') || raw.contains('\\')
        ) throw IOException("Unsafe app bundle entry: $raw")
        val normalized = raw.removeSuffix("/")
        if (normalized.isBlank()) throw IOException("Unsafe app bundle entry: $raw")
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Unsafe app bundle entry: $raw")
        }
        return if (raw.endsWith('/')) "$normalized/" else normalized
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

    private fun deleteIfExists(path: File, description: String) {
        if (path.exists() && !path.deleteRecursively()) {
            throw IOException("Unable to remove $description: ${path.path}")
        }
    }

    private fun isUuidName(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
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
        val backup: File,
        val hadOriginal: Boolean,
        val source: File? = null,
        val sourceIsDirectory: Boolean = false,
    )

    private data class RestoreTransaction(
        val storageKey: String,
        val syncIcon: Boolean,
        val marker: File,
        val commitMarker: File,
        val replacements: List<Replacement>,
    )
}
