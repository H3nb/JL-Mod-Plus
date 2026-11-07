// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CustomShaderSelectionTest {
    @Test
    fun customStagesSurviveProfileSerializationAndFormCopies() {
        val original = ShaderInfo("Custom pipeline", "JL-Mod Plus").also {
            it.customStages = listOf(
                CustomShaderStage("shaders_glsl/edges.glsl", 4, false),
                CustomShaderStage("shaders_glsl/final.glsl", 1, null),
            )
        }
        val selected = ShaderSelection.fromInfo(original)
        val serialized = Json.encodeToString(selected)
        val restored = Json.decodeFromString<ShaderSelection>(serialized)

        assertEquals(original.customStages, restored.customStages)
        assertEquals(original.customStages, restored.toInfo().copyForDialog().customStages)
        assertEquals(null, restored.preset)
    }

    @Test
    fun legacyStagePreservesModePathsAndPerPassSettings() {
        val source = ShaderInfo("Custom pipeline", "JL-Mod Plus").apply {
            customMode = "legacy"
            customStages = listOf(
                CustomShaderStage("legacy/old.fsh", 2, false,
                    "legacy/old.vsh", listOf(1f, 2f, 3f, 4f), "Old shader"),
            )
        }
        val restored = Json.decodeFromString<ShaderSelection>(
            Json.encodeToString(ShaderSelection.fromInfo(source)),
        )
        assertEquals("legacy", restored.customMode)
        assertEquals("legacy/old.vsh", restored.customStages?.single()?.legacyVertex)
        assertEquals(listOf(1f, 2f, 3f, 4f),
            restored.toInfo().copyForDialog().customStages?.single()?.legacyValues)
    }

}
