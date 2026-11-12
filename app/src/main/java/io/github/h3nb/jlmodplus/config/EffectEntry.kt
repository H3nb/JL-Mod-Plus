// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A profile-owned use of an effect. Source definitions and GPU objects are not persisted.
 * Legacy selection retains file paths and tuning values for missing-file recovery.
 */
@Serializable
data class EffectEntry(
    @SerialName("Key") @JvmField val key: String,
    @SerialName("Builtin") @JvmField val builtinId: String? = null,
    @SerialName("User") @JvmField val user: ShaderSelection? = null,
    @SerialName("Parameters") @JvmField val parameters: Map<String, Float> = emptyMap(),
    @SerialName("NativeInput") @JvmField val nativeInput: Boolean = false,
) {
    init {
        require((builtinId != null) != (user != null)) { "Exactly one effect source is required" }
        require(!nativeInput || user != null) { "Native input applies only to user shaders" }
    }

    companion object {
        @JvmStatic fun builtin(id: String): EffectEntry =
            EffectEntry(UUID.randomUUID().toString(), builtinId = id)

        @JvmStatic fun legacy(selection: ShaderSelection): EffectEntry =
            EffectEntry(UUID.randomUUID().toString(), user = selection)
    }
}
