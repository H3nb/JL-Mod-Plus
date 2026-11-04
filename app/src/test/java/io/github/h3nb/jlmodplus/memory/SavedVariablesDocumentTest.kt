/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class SavedVariablesDocumentTest {
    @Test fun portableDocumentAcceptsTypedRootedLocatorAndRejectsIncompatibleFields() {
        val document = """{"schema":2,"items":[{"id":"hp","label":"Hit points","locator":{"rootKind":1,"rootClass":"guest.Game","segments":[{"owner":"guest.Game","name":"hp","type":"int","index":-1}],"arrayIndex":-1}}]}"""
        SavedVariablesDocument.validate(document.toByteArray())

        assertThrows(IOException::class.java) {
            SavedVariablesDocument.validate(document.replace("guest.Game", "").toByteArray())
        }
        assertThrows(IOException::class.java) {
            SavedVariablesDocument.validate(
                document.replace("\"index\":-1", "\"index\":0").toByteArray(),
            )
        }
        assertThrows(IOException::class.java) {
            SavedVariablesDocument.validate(
                document.replace("\"schema\":2", "\"schema\":1").toByteArray(),
            )
        }
    }
    @Test fun nestedArrayLocatorIsValidButWrongArrayTypeIsRejected() {
        val nested = """{"schema":3,"items":[{"id":"hp","label":"Inventory","locator":
            {"rootKind":1,"rootClass":"guest.Game","steps":[
            {"kind":1,"owner":"guest.Game","name":"items","type":"[[B"},
            {"kind":2,"arrayType":"[[B","index":1},
            {"kind":2,"arrayType":"[B","index":2}]}}]}"""
        SavedVariablesDocument.validate(nested.toByteArray())
        assertThrows(IOException::class.java) {
            SavedVariablesDocument.validate(
                nested.replace("\"arrayType\":\"[B\"", "\"arrayType\":\"[I\"")
                    .toByteArray(),
            )
        }
    }

    @Test fun mergePreservesLocalLabelsAndReassignsConflictingImportedIds() {
        val old = """{"schema":2,"items":[{"id":"hp","label":"My HP","locator":
            {"rootKind":1,"rootClass":"guest.Game","segments":[
            {"owner":"guest.Game","name":"hp","type":"int","index":-1}],"arrayIndex":-1}}]}"""
        val incoming = """{"schema":3,"items":[
            {"id":"external","label":"External HP","locator":{"rootKind":1,
                "rootClass":"guest.Game","steps":[{"kind":1,"owner":"guest.Game",
                "name":"hp","type":"int"}]}},
            {"id":"hp","label":"Coins","locator":{"rootKind":1,
                "rootClass":"guest.Game","steps":[{"kind":1,"owner":"guest.Game",
                "name":"coins","type":"int"}]}}]}"""
        val merged = SavedVariablesDocument.merge(old.toByteArray(), incoming.toByteArray())
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
        SavedVariablesDocument.validate(merged.bytes)
        val doc = Json.parseToJsonElement(merged.bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(3, doc["schema"]!!.jsonPrimitive.content.toInt())
        val rows = doc["items"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("My HP", rows[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("Coins", rows[1].jsonObject["label"]!!.jsonPrimitive.content)
        org.junit.jupiter.api.Assertions.assertNotEquals(
            rows[0].jsonObject["id"]!!.jsonPrimitive.content,
            rows[1].jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

}
