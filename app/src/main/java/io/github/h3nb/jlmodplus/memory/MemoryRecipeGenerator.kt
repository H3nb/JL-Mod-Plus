/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

import android.os.Bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Simple, data-only authoring for a user-selected verified Saved target. It does not change
 * Saved storage: the generated document can be copied/shared and executed through the same
 * advanced recipe engine. Runtime IDs and reflection field-order indices are never serialized.
 */
internal object MemoryRecipeGenerator {
    @Suppress("DEPRECATION")
    @JvmOverloads
    fun fromSaved(
        locator: Bundle, midletClass: String, id: String, value: String,
        label: String = "Saved automation",
    ): String {
        require(locator.getInt("savedSchema") == 2) { "Unsupported Saved descriptor version" }
        val rootKind = when (locator.getInt("savedRootKind")) {
            1 -> "midlet"
            2 -> "displayable"
            3 -> "initializedStatic"
            else -> error("Invalid Saved root")
        }
        val rootClass = locator.getString("savedRootClass").orEmpty().replace('.', '/')
        require(rootClass.isNotEmpty() && midletClass.isNotBlank())
        val segments = locator.getParcelableArrayList<Bundle>("savedSteps").orEmpty()
        require(segments.isNotEmpty() && segments.size <= 24)
        val steps = segments.map { step ->
            when (step.getInt("kind")) {
                1 -> JsonObject(mapOf(
                    "kind" to JsonPrimitive("field"),
                    "owner" to JsonPrimitive(step.getString("owner").orEmpty().replace('.', '/')),
                    "name" to JsonPrimitive(step.getString("name").orEmpty()),
                    "descriptor" to JsonPrimitive(descriptor(step.getString("type").orEmpty())),
                ))
                2 -> JsonObject(mapOf(
                    "kind" to JsonPrimitive("index"),
                    "descriptor" to JsonPrimitive(descriptor(step.getString("arrayType").orEmpty())),
                    "index" to JsonPrimitive(step.getInt("index")),
                ))
                else -> error("Invalid Saved step")
            }
        }
        val terminal = segments.last()
        val type = if (terminal.getInt("kind") == 1) terminal.getString("type").orEmpty()
            else terminal.getString("arrayType").orEmpty().let {
                when (it) {
                    "[B" -> "byte"
                    "[S" -> "short"
                    "[I" -> "int"
                    "[J" -> "long"
                    "[C" -> "char"
                    "[Z" -> "boolean"
                    else -> error("This numeric type is not yet supported by Recipes")
                }
            }
        val normalized = when (type) {
            "boolean" -> when (value.lowercase()) {
                "true", "1" -> "1"
                "false", "0" -> "0"
                else -> error("Enter true or false")
            }
            "byte", "short", "int", "long", "char" -> value.toLongOrNull()?.toString()
                ?: error("Enter an integer value")
            else -> error("This value type is not supported by simple automation")
        }
        // Avoid accepting values that the typed write itself cannot represent.
        val numeric = normalized.toLong()
        val bounds = when (type) {
            "byte" -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
            "short" -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
            "int" -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            "char" -> 0L..65535L
            "boolean" -> 0L..1L
            else -> Long.MIN_VALUE..Long.MAX_VALUE
        }
        require(numeric in bounds) { "Value exceeds the selected variable type" }

        val root = JsonObject(mapOf(
            "kind" to JsonPrimitive(rootKind), "class" to JsonPrimitive(rootClass),
        ))
        val action = JsonObject(mapOf(
            "kind" to JsonPrimitive("set"),
            "path" to JsonArray(steps),
            "value" to JsonPrimitive(normalized),
        ))
        return JsonObject(mapOf(
            "formatVersion" to JsonPrimitive(1),
            "id" to JsonPrimitive("saved.$id"),
            "name" to JsonPrimitive(label.take(80).ifBlank { "Saved automation" }),
            "game" to JsonObject(mapOf(
                "mainClass" to JsonPrimitive(midletClass.replace('.', '/')),
            )),
            "root" to root,
            "actions" to JsonArray(listOf(action)),
        )).toString()
    }


    private fun targetFromSaved(locator: Bundle, midletClass: String, id: String): JsonObject {
        val source = Json.parseToJsonElement(fromSaved(locator, midletClass, id, "0")) as JsonObject
        val action = (source["actions"] as JsonArray).single() as JsonObject
        return JsonObject(mapOf(
            "root" to (source["root"] ?: error("Missing Bookmark root")),
            "path" to (action["path"] ?: error("Missing Bookmark path")),
        ))
    }

    fun fromBookmark(
        bookmark: SavedVariableRow, locator: Bundle, midletClass: String,
    ): MemoryScriptBuilderAction = MemoryScriptBuilderAction(
        bookmarkId = bookmark.id,
        label = bookmark.label,
        originalTarget = targetFromSaved(locator, midletClass, bookmark.id),
    )

