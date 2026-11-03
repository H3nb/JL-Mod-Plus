/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.ConfigAssetFiles
import io.github.h3nb.jlmodplus.config.ControlSkinStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Only a MIDlet's selected screen skin and control-skin set are portable. Other global skins
 * belong to the local workdir; never export or replace the entire shared asset directory.
 */
internal object LibraryBundleSkinAssets {
    private const val META = "skin.json"
    private const val MAX_META = 65_536L
    private const val MAX_IMAGE = 8L * 1024L * 1024L
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "svg")
    private val CONTROL_EXTENSIONS = setOf("png", "webp", "svg")

    data class References(val screenSkin: String?, val controlSkinId: String?)

    fun references(configDir: File): References {
        val document = File(configDir, "config.json")
        val json = runCatching {
            if (document.isFile) Json.parseToJsonElement(document.readText()).jsonObject else null
        }.getOrNull()
        return References(json?.string("ScreenBackgroundImage"), json?.string("ControlSkinSetId"))
    }

    fun validScreenName(name: String): Boolean =
        validLeaf(name) && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    fun validControlName(name: String): Boolean {
        if (name == META) return true
        if (!validLeaf(name) || name.substringAfterLast('.', "").lowercase() !in CONTROL_EXTENSIONS) {
            return false
        }
        return ControlSkinStore.validAsset(name.substringBeforeLast('.'))
    }

    fun screenSkin(workdir: File, name: String?): File? {
        if (name == null || !validScreenName(name)) return null
        return File(File(workdir, "skins"), name).takeIf { it.isFile }
    }

    /** Find one selected global set without ControlSkinStore.list()'s repair/mutation side effects. */
    fun controlSkin(workdir: File, id: String?): File? {
        if (!validId(id)) return null
        val root = File(File(workdir, "skins"), "controls")
        val matches = root.listFiles()?.filter { it.isDirectory && readSkinId(it) == id }.orEmpty()
        if (matches.size > 1) throw IOException("Control skin ID is ambiguous")
        return matches.singleOrNull()
    }

    fun selectedControlFiles(folder: File): List<File> {
        if (readSkinId(folder) == null) throw IOException("Invalid control skin metadata")
        val files = folder.listFiles() ?: throw IOException("Cannot read control skin set")
        if (files.none { it.name == META }) throw IOException("Control skin has no metadata")
        return files.sortedBy(File::getName).onEach {
            if (!it.isFile || !validControlName(it.name) ||
                it.canonicalFile.parentFile != folder.canonicalFile ||
                it.length() > if (it.name == META) MAX_META else MAX_IMAGE) {
                throw IOException("Invalid control skin asset: ${it.name}")
            }
        }
    }

    /** Install selected assets without modifying another game's set or an existing skin ID. */
    @Synchronized
    fun importControlSkin(workdir: File, source: File): String {
        val sourceId = readSkinId(source) ?: throw IOException("Imported control skin is invalid")
        val files = selectedControlFiles(source)
        val original = controlSkin(workdir, sourceId)
        if (original != null && sameFiles(files, original)) return sourceId

        val installedId = if (original == null) sourceId else UUID.randomUUID().toString()
        val root = File(File(workdir, "skins"), "controls")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create control skin directory")
        val name = "Imported-${UUID.randomUUID()}"
        val staging = File(root, ".$name.tmp")
        val target = File(root, name)
        if (!staging.mkdir()) throw IOException("Cannot stage imported control skin")
        try {
            files.filterNot { it.name == META }.forEach { sourceFile ->
                sourceFile.copyTo(File(staging, sourceFile.name))
            }
            val oldMeta = Json.parseToJsonElement(File(source, META).readText()).jsonObject
            File(staging, META).writeText(
                JsonObject(oldMeta + ("id" to JsonPrimitive(installedId))).toString(),
            )
            if (target.exists() || !staging.renameTo(target)) {
                throw IOException("Cannot publish imported control skin")
            }
            return installedId
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Configuration-only import does not bring global assets with it. Missing references become
     * built-in defaults; an explicitly imported skin can be renamed to avoid a local conflict.
     * This edits only a staged config.json before its existing transaction is published.
     */
    fun normalizeConfig(
        stagedConfig: File,
        workdir: File,
        importedScreenSkin: String?,
        importedControlSkin: String?,
        fallbackMissingAssets: Boolean = true,
    ) {
        val file = File(stagedConfig, "config.json")
        if (!file.isFile) return
        val original = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return // Legacy/invalid config remains handled by the existing config loader.
        val current = original.toMutableMap()

        val screen = importedScreenSkin ?: original.string("ScreenBackgroundImage")
        if (fallbackMissingAssets && screen != null && screenSkin(workdir, screen) == null) {
            current["ScreenBackgroundImage"] = JsonNull
        } else if (importedScreenSkin != null) {
            current["ScreenBackgroundImage"] = JsonPrimitive(importedScreenSkin)
        }

        val soundBank = original.string("SoundBank")
        if (fallbackMissingAssets && soundBank != null &&
            (!validLeaf(soundBank) ||
                !File(File(workdir, "soundbanks"), soundBank).isFile)) {
            current["SoundBank"] = JsonNull
        }

        if (fallbackMissingAssets && original["Shader"] != null && original["Shader"] != JsonNull &&
            !shaderAvailable(workdir, original["Shader"] as? JsonObject)) {
            current["Shader"] = JsonNull
        }

        val controlId = importedControlSkin ?: original.string("ControlSkinSetId")
        if (fallbackMissingAssets && controlId != null && controlSkin(workdir, controlId) == null) {
            current["ControlSkinSetId"] = JsonNull
        } else if (importedControlSkin != null) {
            current["ControlSkinSetId"] = JsonPrimitive(importedControlSkin)
        }

        if (current != original) file.writeText(JsonObject(current).toString() + "\n")
    }

    private fun shaderAvailable(workdir: File, selection: JsonObject?): Boolean {
        if (selection == null) return false
        val name = selection.string("name") ?: return false
        val fragment = selection.string("fragment") ?: return false
        val vertex = selection.string("vertex") ?: return false
        val author = selection.string("author") ?: "unknown"
        val files = File(workdir, "shaders").listFiles()?.filter {
            it.isFile && it.extension.equals("ini", ignoreCase = true)
        }.orEmpty()
        // Match the same descriptor fields used by ConfigActivity's shader catalog.
        return files.any { file ->
            val lines = runCatching { file.readLines() }.getOrNull() ?: return@any false
            var section = ""
            var values = mutableMapOf<String, String>()
            fun matches(): Boolean = section == name &&
                values["Fragment"] == fragment && values["Vertex"] == vertex &&
                (values["Author"] ?: "unknown") == author
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (matches()) return@any true
                    section = trimmed.removeSurrounding("[", "]")
                    values = mutableMapOf()
                } else {
                    val key = trimmed.substringBefore('=', "")
                    if (trimmed.contains('=') && key.isNotEmpty()) {
                        values[key] = trimmed.substringAfter('=').trim()
                    }
                }
            }
            matches()
        }
    }

    private fun sameFiles(source: List<File>, existing: File): Boolean {
        val other = runCatching { selectedControlFiles(existing) }.getOrNull() ?: return false
        if (source.map(File::getName) != other.map(File::getName)) return false
        return source.zip(other).all { (a, b) ->
            if (a.length() != b.length()) return@all false
            a.inputStream().use { left ->
                b.inputStream().use { right ->
                    val first = ByteArray(8192)
                    val second = ByteArray(8192)
                    while (true) {
                        val count = left.read(first)
                        if (count < 0) return@use right.read() == -1
                        var offset = 0
                        while (offset < count) {
                            val n = right.read(second, offset, count - offset)
                            if (n < 0) return@use false
                            offset += n
                        }
                        for (i in 0 until count) if (first[i] != second[i]) return@use false
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
        }
    }

    private fun readSkinId(folder: File): String? {
        val file = File(folder, META)
        if (!file.isFile || file.length() !in 1..MAX_META) return null
        val id = runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.string("id")
        }.getOrNull()
        return id?.takeIf(::validId)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun validId(value: String?): Boolean = value != null && runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private fun validLeaf(name: String): Boolean =
        name.isNotBlank() && name.length <= 120 &&
            name != "." && name != ".." && !name.startsWith('.') &&
            !name.endsWith(' ') && '/' !in name && '\\' !in name && '\u0000' !in name
}
