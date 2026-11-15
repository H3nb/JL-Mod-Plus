package io.github.h3nb.jlmodplus.config

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Portable one-set ZIP format: <set-name>/skin.json and its original artwork files. */
internal object ControlSkinArchive {
    private const val BUFFER_SIZE = 8192
    private val MAX_ENTRIES = ControlSkinStore.KEYS.size * 2 +
        ControlSkinStore.GROUPED_ASSETS.size + 2 // Metadata and optional root directory.

    data class ImportResult(val id: String, val name: String, val added: Boolean)

    fun export(workdir: File, setId: String, destination: File): File =
        synchronized(ControlSkinStore::class.java) {
            val controlRoot = ControlSkinStore.directory(workdir).canonicalFile
            val exportFolder = destination.canonicalFile
            if (exportFolder == controlRoot ||
                exportFolder.path.startsWith(controlRoot.path + File.separator)) {
                throw IOException("Choose a folder outside the control skin library")
            }
            val set = ControlSkinStore.find(workdir, setId)
                ?: throw IOException("Control skin set no longer exists")
            val entries = ControlSkinSetTransfer.files(set.folder)
            checkSlotFormats(entries)
            val metadata = normalizedMetadata(File(set.folder, "skin.json"))
            entries.filterNot { it.name == "skin.json" }.forEach {
                ControlSkinStore.validateImage(it, "." + it.extension)
            }
            if (!destination.isDirectory || !destination.canWrite()) {
                throw IOException("Export folder is not writable")
            }
            val temporary = File.createTempFile(".control-skin-", ".tmp", destination)
            try {
                FileOutputStream(temporary).use { output ->
                    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                        for (source in entries) {
                            if (Thread.currentThread().isInterrupted) {
                                throw IOException("Control skin export was interrupted")
                            }
                            zip.putNextEntry(ZipEntry(set.name + "/" + source.name))
                            if (source.name == "skin.json") {
                                zip.write(metadata)
                            } else {
                                FileInputStream(source).buffered().use { input ->
                                    input.copyTo(zip, BUFFER_SIZE)
                                }
                            }
                            zip.closeEntry()
                        }
                        zip.finish()
                        zip.flush()
                        output.fd.sync()
                    }
                }
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Control skin export was interrupted")
                }
                publishUnique(temporary, destination, set.name)
            } finally {
                temporary.delete()
            }
        }

    fun importZip(workdir: File, archive: File, stagingParent: File): ImportResult {
        if (!archive.isFile) throw IOException("Selected ZIP is unavailable")
        val stage = File(stagingParent, ".control-skin-" + UUID.randomUUID())
        if (!stage.mkdir()) throw IOException("Cannot stage control skin ZIP")
        try {
            val folder = extract(archive, stage)
            val metadata = File(folder, "skin.json")
            val normalized = normalizedMetadata(metadata)
            metadata.writeBytes(normalized)
            val entries = ControlSkinSetTransfer.files(folder)
            checkSlotFormats(entries)
            entries.filterNot { it.name == "skin.json" }.forEach { file ->
                ControlSkinStore.validateImage(file, "." + file.extension)
            }

            return synchronized(ControlSkinStore::class.java) {
                val previous = ControlSkinStore.list(workdir).map { it.id }.toSet()
                val id = ControlSkinSetTransfer.install(workdir, folder, folder.name)
                val set = ControlSkinStore.find(workdir, id)
                    ?: throw IOException("Imported control skin is unavailable")
                ImportResult(id, set.name, id !in previous)
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun extract(archive: File, stage: File): File {
        var folder: File? = null
        val seen = HashSet<String>()
        var entries = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Control skin import was interrupted")
                }
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw IOException("Control skin ZIP has too many entries")
                val path = entry.name
                if (path.startsWith('/') || 92.toChar() in path || 0.toChar() in path) {
                    throw IOException("Invalid ZIP entry path")
                }
                val root = path.substringBefore('/')
                if (!ControlSkinSetTransfer.validSetName(root) ||
                    !path.startsWith(root + "/") ||
                    (folder != null && folder.name != root)) {
                    throw IOException("ZIP must contain one control skin folder")
                }
                val setFolder = folder ?: File(stage, root).also {
                    if (!it.mkdir()) throw IOException("Cannot create ZIP staging folder")
                    folder = it
                }
                val leaf = path.removePrefix(root + "/")
                if (entry.isDirectory) {
                    if (leaf.isNotEmpty()) throw IOException("Nested ZIP folders are unsupported")
                } else {
                    if (leaf.isEmpty() || '/' in leaf ||
                        !ControlSkinSetTransfer.validAssetName(leaf) ||
                        (leaf != "skin.json" && leaf.substringAfterLast('.') !in
                            setOf("png", "webp", "svg"))) {
                        throw IOException("Unexpected control skin ZIP entry")
                    }
                    if (!seen.add(leaf.lowercase(Locale.ROOT))) {
                        throw IOException("Duplicate ZIP entry")
                    }
                    val limit = if (leaf == "skin.json") ControlSkinSetTransfer.MAX_METADATA_BYTES
                        else ControlSkinSetTransfer.MAX_ASSET_BYTES
                    val output = File(setFolder, leaf)
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw IOException("Control skin import was interrupted")
                            }
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > limit) throw IOException("Control skin ZIP entry is too large")
                            stream.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return folder ?: throw IOException("Control skin ZIP is empty")
    }

    private fun normalizedMetadata(file: File): ByteArray {
        val json = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (error: Exception) {
            throw IOException("Invalid control skin metadata", error)
        }
        val version = json["version"]
        if (version != null && (version as? JsonPrimitive)?.intOrNull != 1) {
            throw IOException("Unsupported control skin ZIP version")
        }
        val bytes = (JsonObject(json + ("version" to JsonPrimitive(1))).toString() + "\n")
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > ControlSkinSetTransfer.MAX_METADATA_BYTES) {
            throw IOException("Control skin metadata is too large")
        }
        return bytes
    }

    private fun checkSlotFormats(files: List<File>) {
        val stems = HashSet<String>()
        for (file in files) {
            if (file.name == "skin.json") continue
            if (file.extension !in setOf("png", "webp", "svg")) {
                throw IOException("Unsupported control skin image format")
            }
            if (!stems.add(file.name.substringBeforeLast('.'))) {
                throw IOException("Multiple formats for one control skin slot")
            }
        }
    }

    private fun publishUnique(source: File, directory: File, name: String): File {
        var index = 1
        while (true) {
            val stem = if (index == 1) name else name + " (" + index + ")"
            index++
            val target = File(directory, stem + ".zip")
            if (directory.listFiles()?.any { it.name.equals(target.name, ignoreCase = true) } == true) {
                continue
            }
            try {
                Os.link(source.absolutePath, target.absolutePath)
                return target
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) continue
            }
            if (!target.createNewFile()) continue
            var published = false
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.fd.sync()
                    }
                }
                published = true
                return target
            } finally {
                if (!published) target.delete()
            }
        }
    }
}
