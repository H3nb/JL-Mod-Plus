/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

import android.os.Bundle
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
