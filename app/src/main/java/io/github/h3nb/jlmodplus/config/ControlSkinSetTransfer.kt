package io.github.h3nb.jlmodplus.config

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Shared helpers for importing standalone ZIP skin sets and resolving local set references.
 * A set's ID stays stable unless it conflicts with different local content.
 */
internal object ControlSkinSetTransfer {
    const val MAX_METADATA_BYTES = 65_536L
    const val MAX_ASSET_BYTES = 8L * 1024L * 1024L
    private const val METADATA = "skin.json"
    private val extensions = setOf("png", "webp", "svg")

    fun validSetName(name: String): Boolean = ControlSkinStore.validSetName(name)

    fun availableName(label: String, existingNames: Collection<String>): String {
        var name = label
        var index = 2
        while (existingNames.any { ControlSkinStore.sameSetName(it, name) }) {
            val suffix = " (" + index++ + ")"
            val length = minOf(label.codePointCount(0, label.length),
                ControlSkinStore.MAX_NAME_CODE_POINTS - suffix.length)
            name = label.substring(0, label.offsetByCodePoints(0, length))
                .trimEnd(' ', '.') + suffix
        }
        return name
    }

    fun validAssetName(name: String): Boolean {
        if (name == METADATA) return true
        if (name.isBlank() || name.startsWith('.') || name.endsWith(' ') ||
            '/' in name || 92.toChar() in name || 0.toChar() in name ||
            name.substringAfterLast('.', "").lowercase() !in extensions) return false
        return ControlSkinStore.validAsset(name.substringBeforeLast('.'))
    }

    fun readId(folder: File): String? {
        val metadata = File(folder, METADATA)
        if (!metadata.isFile || metadata.length() !in 1..MAX_METADATA_BYTES) return null
        return runCatching {
            val id = (Json.parseToJsonElement(metadata.readText()).jsonObject["id"]
                as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
            id.takeIf { UUID.fromString(it).toString() == it }
        }.getOrNull()
    }

    fun findById(workdir: File, id: String?): File? {
        if (id == null || runCatching { UUID.fromString(id).toString() != id }
                .getOrDefault(true)) return null
        val root = ControlSkinStore.directory(workdir)
        val matches = root.listFiles()?.filter { it.isDirectory && readId(it) == id }.orEmpty()
        if (matches.size > 1) throw IOException("Control skin ID is ambiguous")
        return matches.singleOrNull()
    }

    fun files(folder: File): List<File> {
        if (readId(folder) == null) throw IOException("Invalid control skin metadata")
        val entries = folder.listFiles() ?: throw IOException("Cannot read control skin set")
        if (entries.none { it.name == METADATA }) throw IOException("Control skin has no metadata")
        return entries.sortedBy(File::getName).onEach {
            if (!it.isFile || !validAssetName(it.name) ||
                it.canonicalFile.parentFile != folder.canonicalFile ||
                it.length() > if (it.name == METADATA) MAX_METADATA_BYTES else MAX_ASSET_BYTES) {
                throw IOException("Invalid control skin asset: " + it.name)
            }
        }
    }

    fun install(workdir: File, source: File, sourceName: String?): String =
        synchronized(ControlSkinStore::class.java) {
            val sourceId = readId(source) ?: throw IOException("Imported control skin is invalid")
            val incoming = files(source)
            val original = findById(workdir, sourceId)
            if (original != null && sameFiles(incoming, original)) return@synchronized sourceId

            val root = ControlSkinStore.directory(workdir)
            root.listFiles()?.sortedBy(File::getName)?.firstOrNull { candidate ->
                candidate.isDirectory && readId(candidate) != null &&
                    runCatching { sameFiles(incoming, candidate) }.getOrDefault(false)
            }?.let { existing -> return@synchronized readId(existing)!! }

            if (!root.isDirectory && !root.mkdirs()) {
                throw IOException("Cannot create control skin directory")
            }
            val installedId = if (original == null) sourceId else UUID.randomUUID().toString()
            val label = sourceName?.takeIf(::validSetName) ?: "Imported control skin"
            val existing = root.listFiles() ?: throw IOException("Cannot list control skin sets")
            val name = availableName(label, existing.map(File::getName))
            val stage = File(root, ".skin-" + UUID.randomUUID() + ".tmp")
            val target = File(root, name)
            if (!stage.mkdir()) throw IOException("Cannot stage imported control skin")
            try {
                incoming.filterNot { it.name == METADATA }.forEach {
                    it.copyTo(File(stage, it.name))
                }
                val old = Json.parseToJsonElement(File(source, METADATA).readText()).jsonObject
                File(stage, METADATA).writeText(
                    JsonObject(old + ("id" to JsonPrimitive(installedId))).toString(),
                )
                if (target.exists() || !stage.renameTo(target)) {
                    throw IOException("Cannot publish imported control skin")
                }
                installedId
            } finally {
                if (stage.exists()) stage.deleteRecursively()
            }
        }

    private fun sameFiles(source: List<File>, existing: File): Boolean {
        val other = runCatching { files(existing) }.getOrNull() ?: return false
        if (source.map(File::getName) != other.map(File::getName)) return false
        return source.zip(other).all { (a, b) ->
            if (a.name == METADATA) {
                val left = Json.parseToJsonElement(a.readText()).jsonObject
                val right = Json.parseToJsonElement(b.readText()).jsonObject
                val normalizedLeft = (left - "id") +
                    ("version" to (left["version"] ?: JsonPrimitive(1)))
                val normalizedRight = (right - "id") +
                    ("version" to (right["version"] ?: JsonPrimitive(1)))
                return@all normalizedLeft == normalizedRight
            }
            if (a.length() != b.length()) return@all false
            a.inputStream().buffered().use { left ->
                b.inputStream().buffered().use { right ->
                    val leftBytes = ByteArray(8192)
                    val rightBytes = ByteArray(8192)
                    while (true) {
                        val count = left.read(leftBytes)
                        if (count == -1) return@use right.read() == -1
                        var offset = 0
                        while (offset < count) {
                            val n = right.read(rightBytes, offset, count - offset)
                            if (n < 0) return@use false
                            offset += n
                        }
                        for (i in 0 until count) if (leftBytes[i] != rightBytes[i]) {
                            return@use false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
        }
    }
}
