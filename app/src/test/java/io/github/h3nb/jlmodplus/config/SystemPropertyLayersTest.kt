/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SystemPropertyLayersTest {
    private val old = SystemPropertyLayers.currentFactory()
    private val current = """
        microedition.configuration: CLDC-1.1
        microedition.platform: Nokia 6233
        supports.video.capture: true
        supports.recording: false
        new.device.property: available
    """.trimIndent()

    @Test fun factoryRevisionReachesInheritedPropertiesWithoutChangingOverrides() {
        val legacy = old.replace("microedition.platform: Nokia 6233", "microedition.platform: SonyEricssonK800i")
        val migrated = SystemPropertyLayers.migrate(legacy)
        assertEquals("SonyEricssonK800i", migrated["microedition.platform"])
        assertFalse(migrated.containsKey("supports.video.capture"))
        assertFalse(migrated.containsKey("microedition.configuration"))
        val effective = SystemPropertyLayers.effective(migrated, current)
        assertTrue(effective.contains("microedition.platform: SonyEricssonK800i"))
        assertTrue(effective.contains("supports.video.capture: true"))
        assertTrue(effective.contains("microedition.configuration: CLDC-1.1"))
        assertTrue(effective.contains("new.device.property: available"))
    }

    @Test fun removedPropertyAndExplicitFalseSurviveFutureFactoryChangesAndSerialization() {
        val model = ProfileModel.fresh(current)
        val edited = model.systemProperties!!
            .replace("supports.video.capture: true", "supports.video.capture: false")
            .lineSequence().filterNot { it.startsWith("supports.recording:") }.joinToString("\n")
        SystemPropertyLayers.applyEditorText(model, edited)
        assertEquals("false", model.systemPropertyOverrides["supports.video.capture"])
        assertTrue(model.systemPropertyOverrides.containsKey("supports.recording"))
        assertNull(model.systemPropertyOverrides["supports.recording"])
        val encoded = ProfileJson.encode(model)
        val parsed = Json.parseToJsonElement(encoded).jsonObject
        assertFalse(parsed.containsKey("SystemProperties"))
        assertTrue(parsed.containsKey("SystemPropertyOverrides"))
        val decoded = ProfileJson.decode(encoded, false).model
        val updated = current.replace("new.device.property: available", "new.device.property: upgraded")
        SystemPropertyLayers.hydrate(decoded, updated)
        assertTrue(decoded.systemProperties!!.contains("new.device.property: upgraded"))
        assertTrue(decoded.systemProperties!!.contains("supports.video.capture: false"))
        assertFalse(decoded.systemProperties!!.contains("supports.recording:"))
        assertEquals(model.systemPropertyOverrides, decoded.systemPropertyOverrides)
    }

    @Test fun unchangedEditorDraftDoesNotFreezeDefaults() {
        val model = ProfileModel.fresh(current)
        SystemPropertyLayers.applyEditorText(model, model.systemProperties)
        assertTrue(model.systemPropertyOverrides.isEmpty())
        SystemPropertyLayers.hydrate(model, current.replace("supports.video.capture: true", "supports.video.capture: false"))
        assertTrue(model.systemProperties!!.contains("supports.video.capture: false"))
    }

    @Test fun oldSchemaIsImportedButNewSchemaDoesNotRepeatMigration() {
        val legacy = """{"Version":7,"SystemProperties":"microedition.platform: Custom\\nsupports.video.capture: false"}"""
        val oldModel = ProfileJson.decode(legacy, false).model
        assertEquals("Custom", oldModel.systemPropertyOverrides["microedition.platform"])
        // Historical absent values are tombstones, rather than accidentally reintroduced defaults.
        assertNull(oldModel.systemPropertyOverrides["supports.recording"])
        val newForm = ProfileJson.encode(oldModel)
        val decoded = ProfileJson.decode(newForm, false).model
        assertEquals(oldModel.systemPropertyOverrides, decoded.systemPropertyOverrides)
        assertFalse(Json.parseToJsonElement(newForm).jsonObject.containsKey("SystemProperties"))
    }
}
