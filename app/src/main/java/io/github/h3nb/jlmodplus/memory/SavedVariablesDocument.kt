/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.JsonElement
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
    private const val SCHEMA = 3
    private val PRIMITIVE_FIELDS = setOf("byte", "short", "char", "int", "long", "float", "double")
    private val PRIMITIVE_ARRAYS = setOf("[B", "[S", "[C", "[I", "[J", "[F", "[D")

    @JvmStatic
    @Throws(IOException::class)
    fun validate(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw IOException("Saved definitions exceed size limit")
        try {
            val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            val schema = document.number("schema")
            if (schema != 2 && schema != SCHEMA) {
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
                val locator = record["locator"] as JsonObject
                if (schema == 2) validateLegacyLocator(locator)
                else validateTypedLocator(locator)
            }
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("Invalid Saved definitions document", error)
        }
    }

    data class MergeResult(val bytes: ByteArray, val added: Int, val skipped: Int)

    /**
     * Merge a Saved-only transfer without modifying the original document.
     * Duplicate targets are recognized by a canonical typed path, never by label
     * or an imported installation's record ID. The caller publishes atomically.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun merge(local: ByteArray?, incoming: ByteArray): MergeResult {
        validate(incoming)
        if (local != null) validate(local)
        val old = local?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)) as JsonObject }
        val source = Json.parseToJsonElement(incoming.toString(Charsets.UTF_8)) as JsonObject
        val entries = ArrayList<JsonElement>()
        val seen = HashSet<String>()
        val ids = HashSet<String>()
        fun add(record: JsonObject, schema: Int, imported: Boolean): Boolean {
            val locator = canonicalLocator(record["locator"] as JsonObject, schema)
            val signature = locator.toString()
            if (imported && signature in seen) return false
            seen.add(signature)
            var id = record.string("id")
            if (imported && !ids.add(id)) {
                do { id = UUID.randomUUID().toString() } while (!ids.add(id))
            } else if (!imported) {
                ids.add(id)
            }
            entries.add(JsonObject(mapOf(
                "id" to JsonPrimitive(id),
                "label" to JsonPrimitive(record.string("label")),
                "locator" to locator,
            )))
            return true
        }
        old?.let { document ->
            val version = document.number("schema")
            (document["items"] as JsonArray).forEach { add(it as JsonObject, version, false) }
        }
        var added = 0
        var skipped = 0
        val version = source.number("schema")
        (source["items"] as JsonArray).forEach {
            if (add(it as JsonObject, version, true)) added++ else skipped++
        }
        if (entries.size > 64) throw IOException("Too many Saved definitions after merge")
        val document = JsonObject(mapOf(
            "schema" to JsonPrimitive(SCHEMA),
            "items" to JsonArray(entries),
        ))
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        validate(bytes)
        return MergeResult(bytes, added, skipped)
    }

    private fun canonicalLocator(locator: JsonObject, schema: Int): JsonObject {
        val normalized = ArrayList<JsonElement>()
        if (schema == 2) {
            val fields = locator["segments"] as JsonArray
            fields.forEach {
                val field = it as JsonObject
                normalized.add(canonicalField(field))
                val index = field.number("index")
                if (index >= 0) normalized.add(canonicalIndex(field.string("type"), index))
            }
            val terminalIndex = locator.number("arrayIndex")
            if (terminalIndex >= 0) {
                val fieldsLast = fields.last() as JsonObject
                normalized.add(canonicalIndex(fieldsLast.string("type"), terminalIndex))
            }
        } else {
            (locator["steps"] as JsonArray).forEach {
                val part = it as JsonObject
                normalized.add(when (part.number("kind")) {
                    1 -> canonicalField(part)
                    2 -> canonicalIndex(part.string("arrayType"), part.number("index"))
                    else -> throw IOException("Unsupported Saved step")
                })
            }
        }
        return JsonObject(mapOf(
            "rootKind" to JsonPrimitive(locator.number("rootKind")),
            "rootClass" to JsonPrimitive(locator.string("rootClass")),
            "steps" to JsonArray(normalized),
        ))
    }

    private fun canonicalField(part: JsonObject): JsonObject = JsonObject(mapOf(
        "kind" to JsonPrimitive(1),
        "owner" to JsonPrimitive(part.string("owner")),
        "name" to JsonPrimitive(part.string("name")),
        "type" to JsonPrimitive(part.string("type")),
    ))

    private fun canonicalIndex(arrayType: String, index: Int): JsonObject = JsonObject(mapOf(
        "kind" to JsonPrimitive(2),
        "arrayType" to JsonPrimitive(arrayType),
        "index" to JsonPrimitive(index),
    ))

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

    private fun validateLegacyLocator(locator: JsonObject) {
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

    /** Canonical schema-3 path, deliberately limited to field and array-index steps. */
    private fun validateTypedLocator(locator: JsonObject) {
        val rootKind = locator.number("rootKind")
        val rootClass = locator.string("rootClass")
        val steps = locator["steps"] as JsonArray
        if (rootKind !in 1..3 || !safe(rootClass) || steps.size !in 1..24) {
            throw IOException("Invalid Saved root or path depth")
        }
        var selectedType: String? = null
        steps.forEachIndexed { position, item ->
            val step = item as JsonObject
            val terminal = position == steps.lastIndex
            when (step.number("kind")) {
                1 -> {
                    val owner = step.string("owner")
                    val name = step.string("name")
                    val type = step.string("type")
                    if (!safe(owner) || !safe(name) || !safe(type)
                        || position > 0 && selectedType in PRIMITIVE_FIELDS
                        || position > 0 && selectedType?.startsWith("[") == true
                        || terminal && type !in PRIMITIVE_FIELDS
                        || !terminal && type in PRIMITIVE_FIELDS) {
                        throw IOException("Invalid Saved field step")
                    }
                    selectedType = type
                }
                2 -> {
                    val arrayType = step.string("arrayType")
                    val index = step.number("index")
                    if (selectedType != arrayType || index !in 0 until 2_000_000
                        || !arrayType.startsWith("[")
                        || terminal && arrayType !in PRIMITIVE_ARRAYS) {
                        throw IOException("Invalid Saved index step")
                    }
                    selectedType = when {
                        arrayType.startsWith("[[") -> arrayType.drop(1)
                        arrayType.startsWith("[L") && arrayType.endsWith(";") ->
                            arrayType.substring(2, arrayType.length - 1)
                        else -> arrayType // A primitive component can only be terminal.
                    }
                    if (!terminal && arrayType in PRIMITIVE_ARRAYS) {
                        throw IOException("Primitive array element cannot have children")
                    }
                }
                else -> throw IOException("Unsupported Saved locator step")
            }
        }
        if ((steps.last() as JsonObject).number("kind") == 1 &&
            selectedType !in PRIMITIVE_FIELDS) {
            throw IOException("Saved locator has no editable terminal")
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
