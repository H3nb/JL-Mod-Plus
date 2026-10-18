/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfileJsonTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/config/$name")).readText()

    @Test fun oldV7FixtureAndNewWriterAgreeWithFrozenOldReader() {
        val input = fixture("gson-v7.json")
        val decoded = ProfileJson.decode(input, false)
        val model = decoded.model
        assertEquals(7, decoded.sourceVersion)
        assertFalse(decoded.needsPublication)
        // Expectations are independent of the new writer.
        assertEquals(176, model.screenWidth)
        assertEquals(208, model.screenHeight)
        assertEquals(85, model.screenScaleRatio)
        assertEquals("背景 & skin.png", model.screenBackgroundImage)
        assertEquals(listOf(1.25f, -0.0f, 2.5f, 4f), model.shader!!.values)
        assertEquals("上 Ω", model.customKeys!!.single().keyName)
        assertEquals(mapOf(131 to 0, 132 to Int.MIN_VALUE, 133 to 42), model.keyMappings)
        assertEquals("9007199254740993", model.controller!!.jsonObject.getValue("future").jsonObject.getValue("large").toString())

        val output = ProfileJson.encode(model)
        val old = Gson().fromJson(output, LegacyV7Profile::class.java)
        assertEquals(176, old.screenWidth)
        assertEquals(0, old.keyMappings[131])
        assertEquals(Int.MIN_VALUE, old.keyMappings[132])
        assertArrayEquals(floatArrayOf(1.25f, -0.0f, 2.5f, 4f), old.shader.values, 0f)
        // Compare every emitted known field through the frozen old v7 reader, not a manual field list.
        val oldTree = JsonParser.parseString(GsonBuilder().serializeNulls().create().toJson(old)).asJsonObject
        listOf("HwAcceleration", "ScreenScaleToFit", "ScreenKeepAspectRatio").forEach(oldTree::remove)
        val newTree = Json.parseToJsonElement(output).jsonObject
        val comparableOld = Json.parseToJsonElement(oldTree.toString()).jsonObject.filterValues { it != JsonNull }
        // The additive cutout option was not part of the frozen historical schema.
        assertEquals(JsonPrimitive(false), newTree["AllowDisplayCutout"])
        assertTrue(ProfileJson.sameValue(JsonObject(comparableOld), JsonObject(newTree - "AllowDisplayCutout")))
    }

    @Test fun additiveCutoutOptionDefaultsOffWithoutPublishingOrChangingEffectiveConfig() {
        val input = fixture("gson-v7.json")
        assertFalse(Json.parseToJsonElement(input).jsonObject.containsKey("AllowDisplayCutout"))
        val decoded = ProfileJson.decode(input, false)
        val explicitOff = ProfileJson.decode(
            JsonObject(Json.parseToJsonElement(input).jsonObject +
                ("AllowDisplayCutout" to JsonPrimitive(false))).toString(), false,
        )
        assertFalse(decoded.model.allowDisplayCutout)
        assertFalse(decoded.needsPublication)
        assertTrue(ProfileConfigMatcher.sameConfig(decoded.model, explicitOff.model))

        val directory = Files.createTempDirectory("profile-cutout-default").toFile()
        val config = File(directory, Config.MIDLET_CONFIG_FILE).apply { writeText(input) }
        try {
            assertFalse(checkNotNull(ProfilesManager.loadConfig(directory)).allowDisplayCutout)
            assertEquals(input, config.readText())
            assertEquals(setOf(config.name), directory.list()!!.toSet())
            assertTrue(ProfilesManager.saveConfig(directory, decoded.model))
            assertFalse(checkNotNull(ProfilesManager.loadConfig(directory)).allowDisplayCutout)
            assertEquals(JsonPrimitive(false), Json.parseToJsonElement(config.readText()).jsonObject["AllowDisplayCutout"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyScalarsDefaultsAndBackgroundRulesStaySeparateFromController() {
        val model = ProfileJson.decode("""{
            "Version":"7.0", "ScreenWidth":240.0, "ScreenHeight":" 320.0 ",
            "TouchInput":"TrUe", "ScreenBackgroundMode":1.0,
            "Controller":{"enabled":"true","n":9007199254740993,"nil":null}
        }""", false).model
        assertEquals(240, model.screenWidth)
        assertEquals(320, model.screenHeight)
        assertEquals(320, Gson().fromJson("""{"ScreenHeight":" 320.0 "}""", LegacyV7Profile::class.java).screenHeight)
        assertTrue(model.touchInput)
        assertEquals(BackgroundMode.CUSTOM, model.screenBackgroundMode)
        assertFalse(io.github.h3nb.jlmodplus.input.ControllerConfig.parse(model.controller!!.jsonObject).isSupported)
        val missing = ProfileJson.decode("""{"Version":7}""", false).model
        val fresh = ProfileModel.fresh("default: value\n")
        assertEquals(0, missing.screenWidth)
        assertFalse(missing.showKeyboard)
        assertEquals(240, fresh.screenWidth)
        assertTrue(fresh.showKeyboard)
        listOf("null", "\"1\"", "{}", "[]", "true", "2147483648").forEach { token ->
            assertEquals(BackgroundMode.CUSTOM, ProfileJson.decode("""{"Version":7,"ScreenBackgroundMode":$token}""", false).model.screenBackgroundMode)
        }
        assertThrows(Exception::class.java) { ProfileJson.decode("{'ScreenWidth':240}", false) }
        assertThrows(Exception::class.java) { ProfileJson.decode("""{"Version":7,"ScreenWidth":1.5}""", false) }
    }

    @Test fun historicVersionsNormalizeOnlyWithExplicitContextAndNoReadPublication() {
        val v1 = """{"Version":1,"ScreenWidth":128,"ScreenHeight":160,"ScreenScaleToFit":true,"ScreenKeepAspectRatio":true}"""
        assertFalse(ProfileJson.decode(v1, false).model.fontAA)
        assertEquals(1, ProfileJson.decode(v1, false).model.screenScaleType)
        val v2 = ProfileJson.decode("""{"Version":2,"ScreenScaleToFit":true,"ScreenKeepAspectRatio":false}""", true)
        assertEquals(2, v2.model.screenScaleType)
        assertEquals(BackgroundMode.THEME, v2.model.screenBackgroundMode)
        assertEquals(BackgroundMode.CUSTOM, ProfileJson.decode(v1, false).model.screenBackgroundMode)
        assertEquals(7, v2.model.version)
        assertTrue(v2.needsPublication)
        val map = mapOf<String, Any?>("Version" to 2, "KeyMappings" to """[{"key":131,"value":0},{"key":132,"value":-2147483648}]""")
        assertEquals(mapOf(131 to 0, 132 to Int.MIN_VALUE), ProfileJson.decodeXml(map, false).model.keyMappings)
        assertEquals(mapOf(131 to 0), ProfileJson.decodeMappings(JsonPrimitive("""[{"key":131,"value":0},{}]""")))
        assertEquals(JsonPrimitive(2), ProfileJson.decode("""{"Version":7,"Controller":1,"controller":2}""", false).model.controller)
        val xmlArrays = mapOf<String, Any?>(
            "Version" to 2,
            "ScreenWidth" to 128,
            "Shader" to mapOf("name" to "CRT", "Settings" to doubleArrayOf(1.25, 2.5)),
            "ignoredBytes" to byteArrayOf(1, 2),
            "ignoredBooleans" to booleanArrayOf(true, false),
            "ignoredInts" to intArrayOf(1, 2),
            "ignoredLongs" to longArrayOf(1, 2),
            "ignoredStrings" to arrayOf("old", "history"),
        )
        val arrays = ProfileJson.decodeXml(xmlArrays, false).model
        assertEquals(128, arrays.screenWidth)
        assertEquals(listOf(1.25f, 2.5f), arrays.shader!!.values)
    }

    @Test fun opaqueValuesAndSnapshotsPreserveOwnershipAndExactComparison() {
        val backing = linkedMapOf<String, JsonElement>("large" to Json.parseToJsonElement("9007199254740993"), "nil" to JsonNull)
        val working = ProfileModel(version = 7, controller = JsonObject(backing))
        val shaderDraft = ShaderInfo("CRT", "tester").apply { values = floatArrayOf(1f, 2f) }
        working.shader = ShaderSelection.fromInfo(shaderDraft)
        working.keyMappings = mutableMapOf(131 to 0)
        val baseline = ProfileConfigMatcher.copyConfig(working)
        backing["large"] = JsonPrimitive(1)
        shaderDraft.values[0] = 9f
        (working.keyMappings as MutableMap)[131] = 42
        assertEquals("9007199254740993", baseline.controller!!.jsonObject["large"].toString())
        assertEquals(1f, baseline.shader!!.values!![0])
        assertEquals(0, baseline.keyMappings[131])
        assertFalse(ProfileConfigMatcher.sameConfig(working, baseline))
        assertThrows(UnsupportedOperationException::class.java) {
            (working.shader!!.values as MutableList<Float>)[0] = 5f
        }

        val nestedBacking = linkedMapOf<String, JsonElement>("n" to Json.parseToJsonElement("9007199254740993"))
        val arrayBacking = mutableListOf<JsonElement>(JsonObject(nestedBacking), JsonNull)
        val copiedArray = ProfileJson.ownedJson(JsonArray(arrayBacking)).jsonArray
        nestedBacking["n"] = JsonPrimitive(0)
        arrayBacking.clear()
        assertEquals("9007199254740993", copiedArray[0].jsonObject.getValue("n").toString())
        assertEquals(JsonNull, copiedArray[1])
        assertThrows(UnsupportedOperationException::class.java) {
            (copiedArray[0].jsonObject.entries.first() as MutableMap.MutableEntry<String, JsonElement>)
                .setValue(JsonPrimitive(1))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            val iterator = copiedArray.iterator() as MutableIterator<JsonElement>
            iterator.next()
            iterator.remove()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (copiedArray.subList(0, 1) as MutableList<JsonElement>).clear()
        }
        val written = Json.parseToJsonElement(ProfileJson.encode(baseline))
        assertEquals(JsonNull, written.jsonObject.getValue("Controller").jsonObject["nil"])

        fun equal(a: String, b: String) = ProfileJson.sameValue(Json.parseToJsonElement(a), Json.parseToJsonElement(b))
        assertTrue(equal("[1,-0.0,{\"n\":1e2}]", "[1.0,0,{\"n\":100}]"))
        assertFalse(equal("9007199254740992", "9007199254740993"))
        assertFalse(equal("\"1\"", "1"))
        assertFalse(equal("[1,2]", "[2,1]"))
        listOf("42", "\"opaque\"", "[1,null,{\"nested\":null}]").forEach { payload ->
            val model = ProfileJson.decode("""{"Version":7,"Controller":$payload}""", false).model
            assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(ProfileJson.encode(model)).jsonObject["Controller"])
        }
    }

    @Test fun invalidOrFuturePrimaryNeverFallsBackToStaleXmlOrWritesSideData() {
        val dir = Files.createTempDirectory("profile-invalid").toFile()
        val config = File(dir, Config.MIDLET_CONFIG_FILE)
        val layout = File(dir, Config.MIDLET_KEY_LAYOUT_FILE).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val data = File(dir, "save").apply { writeText("user save") }
        val xml = File(dir, "config.xml").apply { writeText(fixture("legacy-v2.xml")) }
        assertEquals(ProfilesManager.LoadStatus.FAILED, run {
            config.writeText("broken")
            ProfilesManager.loadConfigResult(dir, true, ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false).status
        })
        assertEquals("broken", config.readText())
        config.writeText("""{"Version":8,"ScreenWidth":999}""")
        val before = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val result = ProfilesManager.loadConfigResult(dir, true, ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false)
        assertEquals(ProfilesManager.LoadStatus.UNSUPPORTED_VERSION, result.status)
        assertEquals(8, result.sourceVersion)
        assertEquals(before, dir.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertTrue(layout.exists() && data.exists() && xml.exists())
    }
}
