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
}
