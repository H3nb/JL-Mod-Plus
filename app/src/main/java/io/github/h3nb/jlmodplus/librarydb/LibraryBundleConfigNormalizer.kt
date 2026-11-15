/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.ControlSkinSetTransfer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Normalize imported app settings without importing or mutating global assets. */
internal object LibraryBundleConfigNormalizer {
    private val screenExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "svg")

    fun normalizeConfig(stagedConfig: File, workdir: File) {
        val file = File(stagedConfig, "config.json")
        if (!file.isFile) return
        val original = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return
        val updated = original.toMutableMap()

        val screen = original.string("ScreenBackgroundImage")
        if (screen != null &&
            (!validLeaf(screen) || screen.substringAfterLast('.', "").lowercase() !in screenExtensions ||
                !File(File(workdir, "skins"), screen).isFile)) {
            updated["ScreenBackgroundImage"] = JsonNull
        }
        val soundBank = original.string("SoundBank")
        if (soundBank != null &&
            (!validLeaf(soundBank) || !File(File(workdir, "soundbanks"), soundBank).isFile)) {
            updated["SoundBank"] = JsonNull
        }
        if (original["Shader"] != null && original["Shader"] != JsonNull &&
            !shaderAvailable(workdir, original["Shader"] as? JsonObject)) {
            updated["Shader"] = JsonNull
        }
        val controlId = original.string("ControlSkinSetId")
        if (controlId != null &&
            runCatching { ControlSkinSetTransfer.findById(workdir, controlId) }.getOrNull() == null) {
            updated["ControlSkinSetId"] = JsonNull
        }
        if (updated != original) file.writeText(JsonObject(updated).toString() + "\n")
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

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun validLeaf(name: String): Boolean =
        name.isNotBlank() && name.length <= 120 &&
            name != "." && name != ".." && !name.startsWith('.') &&
            !name.endsWith(' ') && '/' !in name && '\\' !in name && '\u0000' !in name
}
