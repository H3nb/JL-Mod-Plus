// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EffectStackProfileTest {
    @Test
    fun migratesReleasedLegacyShaderIntoSingleEffectWithoutLosingTuning() {
        val old = """{"Version":9,"GraphicsMode":1,
            "Shader":{"fragment":"filter.fsh","vertex":"filter.vsh",
                      "name":"Custom","Settings":[0.4,0.2,0.0,1.0]}}"""
        val result = ProfileJson.decode(old, false)
        assertEquals(9, result.sourceVersion)
        assertTrue(result.needsPublication)
        assertNull(result.model.shader)
        val migrated = result.model.effects.single()
        assertEquals("filter.fsh", migrated.user!!.fragment)
        assertEquals("filter.vsh", migrated.user.vertex)
        assertEquals(listOf(0.4f, 0.2f, 0f, 1f), migrated.user.values)
        assertTrue(migrated.enabled)
        val reread = ProfileJson.decode(ProfileJson.encode(result.model), false)
        assertEquals(result.model.effects, reread.model.effects)
        assertFalse(reread.needsPublication)
    }

    @Test
    fun preservesOrderRepeatedInstancesAndTheirIndependentParameters() {
        val a = EffectEntry("a", builtinId = "color", parameters = mapOf("uContrast" to 1.5f))
        val legacy = ShaderSelection.fromInfo(ShaderInfo("User", "Author").apply {
            vertex = "a.vsh"
            fragment = "a.fsh"
            values = floatArrayOf(0.7f, 0f, 0f, 0f)
        })!!
        val b = EffectEntry("b", user = legacy, enabled = false)
        val c = EffectEntry("c", builtinId = "color", parameters = mapOf("uContrast" to 0.6f))
        val source = ProfileModel(effects = listOf(a, b, c))
        val saved = ProfileJson.decode(ProfileJson.encode(source), false).model
        assertEquals(listOf("a", "b", "c"), saved.effects.map { it.key })
        assertEquals(1.5f, saved.effects[0].parameters["uContrast"])
        assertFalse(saved.effects[1].enabled)
        assertEquals(0.7f, saved.effects[1].user!!.values!![0])
        assertEquals(0.6f, saved.effects[2].parameters["uContrast"])
    }

    @Test
    fun deepCopiesMutableEffectParametersAcrossProfileAndEditorBoundaries() {
        val mutable = mutableMapOf("uStrength" to 0.45f)
        val src = ProfileModel(effects = listOf(
            EffectEntry("vignette", builtinId = "vignette", parameters = mutable)
        ))
        val owned = ProfileJson.ownedCopy(src)
        mutable["uStrength"] = 0.95f
        assertEquals(0.45f, owned.effects.single().parameters["uStrength"])
        assertThrows(UnsupportedOperationException::class.java) {
            (owned.effects.single().parameters as MutableMap<String, Float>)["uStrength"] = 0.7f
        }
        val form = ConfigFormState.fromProfile(owned, "")
        assertEquals(0.45f, form.effects.single().parameters["uStrength"])
        val newProfile = form.applyTo(ProfileModel())
        assertEquals(owned.effects, newProfile.effects)
    }
}