    fun fromBuilder(
        draft: MemoryScriptBuilderDraft,
        midletClass: String,
        locators: Map<String, Bundle>,
    ): String {
        require(draft.name.isNotBlank()) { "Enter a script name" }
        require(draft.actions.isNotEmpty()) { "Add at least one Bookmark" }
        require(draft.actions.map { it.bookmarkId }.distinct().size == draft.actions.size) {
            "Each Bookmark can appear only once"
        }
        val inputs = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val actions = draft.actions.mapIndexed { index, item ->
            val locator = locators[item.bookmarkId]
                ?: error("Bookmark " + item.label + " has no verified locator")
            val target = targetFromSaved(locator, midletClass, item.bookmarkId)
            require(target == item.originalTarget) {
                "Bookmark " + item.label + " was relinked. Remove and add it again to use its new target."
            }
            val root = target.getValue("root")
            val path = target.getValue("path") as JsonArray
            val bounds = primitiveBounds(path)
            val min = bounds.first
            val max = bounds.last
            val key = "value" + (index + 1)
            val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "kind" to JsonPrimitive("set"),
                "root" to root,
                "path" to path,
            )
            when (draft.mode) {
                MemoryScriptBuilderMode.FREEZE -> {
                    val value = item.value.trim().toLongOrNull()
                        ?: error("Enter a numeric value for " + item.label)
                    require(value in min..max) { "Value is out of range for " + item.label }
                    fields["value"] = JsonPrimitive(value)
                }
                MemoryScriptBuilderMode.FORM -> {
                    fields["field"] = JsonPrimitive(key)
                    fields["valueInput"] = JsonPrimitive(key)
                    val minimum = if (item.min.isBlank()) min else item.min.trim().toLongOrNull()
                        ?: error("Invalid minimum for " + item.label)
                    val maximum = if (item.max.isBlank()) max else item.max.trim().toLongOrNull()
                        ?: error("Invalid maximum for " + item.label)
                    require(minimum >= min && maximum <= max && minimum <= maximum) {
                        "Value range is invalid for " + item.label
                    }
                    val initial = if (0L in minimum..maximum) 0L else minimum
                    inputs[key] = JsonObject(mapOf(
                        "min" to JsonPrimitive(minimum),
                        "max" to JsonPrimitive(maximum),
                        "default" to JsonPrimitive(initial),
                        "label" to JsonPrimitive(item.label),
                    ))
                }
                MemoryScriptBuilderMode.ACTIONS -> {
                    val amount = item.value.trim().toLongOrNull()
                        ?: error("Enter a numeric amount for " + item.label)
                    val value = when (item.operation) {
                        "set" -> amount.also {
                            require(it in min..max) { "Value is out of range for " + item.label }
                        }
                        "add" -> amount
                        "subtract" -> {
                            require(amount >= 0) { "Subtract needs a nonnegative amount" }
                            -amount
                        }
                        else -> error("Unsupported action")
                    }
                    fields["value"] = JsonPrimitive(value)
                    if (item.operation != "set") {
                        fields["operation"] = JsonPrimitive("add")
                        fields["field"] = JsonPrimitive(key)
                    }
                }
            }
            JsonObject(fields)
        }
        val document = JsonObject(buildMap {
            put("formatVersion", JsonPrimitive(2))
            put("mode", JsonPrimitive(draft.mode.recipeMode))
            put("id", JsonPrimitive(draft.id))
            put("name", JsonPrimitive(draft.name.trim()))
            if (draft.description.isNotBlank()) put("description", JsonPrimitive(draft.description.trim()))
            put("game", JsonObject(mapOf(
                "mainClass" to JsonPrimitive(midletClass.replace('.', '/')),
            )))
            put("inputs", JsonObject(inputs))
            put("actions", JsonArray(actions))
        }).toString()
        MemoryRecipeRuntime.validateDocument(document)
        return document
    }


    /**
     * Only restore documents that the Builder can regenerate byte-for-byte semantically.
     * Advanced JSON must remain editable without silently discarding its metadata or guards.
     */
    fun forEditing(
        document: String,
        midletClass: String,
        locators: Map<String, Bundle>,
        bookmarks: List<SavedVariableRow>,
    ): MemoryScriptBuilderDraft? = runCatching {
        val original = Json.parseToJsonElement(document) as? JsonObject
            ?: error("Not a recipe object")
        if (original["formatVersion"] != JsonPrimitive(2)) error("Not a Builder recipe")
        val mode = MemoryScriptBuilderMode.entries.firstOrNull {
            original["mode"] == JsonPrimitive(it.recipeMode)
        } ?: error("Unsupported Builder mode")
        val id = (original["id"] as? JsonPrimitive)?.content
            ?: error("Missing recipe ID")
        val name = (original["name"] as? JsonPrimitive)?.content
            ?: error("Missing recipe name")
        val description = (original["description"] as? JsonPrimitive)?.content.orEmpty()
        val declaredInputs = original["inputs"] as? JsonObject ?: error("Missing Builder inputs")
        val lookup = HashMap<Pair<kotlinx.serialization.json.JsonElement, kotlinx.serialization.json.JsonElement>, String?>()
        for ((savedId, locator) in locators) {
            val saved = runCatching {
                targetFromSaved(locator, midletClass, savedId)
            }.getOrNull() ?: continue
            val target = saved.getValue("root") to saved.getValue("path")
            if (target in lookup) lookup[target] = null else lookup[target] = savedId
        }
        val labels = bookmarks.associate { it.id to it.label }
        val actionObjects = original["actions"] as? JsonArray ?: error("Missing actions")
        val entries = actionObjects.mapIndexed { index, element ->
            val action = element as? JsonObject ?: error("Invalid action")
            val root = action["root"] ?: error("Missing root")
            val path = action["path"] as? JsonArray ?: error("Missing path")
            val savedId = lookup[root to path] ?: error("Bookmark unavailable or ambiguous")
            val originalTarget = JsonObject(mapOf("root" to root, "path" to path))
            val key = "value" + (index + 1)
            val operand = (action["value"] as? JsonPrimitive)?.content?.toLongOrNull()
            val savedLabel = labels[savedId] ?: savedId
            when (mode) {
                MemoryScriptBuilderMode.FREEZE -> MemoryScriptBuilderAction(
                    bookmarkId = savedId, label = savedLabel,
                    value = operand?.toString() ?: error("Missing Freeze value"),
                    originalTarget = originalTarget,
                )
                MemoryScriptBuilderMode.FORM -> {
                    val specification = declaredInputs[key] as? JsonObject
                        ?: error("Missing editable field")
                    val fieldLabel = (specification["label"] as? JsonPrimitive)?.content
                        ?: error("Missing field label")
                    val nativeRange = primitiveBounds(path)
                    val minimum = (specification["min"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: error("Missing minimum")
                    val maximum = (specification["max"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: error("Missing maximum")
                    MemoryScriptBuilderAction(
                        bookmarkId = savedId, label = fieldLabel,
                        min = if (minimum == nativeRange.first) "" else minimum.toString(),
                        max = if (maximum == nativeRange.last) "" else maximum.toString(),
                        originalTarget = originalTarget,
                    )
                }
                MemoryScriptBuilderMode.ACTIONS -> {
                    val value = operand ?: error("Missing Quick Action amount")
                    val operation = when ((action["operation"] as? JsonPrimitive)?.content) {
                        null, "set" -> "set"
                        "add" -> if (value < 0L) "subtract" else "add"
                        else -> error("Unsupported operation")
                    }
                    if (operation == "subtract" && value == Long.MIN_VALUE)
                        error("Cannot display subtraction amount")
                    MemoryScriptBuilderAction(
                        bookmarkId = savedId, label = savedLabel,
                        value = if (operation == "subtract") (-value).toString() else value.toString(),
                        operation = operation,
                        originalTarget = originalTarget,
                    )
                }
            }
        }
        val draft = MemoryScriptBuilderDraft(
            id = id, mode = mode, name = name, description = description,
            actions = entries, replacingId = id,
            baseline = MemoryScriptBuilderSnapshot(name, description, entries),
        )
        if (Json.parseToJsonElement(fromBuilder(draft, midletClass, locators)) != original) {
            error("Recipe uses advanced settings")
        }
        draft
    }.getOrNull()

    private fun primitiveBounds(path: JsonArray): LongRange {
        val terminal = path.last() as JsonObject
        val descriptor = (terminal["descriptor"] as JsonPrimitive).content
            .let { if (terminal["kind"] == JsonPrimitive("index")) it.removePrefix("[") else it }
        return when (descriptor) {
            "B" -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
            "S" -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
            "I" -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            "J" -> Long.MIN_VALUE..Long.MAX_VALUE
            "C" -> 0L..65535L
            "Z" -> 0L..1L
            else -> error("Bookmark does not contain an editable integer")
        }
    }

    private fun descriptor(type: String): String = when (type) {
        "byte" -> "B"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "boolean" -> "Z"
        "char" -> "C"
        "float" -> "F"
        "double" -> "D"
        else -> when {
            type.startsWith("[") -> type.replace('.', '/')
            type.isNotEmpty() -> "L${type.replace('.', '/')};"
            else -> error("Invalid Saved field descriptor")
        }
    }
}
