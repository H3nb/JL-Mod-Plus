/*
 * Copyright 2020-2026 Yury Kharchenko
 * Modified for JL-Mod Plus.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.config

import android.util.SparseIntArray
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The persisted selection is independent of catalog metadata and mutable tuning dialogs. */
@Serializable
@ConsistentCopyVisibility
data class ShaderSelection internal constructor(
    @JvmField val fragment: String? = null,
    @JvmField val vertex: String? = null,
    @JvmField val name: String? = null,
    @JvmField val author: String? = null,
    @SerialName("Settings") @JvmField val values: List<Float>? = null,
) {
    fun valuesArray(): FloatArray? = values?.toFloatArray()

    fun toInfo(): ShaderInfo = ShaderInfo(name, author).also {
        it.fragment = fragment
        it.vertex = vertex
        it.values = values?.toFloatArray()
    }

    companion object {
        @JvmStatic fun fromInfo(info: ShaderInfo?): ShaderSelection? = info?.let {
            ShaderSelection(it.fragment, it.vertex, it.name, it.author,
                it.values?.toList()?.let(Collections::unmodifiableList))
        }
    }
}

/** Value entries used by config; the guest-facing KeyModel remains a separate helper. */
@Serializable
data class CustomKey(
    @SerialName("DefaultKeyCode") @JvmField val defaultKeyCode: Int = 0,
    @SerialName("CustomKeyCode") @JvmField val customKeyCode: Int = 0,
    @SerialName("GameAction") @JvmField val gameAction: Int = 0,
    @SerialName("KeyName") @JvmField val keyName: String? = null,
)

internal fun <T> List<T>.ownedList(): List<T> = Collections.unmodifiableList(ArrayList(this))
internal fun <K, V> Map<K, V>.ownedMap(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))

/** Android's resolved input map is constructed only at editor/runtime boundaries. */
object ProfileKeyMappings {
    @JvmStatic fun toSparse(values: Map<Int, Int>): SparseIntArray = SparseIntArray(values.size).apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    @JvmStatic fun fromSparse(values: SparseIntArray?): Map<Int, Int> = buildMap {
        if (values != null) for (index in 0 until values.size()) put(values.keyAt(index), values.valueAt(index))
    }

    @JvmStatic fun encode(values: SparseIntArray): String =
        ProfileJson.format.encodeToString(fromSparse(values))

    @JvmStatic fun decode(value: String): SparseIntArray =
        toSparse(ProfileJson.decodeMappings(ProfileJson.format.parseToJsonElement(value)))
}
