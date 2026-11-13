/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryScriptBuilderTest {
    private fun locator(): Bundle {
        val field = Bundle().apply {
            putInt("kind", 1)
            putString("owner", "sample/Game")
            putString("name", "coins")
            putString("type", "int")
        }
        return Bundle().apply {
            putInt("savedSchema", 2)
            putInt("savedRootKind", 1)
            putString("savedRootClass", "sample/Game")
            putParcelableArrayList("savedSteps", arrayListOf(field))
        }
    }

    private fun document(
        mode: MemoryScriptBuilderMode, operation: String = "set", value: String = "100",
    ): JsonObject {
        val draft = MemoryScriptBuilderDraft(
            id = "builder.test", mode = mode, name = "Coins",
            actions = listOf(MemoryScriptBuilderAction("coins", "Coins", value, operation)),
        )
        val json = MemoryRecipeGenerator.fromBuilder(
            draft, "sample/Game", mapOf("coins" to locator()),
        )
        MemoryRecipeRuntime.validateDocument(json)
        return Json.parseToJsonElement(json) as JsonObject
    }

    @Test fun eachBuilderModeProducesTheExpectedRuntimeSemantics() {
        val toggle = document(MemoryScriptBuilderMode.FREEZE)
        assertEquals(JsonPrimitive("toggle"), toggle["mode"])
        assertEquals(JsonPrimitive(100),
            ((toggle["actions"] as JsonArray).single() as JsonObject)["value"])
        assertTrue((toggle["inputs"] as JsonObject).isEmpty())

        val form = document(MemoryScriptBuilderMode.FORM)
        val field = (form["actions"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("form"), form["mode"])
        assertEquals(JsonPrimitive("value1"), field["valueInput"])
        assertEquals(JsonPrimitive("Coins"),
            (((form["inputs"] as JsonObject)["value1"] as JsonObject)["label"]))

        val action = document(MemoryScriptBuilderMode.ACTIONS, "subtract", "20")
        val change = (action["actions"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("oneShot"), action["mode"])
        assertEquals(JsonPrimitive("add"), change["operation"])
        assertEquals(JsonPrimitive(-20), change["value"])
    }

    @Test fun missingBookmarkLocatorCannotGenerateAWriteTarget() {
        val draft = MemoryScriptBuilderDraft(
            id = "builder.test", mode = MemoryScriptBuilderMode.ACTIONS, name = "Coins",
            actions = listOf(MemoryScriptBuilderAction("missing", "Coins")),
        )
        assertTrue(runCatching {
            MemoryRecipeGenerator.fromBuilder(draft, "sample/Game", emptyMap())
        }.isFailure)
    }
}
