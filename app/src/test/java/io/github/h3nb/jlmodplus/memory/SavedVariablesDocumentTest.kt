/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertThrows
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
}
