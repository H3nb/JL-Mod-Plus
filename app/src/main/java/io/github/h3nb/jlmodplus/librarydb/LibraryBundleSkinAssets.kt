/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.ConfigAssetFiles
import io.github.h3nb.jlmodplus.config.ControlSkinSetTransfer
import java.io.File
import java.io.IOException
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
    internal const val MAX_META = ControlSkinSetTransfer.MAX_METADATA_BYTES
    internal const val MAX_LAYOUT_SKIN_BYTES = 16L * 1024L * 1024L
    internal const val MAX_CONTROL_SKIN_BYTES = ControlSkinSetTransfer.MAX_ASSET_BYTES
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "svg")

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

    fun validSetName(name: String): Boolean = ControlSkinSetTransfer.validSetName(name)

    fun validControlName(name: String): Boolean = ControlSkinSetTransfer.validAssetName(name)

    fun screenSkin(workdir: File, name: String?): File? {
        if (name == null || !validScreenName(name)) return null
        return File(File(workdir, "skins"), name).takeIf { it.isFile }
    }

    /** Find the selected shared set without metadata repair side effects. */
    fun controlSkin(workdir: File, id: String?): File? =
        ControlSkinSetTransfer.findById(workdir, id)

    fun selectedControlFiles(folder: File): List<File> = ControlSkinSetTransfer.files(folder)

    fun importControlSkin(workdir: File, source: File, sourceName: String?): String =
        ControlSkinSetTransfer.install(workdir, source, sourceName)

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
        if (fallbackMissingAssets && controlId != null &&
            runCatching { controlSkin(workdir, controlId) }.getOrNull() == null) {
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
            fun matches(): Boolean = (values["Name"] ?: section) == name &&
                values["Fragment"] == fragment && values["Vertex"] == vertex &&
                (values["Author"] ?: "unknown") == author
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[")) {
                    if (matches()) return@any true
                    section = trimmed.replace("[", "").replace("]", "")
                    values = mutableMapOf()
                } else {
                    val equal = trimmed.indexOf('=')
                    if (equal >= 4) {
                        values[trimmed.substring(0, equal).trim()] =
                            trimmed.substring(equal + 1).trim()
                    }
                }
            }
            matches()
        }
    }

    private fun validLeaf(name: String): Boolean =
        name.isNotBlank() && name.length <= 120 &&
            name != "." && name != ".." && !name.startsWith('.') &&
            !name.endsWith(' ') && '/' !in name && '\\' !in name && '\u0000' !in name
}
