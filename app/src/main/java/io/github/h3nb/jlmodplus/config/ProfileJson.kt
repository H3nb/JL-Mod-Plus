/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.config

import java.math.BigDecimal
import java.util.Collections
import javax.microedition.shell.timing.TimingMode
import io.github.h3nb.jlmodplus.mmapi.camera.CameraCaptureOptions
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*

/** One historical input boundary; the generated serializer is the only profile writer. */
object ProfileJson {
    internal val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    class UnsupportedVersion(@JvmField val version: Int) : IllegalArgumentException("Unsupported config version $version")

    class Decoded(
        @JvmField val model: ProfileModel,
        @JvmField val sourceVersion: Int,
        @JvmField val needsPublication: Boolean,
    )

    @JvmStatic fun decode(text: String, themeLinked: Boolean): Decoded =
        decodeTree(format.parseToJsonElement(text), themeLinked)

    @JvmStatic fun decodeXml(values: Map<String, Any?>, themeLinked: Boolean): Decoded =
        decodeTree(xmlValue(values), themeLinked)

    private fun decodeTree(tree: JsonElement, themeLinked: Boolean): Decoded {
        require(tree is JsonObject) { "Profile must be a JSON object" }
        val version = tree["Version"]?.takeUnless { it == JsonNull }?.let(::integral) ?: 0
        if (version !in 0..ProfileModel.VERSION) throw UnsupportedVersion(version)
        val normalized = normalize(tree, ProfileModel.serializer().descriptor).jsonObject.toMutableMap()
        // Historical alternate spelling follows source order, as the old reader did.
        tree.forEach { (name, value) ->
            if (name == "Controller" || name == "controller") normalized["Controller"] = value
        }
        normalized.remove("controller")
        normalized["ScreenBackgroundMode"] = JsonPrimitive(if (version < ProfileModel.VERSION) {
            if (themeLinked) BackgroundMode.THEME else BackgroundMode.CUSTOM
        } else backgroundMode(tree["ScreenBackgroundMode"]))
        normalized["KeyMappings"] = format.encodeToJsonElement(decodeMappings(tree["KeyMappings"]))
        val model = format.decodeFromJsonElement<ProfileModel>(JsonObject(normalized))
        if (!tree.containsKey("SystemPropertyOverrides")) {
            val oldProperties = tree["SystemProperties"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            model.systemProperties = oldProperties
            model.systemPropertyOverrides = SystemPropertyLayers.migrate(oldProperties, preserveMissingAsDeleted = version != 0)
        }
        if (version == 0) {
            if (legacyBoolean(tree["HwAcceleration"])) model.graphicsMode = 3
            // Legacy properties are converted by SystemPropertyLayers.
        }
        if (version <= 1) {
            val w = model.screenWidth
            val h = model.screenHeight
            model.fontAA = if (w > 0) { if (h > 0) minOf(w, h) >= 240 else w >= 240 } else h <= 0 || h >= 240
        }
        if (version <= 2) {
            model.screenScaleType = if (legacyBoolean(tree["ScreenScaleToFit"])) {
                if (legacyBoolean(tree["ScreenKeepAspectRatio"])) 1 else 2
            } else 0
            model.screenGravity = 1
        }
        model.version = ProfileModel.VERSION
        model.screenScaleRatio = ProfileModel.effectiveScreenScaleRatio(model.screenScaleType, model.screenScaleRatio)
        val timing = TimingMode.sanitize(model.timingMode)
        val needsPublication = version < ProfileModel.VERSION || timing != model.timingMode
        model.timingMode = timing
        model.cameraDefaultFacing = CameraCaptureOptions.normalizeFacing(model.cameraDefaultFacing)
        model.cameraResolution = CameraCaptureOptions.normalizeResolution(model.cameraResolution)
        return Decoded(ownedCopy(model), version, needsPublication)
    }

    @JvmStatic fun encode(source: ProfileModel): String = format.encodeToString(source.copy(
        version = ProfileModel.VERSION,
        screenBackgroundMode = BackgroundMode.sanitize(source.screenBackgroundMode),
        screenScaleRatio = ProfileModel.effectiveScreenScaleRatio(source.screenScaleType, source.screenScaleRatio),
        cameraDefaultFacing = CameraCaptureOptions.normalizeFacing(source.cameraDefaultFacing),
        cameraResolution = CameraCaptureOptions.normalizeResolution(source.cameraResolution),
    ))

    /** Copy value collections on entry; never retain an externally mutable tree backing map. */
    @JvmStatic fun ownedCopy(source: ProfileModel): ProfileModel = source.copy(
        shader = source.shader?.let { it.copy(values = it.values?.ownedList()) },
        customKeys = source.customKeys?.ownedList(),
        keyMappings = source.keyMappings.ownedMap(),
        controller = source.controller?.let(::ownedJson),
        systemPropertyOverrides = Collections.unmodifiableMap(HashMap(source.systemPropertyOverrides)),
    )

    @JvmStatic fun ownedJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { ownedJson(it.value) }))
        is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::ownedJson)))
        else -> value
    }

    @JvmStatic fun copyJson(value: JsonElement?): JsonElement? = value?.let(::ownedJson)

    @JvmStatic fun tree(source: ProfileModel): JsonElement = format.encodeToJsonElement(source)

    /** Exact numeric value equality, independent of the opaque payload's stored spelling. */
    @JvmStatic fun sameValue(left: JsonElement, right: JsonElement): Boolean {
        if (left === right) return true
        return when {
            left is JsonObject && right is JsonObject -> left.keys == right.keys &&
                left.all { (key, value) -> sameValue(value, right.getValue(key)) }
            left is JsonArray && right is JsonArray -> left.size == right.size &&
                left.indices.all { sameValue(left[it], right[it]) }
            left is JsonPrimitive && right is JsonPrimitive -> {
                if (left == right) true
                else if (left == JsonNull || right == JsonNull || left.isString || right.isString) false
                else try { BigDecimal(left.content).compareTo(BigDecimal(right.content)) == 0 }
                catch (_: NumberFormatException) { false }
            }
            else -> false
        }
    }

    private fun backgroundMode(value: JsonElement?): Int {
        if (value !is JsonPrimitive || value.isString || !value.content.matches(Regex("-?\\d+"))) return BackgroundMode.CUSTOM
        return BackgroundMode.sanitize(value.content.toIntOrNull() ?: BackgroundMode.CUSTOM)
    }

    private fun integral(value: JsonElement): Int = BigDecimal(value.jsonPrimitive.content.trim()).intValueExact()

    private fun legacyBoolean(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> {
            require(value.isString || value.booleanOrNull != null) { "Expected boolean" }
            value.content.equals("true", ignoreCase = true)
        }
        else -> error("Expected boolean")
    }

    /** Only known profile scalar fields are coerced; Controller is deliberately never traversed. */
    private fun normalize(value: JsonElement, descriptor: SerialDescriptor): JsonElement {
        if (value == JsonNull) return value
        return when (descriptor.kind) {
            PrimitiveKind.INT -> JsonPrimitive(integral(value))
            PrimitiveKind.FLOAT -> JsonPrimitive(value.jsonPrimitive.content.toFloat().also { require(it.isFinite()) })
            PrimitiveKind.BOOLEAN -> JsonPrimitive(legacyBoolean(value))
            PrimitiveKind.STRING -> JsonPrimitive(value.jsonPrimitive.content)
            else -> when {
                descriptor.serialName == "kotlinx.serialization.json.JsonElement" || descriptor.kind == StructureKind.MAP -> value
                value is JsonObject -> JsonObject(value.mapNotNull { (key, entry) ->
                    val index = descriptor.getElementIndex(key)
                    when {
                        key == "Controller" || key == "controller" || key == "KeyMappings" ||
                            key == "ScreenBackgroundMode" -> key to entry
                        index < 0 -> null
                        entry == JsonNull && !descriptor.getElementDescriptor(index).isNullable -> null
                        else -> key to normalize(entry, descriptor.getElementDescriptor(index))
                    }
                }.toMap())
                value is JsonArray -> JsonArray(value.map { normalize(it, descriptor.getElementDescriptor(0)) })
                else -> value // The generated decoder reports the structural error.
            }
        }
    }

    internal fun decodeMappings(value: JsonElement?): Map<Int, Int> {
        if (value == null || value == JsonNull) return emptyMap()
        if (value is JsonPrimitive && value.isString) {
            // Android's historical JSONObject.getInt coerced scalar numbers and retained
            // entries read before a malformed array item. Keep that XML-only behavior local.
            val result = linkedMapOf<Int, Int>()
            try {
                format.parseToJsonElement(value.content).jsonArray.forEach {
                    result[legacyMappingInt(it.jsonObject.getValue("key"))] =
                        legacyMappingInt(it.jsonObject.getValue("value"))
                }
            } catch (_: RuntimeException) {
                // Old malformed string mappings represented the successfully read prefix.
            }
            return result
        }
        return value.jsonObject.map { (key, entry) -> key.toInt() to integral(entry) }.toMap()
    }

    private fun legacyMappingInt(value: JsonElement): Int {
        val primitive = value.jsonPrimitive
        if (!primitive.isString) primitive.longOrNull?.let { return it.toInt() }
        return primitive.content.toDouble().toInt()
    }

    private fun xmlValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to xmlValue(it.value) })
        is Iterable<*> -> JsonArray(value.map(::xmlValue))
        is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
        is ByteArray -> JsonArray(value.map { JsonPrimitive(it) })
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
        is Array<*> -> JsonArray(value.map(::xmlValue))
        else -> error("Unsupported XML value ${value.javaClass.name}")
    }
}
