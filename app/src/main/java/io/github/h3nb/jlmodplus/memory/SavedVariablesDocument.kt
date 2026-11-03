/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int

/**
 * Portable Saved document contract shared by the authority and the bundle importer.
 * Validation is independent of the installed game's identity and never runs guest code.
 */
object SavedVariablesDocument {
    const val MAX_BYTES = 65_536
    private const val SCHEMA = 2
    private val PRIMITIVE_ARRAYS = setOf("[B", "[S", "[C", "[I", "[J", "[F", "[D")

    @JvmStatic
    @Throws(IOException::class)
    fun validate(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw IOException("Saved definitions exceed size limit")
        try {
            val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            if (document.number("schema") != SCHEMA) {
                throw IOException("Unsupported Saved definitions version")
            }
            val items = document["items"] as JsonArray
            if (items.size > 64) throw IOException("Too many Saved definitions")
            val ids = HashSet<String>()
            items.forEach { item ->
                val record = item as JsonObject
                val id = record.string("id")
                if (id.isEmpty() || id.length > 64 || !ids.add(id)) {
                    throw IOException("Invalid or duplicate Saved ID")
                }
                val label = record.string("label").trim()
                if (label.isEmpty() || label.length > 64) {
                    throw IOException("Saved variable name must be 1–64 characters")
                }
                validateLocator(record["locator"] as JsonObject)
            }
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("Invalid Saved definitions document", error)
        }
    }

    /**
     * AtomicFile.openRead() recovers the legacy .bak first when it exists, even if
     * the base file also exists. Choose that committed version without mutating a
     * live configuration directory during export.
     */
    @JvmStatic
    fun committedFile(configDir: File): File? {
        val base = File(configDir, "saved-variables.json")
        val backup = File(configDir, "saved-variables.json.bak")
        return when {
            backup.exists() -> backup
            base.exists() -> base
            else -> null
        }
    }

    private fun validateLocator(locator: JsonObject) {
        val rootKind = locator.number("rootKind")
        val rootClass = locator.string("rootClass")
        val parts = locator["segments"] as JsonArray
        val arrayIndex = locator.number("arrayIndex")
        if (rootKind !in 1..3 || !safe(rootClass) || parts.size !in 1..24 ||
            arrayIndex < -1 || arrayIndex >= 2_000_000) {
            throw IOException("Invalid Saved locator")
        }
        var directArrayType: String? = null
        parts.forEachIndexed { index, item ->
            val part = item as JsonObject
            val owner = part.string("owner")
            val name = part.string("name")
            val type = part.string("type")
            val fieldIndex = part.number("index")
            if (!safe(owner) || !safe(name) || !safe(type) ||
                fieldIndex < -1 || fieldIndex >= 100_000 ||
                (fieldIndex >= 0 && (index == parts.lastIndex || !type.startsWith("[L")))) {
                throw IOException("Unsupported Saved field segment")
            }
            if (index == 0) directArrayType = type
            if (arrayIndex >= 0 && index == 0 && fieldIndex != -1) {
                throw IOException("Unsupported Saved primitive array")
            }
        }
        if (arrayIndex >= 0 &&
            (rootKind != 3 || parts.size != 1 || directArrayType !in PRIMITIVE_ARRAYS)) {
            throw IOException("Unsupported Saved primitive array")
        }
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("Missing or invalid Saved field: $key")

    private fun JsonObject.number(key: String): Int =
        (get(key) as? JsonPrimitive)?.int
            ?: throw IOException("Missing or invalid Saved number: $key")

    private fun safe(value: String) = value.isNotEmpty() &&
        value.length <= MemoryEngineContract.MAX_IPC_DISPLAY_CHARS
}
