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
    private fun locator(fieldName: String = "coins"): Bundle {
        val field = Bundle().apply {
            putInt("kind", 1)
            putString("owner", "sample/Game")
            putString("name", fieldName)
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

    @Test fun newValueActionsRequireAnExplicitAmount() {
        for (mode in listOf(MemoryScriptBuilderMode.FREEZE, MemoryScriptBuilderMode.ACTIONS)) {
            val draft = MemoryScriptBuilderDraft(
                id = "builder.test", mode = mode, name = "Coins",
                actions = listOf(MemoryScriptBuilderAction("coins", "Coins")),
            )
            assertTrue(runCatching {
                MemoryRecipeGenerator.fromBuilder(draft, "sample/Game", mapOf("coins" to locator()))
            }.isFailure)
            val withZero = draft.copy(actions = draft.actions.map { it.copy(value = "0") })
            val result = Json.parseToJsonElement(MemoryRecipeGenerator.fromBuilder(
                withZero, "sample/Game", mapOf("coins" to locator()),
            )) as JsonObject
            val action = (result["actions"] as JsonArray).single() as JsonObject
            assertEquals(JsonPrimitive(0), action["value"])
        }
    }

    @Test fun visualEditingKeepsCustomFormBoundsAndDoesNotDiscardJsonFields() {
        val draft = MemoryScriptBuilderDraft(
            id = "builder.form", mode = MemoryScriptBuilderMode.FORM, name = "Health",
            actions = listOf(MemoryScriptBuilderAction(
                bookmarkId = "coins", label = "Editable health", min = "10", max = "200",
            )),
        )
        val locators = mapOf("coins" to locator())
        val document = MemoryRecipeGenerator.fromBuilder(draft, "sample/Game", locators)
        val restored = MemoryRecipeGenerator.forEditing(
            document, "sample/Game", locators,
            listOf(SavedVariableRow("coins", "Coins", "coins", true)),
        )
        assertTrue(restored != null)
        assertEquals("10", restored?.actions?.single()?.min)
        assertEquals("200", restored?.actions?.single()?.max)
        assertEquals("builder.form", restored?.replacingId)
        assertEquals(false, restored?.hasChanges)
        assertEquals(
            Json.parseToJsonElement(document),
            Json.parseToJsonElement(MemoryRecipeGenerator.fromBuilder(
                restored!!, "sample/Game", locators,
            )),
        )
        assertTrue(restored.copy(actions = restored.actions.map { it.copy(max = "150") }).hasChanges)

        val invalid = draft.copy(actions = draft.actions.map { it.copy(min = "300") })
        assertTrue(runCatching {
            MemoryRecipeGenerator.fromBuilder(invalid, "sample/Game", locators)
        }.isFailure)

        val json = Json.parseToJsonElement(document) as JsonObject
        val advanced = JsonObject(json + mapOf(
            "root" to JsonObject(mapOf(
                "kind" to JsonPrimitive("midlet"), "class" to JsonPrimitive("sample/Game"),
            )),
        ))
        assertEquals(null, MemoryRecipeGenerator.forEditing(
            advanced.toString(), "sample/Game", locators,
            listOf(SavedVariableRow("coins", "Coins", "coins", true)),
        ))
    }

    @Test fun quickActionsRestoreSubtractWithoutChangingRuntimeSemantics() {
        val original = document(MemoryScriptBuilderMode.ACTIONS, "subtract", "20")
        val locators = mapOf("coins" to locator())
        val restored = MemoryRecipeGenerator.forEditing(
            original.toString(), "sample/Game", locators,
            listOf(SavedVariableRow("coins", "Coins", "coins", true)),
        )
        assertEquals("subtract", restored?.actions?.single()?.operation)
        assertEquals("20", restored?.actions?.single()?.value)
        assertEquals(original, Json.parseToJsonElement(
            MemoryRecipeGenerator.fromBuilder(restored!!, "sample/Game", locators),
        ))
    }

    @Test fun visualEditingRejectsSilentBookmarkRetargeting() {
        val existing = document(MemoryScriptBuilderMode.FREEZE)
        val currentLocator = mapOf("coins" to locator())
        val saved = MemoryRecipeGenerator.forEditing(
            existing.toString(), "sample/Game", currentLocator,
            listOf(SavedVariableRow("coins", "Coins", "coins", true)),
        )!!
        val edited = saved.copy(name = "More Coins")
        val unchanged = Json.parseToJsonElement(MemoryRecipeGenerator.fromBuilder(
            edited, "sample/Game", currentLocator,
        )) as JsonObject
        assertEquals(JsonPrimitive("More Coins"), unchanged["name"])

        val relinkedLocator = mapOf("coins" to locator("health"))
        val failure = runCatching {
            MemoryRecipeGenerator.fromBuilder(edited, "sample/Game", relinkedLocator)
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("relinked") == true)

        val reselected = edited.copy(actions = listOf(
            MemoryScriptBuilderAction("coins", "Coins", value = "100"),
        ))
        val retargeted = Json.parseToJsonElement(MemoryRecipeGenerator.fromBuilder(
            reselected, "sample/Game", relinkedLocator,
        )) as JsonObject
        val action = (retargeted["actions"] as JsonArray).single() as JsonObject
        val path = (action["path"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("health"), path["name"])
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
