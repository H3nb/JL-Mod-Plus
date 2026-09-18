/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.input

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Collections
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Immutable, Android-free analog controller configuration.
 *
 * Digital gamepad buttons are deliberately absent from this model. ProfileModel.keyMappings and
 * KeyMapper remain the one guest digital mapping, while HostCommand owns application navigation.
 * This subtree stores only analog processing, pointer output, trigger adaptation, and calibration.
 * Unknown fields are retained until an explicit edit/reset so future config data is not silently
 * discarded.
 */
class ControllerConfig private constructor(
    val enabled: Boolean,
    val leftStick: StickSettings,
    val rightStick: StickSettings,
    val triggers: TriggerSettings,
    val pointer: PointerSettings,
    calibrations: Map<String, GamepadCalibration>,
    val schemaVersion: Int,
    private val originalJson: JsonObject?,
    val unsupportedReason: String?,
    val shouldWriteController: Boolean,
    val resolutionSource: ResolutionSource,
) {
    /** False for a newer/invalid payload that remains opaque at runtime. */
    val isSupported: Boolean
        get() = unsupportedReason == null

    /** A clear user-facing explanation for an unsupported payload, when one exists. */
    val notice: String?
        get() = unsupportedReason

    /** Validated, device-independent calibration profiles keyed by descriptor/capability. */
    val calibrations: Map<String, GamepadCalibration> = immutableCalibrations(calibrations)

    /** Returns a defensive copy of the source subtree, if this config came from JSON. */
    val rawJson: JsonObject?
        get() = originalJson?.deepCopy()

    fun validate(): ValidationResult {
        val issues = ArrayList<ValidationIssue>()
        if (unsupportedReason != null) {
            issues.add(ValidationIssue("controller", unsupportedReason))
            return ValidationResult(issues)
        }
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            issues.add(
                ValidationIssue(
                    "schemaVersion",
                    "must be $CURRENT_SCHEMA_VERSION for this runtime",
                ),
            )
        }
        issues += triggers.validationIssues("triggers")
        issues += leftStick.validationIssues("sticks.left")
        issues += rightStick.validationIssues("sticks.right")
        issues += pointer.validationIssues("pointer")
        return ValidationResult(issues)
    }

    fun toBuilder(): Builder = Builder(this)

    /** Explicit recovery from an unsupported payload or a user-requested reset. */
    fun reset(): ControllerConfig = defaultNavigation(shouldWrite = true)

    fun withCalibration(signature: String, calibration: GamepadCalibration): ControllerConfig =
        toBuilder().calibration(signature, calibration).build()

    fun withoutCalibration(signature: String): ControllerConfig =
        toBuilder().removeCalibration(signature).build()

    /**
     * Merge the analog model into the source JSON. The emitted representation is clean schema v2:
     * legacy mode/preset/direction/bindings fields are removed and digital ownership is not
     * reconstructed. Unknown unrelated fields remain intact.
     */
    fun toJson(): JsonObject {
        if (unsupportedReason != null && originalJson != null) {
            return originalJson.deepCopy()
        }

        val root = originalJson?.deepCopy() ?: JsonObject()
        root.addProperty(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        root.addProperty(KEY_ENABLED, enabled)
        root.remove(KEY_MODE)
        root.remove(KEY_PRESET)
        root.remove(KEY_DIRECTION_MODE)
        root.remove(KEY_BINDINGS)
        root.remove(KEY_TRIGGER)
        root.remove(KEY_TRIGGER_PRESS)
        root.remove(KEY_TRIGGER_RELEASE)

        val sticks = mergeObject(root, KEY_STICKS)
        writeStick(sticks, STICK_LEFT, leftStick)
        writeStick(sticks, STICK_RIGHT, rightStick)

        val triggerObject = mergeObject(root, KEY_TRIGGERS)
        triggerObject.addProperty(KEY_TRIGGER_PRESS, triggers.pressThreshold)
        triggerObject.addProperty(KEY_TRIGGER_RELEASE, triggers.releaseThreshold)
        triggerObject.addProperty(KEY_TRIGGER_ENABLED, triggers.enabled)

        val pointerObject = mergeObject(root, KEY_POINTER)
        writePointer(pointerObject, pointer)

        if (calibrations.isNotEmpty() || root.has(KEY_CALIBRATIONS)) {
            val calibrationObject = mergeObject(root, KEY_CALIBRATIONS)
            val originalCalibrations = parseCalibrations(originalJson?.get(KEY_CALIBRATIONS))
            originalCalibrations.keys
                .filter { it !in calibrations }
                .forEach(calibrationObject::remove)
            calibrations.forEach { (signature, calibration) ->
                val existing = calibrationObject.get(signature)
                calibrationObject.add(signature, mergeCalibration(existing, calibration))
            }
        }
        return root
    }

    fun toJsonString(gson: Gson = Gson()): String = gson.toJson(toJson())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ControllerConfig) return false
        return enabled == other.enabled &&
            leftStick == other.leftStick &&
            rightStick == other.rightStick &&
            triggers == other.triggers &&
            pointer == other.pointer &&
            calibrations == other.calibrations &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + leftStick.hashCode()
        result = 31 * result + rightStick.hashCode()
        result = 31 * result + triggers.hashCode()
        result = 31 * result + pointer.hashCode()
        result = 31 * result + calibrations.hashCode()
        result = 31 * result + schemaVersion
        return result
    }

    override fun toString(): String =
        "ControllerConfig(enabled=$enabled, leftStick=$leftStick, rightStick=$rightStick, " +
            "triggers=$triggers, pointer=$pointer, calibrations=${calibrations.keys}, " +
            "schemaVersion=$schemaVersion, unsupportedReason=$unsupportedReason)"

    class Builder internal constructor(
        private var enabled: Boolean,
        private var leftStick: StickSettings,
        private var rightStick: StickSettings,
        private var triggers: TriggerSettings,
        private var pointer: PointerSettings,
        calibrations: Map<String, GamepadCalibration>,
        private var schemaVersion: Int,
        private var originalJson: JsonObject?,
        private var unsupportedReason: String?,
        private var shouldWriteController: Boolean,
        private var resolutionSource: ResolutionSource,
        private var preserveUnsupportedPayload: Boolean,
    ) {
        private var mutableCalibrations = LinkedHashMap(calibrations)
        private var modified = false

        constructor() : this(
            enabled = true,
            leftStick = defaultLeftStick(),
            rightStick = defaultRightStick(),
            triggers = TriggerSettings(),
            pointer = PointerSettings(),
            calibrations = emptyMap(),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            originalJson = null,
            unsupportedReason = null,
            shouldWriteController = true,
            resolutionSource = ResolutionSource.EXPLICIT,
            preserveUnsupportedPayload = false,
        )

        internal constructor(source: ControllerConfig) : this(
            enabled = source.enabled,
            leftStick = source.leftStick,
            rightStick = source.rightStick,
            triggers = source.triggers,
            pointer = source.pointer,
            calibrations = source.calibrations,
            schemaVersion = source.schemaVersion,
            originalJson = source.originalJson?.deepCopy(),
            unsupportedReason = source.unsupportedReason,
            shouldWriteController = source.shouldWriteController,
            resolutionSource = source.resolutionSource,
            preserveUnsupportedPayload = source.unsupportedReason != null,
        )

        private fun markModified() {
            modified = true
            if (unsupportedReason != null) {
                unsupportedReason = null
                schemaVersion = CURRENT_SCHEMA_VERSION
                preserveUnsupportedPayload = false
                resolutionSource = ResolutionSource.EXPLICIT
            }
        }

        fun enabled(value: Boolean): Builder = apply {
            enabled = value
            markModified()
        }

        fun sticks(left: StickSettings, right: StickSettings): Builder = apply {
            leftStick = left
            rightStick = right
            markModified()
        }

        fun leftStick(value: StickSettings): Builder = apply {
            leftStick = value
            markModified()
        }

        fun rightStick(value: StickSettings): Builder = apply {
            rightStick = value
            markModified()
        }

        fun triggers(value: TriggerSettings): Builder = apply {
            triggers = value
            markModified()
        }

        fun triggerThresholds(pressThreshold: Double, releaseThreshold: Double): Builder =
            triggers(TriggerSettings(pressThreshold, releaseThreshold, triggers.enabled))

        fun pointer(value: PointerSettings): Builder = apply {
            pointer = value
            markModified()
        }

        fun calibrations(values: Map<String, GamepadCalibration>): Builder = apply {
            mutableCalibrations = LinkedHashMap(values)
            markModified()
        }

        fun calibration(signature: String, value: GamepadCalibration): Builder = apply {
            require(signature.isNotBlank()) { "calibration signature must not be blank" }
            mutableCalibrations[signature] = value
            markModified()
        }

        fun removeCalibration(signature: String): Builder = apply {
            mutableCalibrations.remove(signature)
            markModified()
        }

        fun schemaVersion(value: Int): Builder = apply {
            schemaVersion = value
            markModified()
        }

        fun build(): ControllerConfig {
            val keepOpaque = preserveUnsupportedPayload && !modified
            val candidate = ControllerConfig(
                enabled = enabled,
                leftStick = leftStick,
                rightStick = rightStick,
                triggers = triggers,
                pointer = pointer,
                calibrations = mutableCalibrations,
                schemaVersion = schemaVersion,
                originalJson = originalJson?.deepCopy(),
                unsupportedReason = if (keepOpaque) unsupportedReason else null,
                shouldWriteController = shouldWriteController || modified,
                resolutionSource = resolutionSource,
            )
            if (keepOpaque) return candidate
            val result = candidate.validate()
            require(result.isValid) { result.messages().joinToString("; ") }
            return candidate
        }

        fun buildOrNull(): ControllerConfig? = try {
            build()
        } catch (_: IllegalArgumentException) {
            null
        }

        fun validation(): ValidationResult {
            val candidate = ControllerConfig(
                enabled = enabled,
                leftStick = leftStick,
                rightStick = rightStick,
                triggers = triggers,
                pointer = pointer,
                calibrations = mutableCalibrations,
                schemaVersion = schemaVersion,
                originalJson = originalJson?.deepCopy(),
                unsupportedReason = if (preserveUnsupportedPayload && !modified) unsupportedReason else null,
                shouldWriteController = shouldWriteController || modified,
                resolutionSource = resolutionSource,
            )
            return candidate.validate()
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun defaultNavigation(): ControllerConfig = defaultNavigation(shouldWrite = false)

        @JvmStatic
        fun parse(json: JsonObject?): ControllerConfig {
            if (json == null) return defaultNavigation()
            val original = json.deepCopy()
            return try {
                parseSupported(json, original)
            } catch (failure: ParseFailure) {
                unsupported(
                    original = original,
                    reason = failure.message ?: "invalid controller configuration",
                    schemaVersion = schemaVersionHint(json),
                )
            } catch (failure: RuntimeException) {
                unsupported(
                    original = original,
                    reason = failure.message ?: "invalid controller configuration",
                    schemaVersion = schemaVersionHint(json),
                )
            }
        }

        @JvmStatic
        fun parse(json: String?): ControllerConfig {
            if (json == null) return defaultNavigation()
            return try {
                val element = JsonParser.parseString(json)
                if (!element.isJsonObject) {
                    unsupported(JsonObject(), "controller config must be a JSON object", CURRENT_SCHEMA_VERSION)
                } else {
                    parse(element.asJsonObject)
                }
            } catch (failure: RuntimeException) {
                unsupported(
                    JsonObject(),
                    failure.message ?: "controller config is not valid JSON",
                    CURRENT_SCHEMA_VERSION,
                )
            }
        }

        /** Resolve only the analog subtree; legacy digital mappings are intentionally ignored. */
        @JvmStatic
        fun resolve(controllerJson: JsonObject?): ControllerConfig =
            if (controllerJson == null) defaultNavigation() else parse(controllerJson)

        private fun parseSupported(json: JsonObject, original: JsonObject): ControllerConfig {
            val schemaVersion = readSchemaVersion(json)
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw ParseFailure("unsupported controller schemaVersion $schemaVersion")
            }
            val enabled = readEnabled(json)
            val rootDirectionMode = readEnum(
                json,
                KEY_DIRECTION_MODE,
                DirectionMode.EIGHT.token,
                DirectionMode::fromToken,
            )

            val sticks = json.get(KEY_STICKS)
            if (sticks != null && !sticks.isJsonObject) {
                throw ParseFailure("sticks must be a JSON object")
            }
            val stickObject = sticks?.asJsonObject
            val left = parseStick(
                stickObject?.get(STICK_LEFT),
                defaultMode = StickMode.DIRECTIONS,
                defaultDirectionMode = rootDirectionMode,
                path = "sticks.left",
            )
            val right = parseStick(
                stickObject?.get(STICK_RIGHT),
                defaultMode = StickMode.UNASSIGNED,
                defaultDirectionMode = rootDirectionMode,
                path = "sticks.right",
            )

            val triggerObject = when {
                json.get(KEY_TRIGGERS)?.isJsonObject == true -> json.getAsJsonObject(KEY_TRIGGERS)
                json.get(KEY_TRIGGER)?.isJsonObject == true -> json.getAsJsonObject(KEY_TRIGGER)
                else -> null
            }
            val press = if (triggerObject != null) {
                readNumber(triggerObject, KEY_TRIGGER_PRESS, json.get(KEY_TRIGGER_PRESS), TriggerSettings.DEFAULT_PRESS)
            } else {
                readNumber(json, KEY_TRIGGER_PRESS, null, TriggerSettings.DEFAULT_PRESS)
            }
            val release = if (triggerObject != null) {
                readNumber(triggerObject, KEY_TRIGGER_RELEASE, json.get(KEY_TRIGGER_RELEASE), TriggerSettings.DEFAULT_RELEASE)
            } else {
                readNumber(json, KEY_TRIGGER_RELEASE, null, TriggerSettings.DEFAULT_RELEASE)
            }
            val triggerEnabled = triggerObject?.let {
                readBoolean(it, KEY_TRIGGER_ENABLED, false)
            } ?: readBoolean(json, KEY_TRIGGER_ENABLED, false)

            val config = create(
                enabled = enabled,
                leftStick = left,
                rightStick = right,
                triggers = TriggerSettings(press, release, triggerEnabled),
                pointer = parsePointer(json.get(KEY_POINTER)),
                calibrations = parseCalibrations(json.get(KEY_CALIBRATIONS)),
                schemaVersion = schemaVersion,
                originalJson = original,
                unsupportedReason = null,
                shouldWriteController = false,
                resolutionSource = ResolutionSource.CONTROLLER,
            )
            val validation = config.validate()
            if (!validation.isValid) {
                throw ParseFailure(validation.messages().joinToString("; "))
            }
            return config
        }

        private fun readEnabled(json: JsonObject): Boolean {
            json.get(KEY_ENABLED)?.let {
                val primitive = it.asPrimitiveOrNull()
                if (primitive == null || !primitive.isBoolean) {
                    throw ParseFailure("enabled must be a boolean")
                }
                return primitive.asBoolean
            }
            // The old experimental mode is accepted as a one-time read compatibility aid, but
            // it is never emitted and it never reintroduces the removed digital mapping table.
            val mode = readString(json, KEY_MODE, required = false, path = KEY_MODE) ?: return true
            return when (normalize(mode)) {
                "ENABLED" -> true
                "LEGACY" -> false
                else -> throw ParseFailure("mode has unknown token '$mode'")
            }
        }

        private fun parseStick(
            element: JsonElement?,
            defaultMode: StickMode,
            defaultDirectionMode: DirectionMode,
            path: String,
        ): StickSettings {
            if (element == null) {
                return StickSettings(mode = defaultMode, directionMode = defaultDirectionMode)
            }
            if (!element.isJsonObject) throw ParseFailure("$path must be a JSON object")
            val json = element.asJsonObject
            val mode = readEnum(json, KEY_STICK_MODE, defaultMode.token, StickMode::fromToken)
            val directionMode = readEnum(
                json,
                KEY_DIRECTION_MODE,
                defaultDirectionMode.token,
                DirectionMode::fromToken,
            )
            val settings = StickSettings(
                mode = mode,
                directionMode = directionMode,
                innerDeadzone = readNumber(json, KEY_INNER_DEADZONE, null, StickSettings.DEFAULT_INNER),
                outerSaturation = readNumber(json, KEY_OUTER_SATURATION, null, StickSettings.DEFAULT_OUTER),
                pressRadius = readNumber(json, KEY_PRESS_RADIUS, null, StickSettings.DEFAULT_PRESS),
                releaseRadius = readNumber(json, KEY_RELEASE_RADIUS, null, StickSettings.DEFAULT_RELEASE),
                angularHysteresisDegrees = readNumber(
                    json,
                    KEY_ANGULAR_HYSTERESIS,
                    null,
                    StickSettings.DEFAULT_ANGULAR_HYSTERESIS,
                ),
                responseExponent = readNumber(
                    json,
                    KEY_RESPONSE_EXPONENT,
                    null,
                    StickSettings.DEFAULT_RESPONSE_EXPONENT,
                ),
                invertX = readBoolean(json, KEY_INVERT_X, false),
                invertY = readBoolean(json, KEY_INVERT_Y, false),
                swapAxes = readBoolean(json, KEY_SWAP_AXES, false),
            )
            val issues = settings.validationIssues(path)
            if (issues.isNotEmpty()) throw ParseFailure(issues.joinToString("; ") { it.message })
            return settings
        }

        private fun parsePointer(element: JsonElement?): PointerSettings {
            if (element == null) return PointerSettings()
            if (!element.isJsonObject) throw ParseFailure("pointer must be a JSON object")
            val json = element.asJsonObject
            val mode = readEnum(json, KEY_POINTER_MODE, PointerMode.OFF.token, PointerMode::fromToken)
            val sourceStick = readEnum(
                json,
                KEY_POINTER_STICK,
                StickId.RIGHT.token,
                StickId::fromToken,
            )
            val clickAction = readPointerAction(json)
            val joystickMode = readEnum(
                json,
                KEY_JOYSTICK_MODE,
                VirtualAnalogStickMode.FIXED.token,
                VirtualAnalogStickMode::fromToken,
            )
            val pointer = PointerSettings(
                mode = mode,
                sourceStick = sourceStick,
                clickAction = clickAction,
                joystickMode = joystickMode,
                speed = readNumber(json, KEY_POINTER_SPEED, null, PointerSettings.DEFAULT_SPEED),
                centerX = readNumber(json, KEY_CENTER_X, null, PointerSettings.DEFAULT_CENTER),
                centerY = readNumber(json, KEY_CENTER_Y, null, PointerSettings.DEFAULT_CENTER),
                radius = readNumber(json, KEY_RADIUS, null, PointerSettings.DEFAULT_RADIUS),
            )
            val issues = pointer.validationIssues("pointer")
            if (issues.isNotEmpty()) throw ParseFailure(issues.joinToString("; ") { it.message })
            return pointer
        }

        private fun readPointerAction(json: JsonObject): PointerAction? {
            val direct = json.get(KEY_CLICK_ACTION)
            if (direct != null) {
                val token = readString(json, KEY_CLICK_ACTION, required = true, path = KEY_CLICK_ACTION)
                    ?: return null
                return PointerAction.fromToken(token)
                    ?: throw ParseFailure("$KEY_CLICK_ACTION has unknown token '$token'")
            }
            // Read the former pointer-only wrapper so a pre-refactor cursor click survives one
            // edit. Guest and host binding kinds are intentionally ignored, never imported.
            val legacy = json.get(KEY_CLICK_BINDING)
            if (legacy == null || !legacy.isJsonObject) return null
            val legacyObject = legacy.asJsonObject
            val kind = readString(legacyObject, KEY_BINDING_KIND, required = false, path = "clickBinding.kind")
                ?: return null
            if (normalize(kind) !in setOf("POINTERINTERACTION", "POINTER")) return null
            val action = readString(legacyObject, KEY_BINDING_ACTION, required = false, path = "clickBinding.action")
                ?: return null
            return PointerAction.fromToken(action)
        }

        private fun parseCalibrations(element: JsonElement?): LinkedHashMap<String, GamepadCalibration> {
            if (element == null) return LinkedHashMap()
            if (!element.isJsonObject) throw ParseFailure("calibrations must be a JSON object")
            val result = LinkedHashMap<String, GamepadCalibration>()
            element.asJsonObject.entrySet().toList().sortedBy { it.key }.forEach { entry ->
                if (entry.key.isBlank() || !entry.value.isJsonObject) return@forEach
                val channels = EnumMap<CalibrationChannel, StickProcessor.ValidatedCalibration>(
                    CalibrationChannel::class.java,
                )
                val channelObject = entry.value.asJsonObject
                CalibrationChannel.entries.forEach { channel ->
                    val parsed = parseCalibrationChannel(channelObject.get(calibrationToken(channel)), channel)
                    if (parsed != null) channels[channel] = parsed
                }
                if (channels.isNotEmpty()) result[entry.key] = GamepadCalibration(channels)
            }
            return result
        }

        private fun parseCalibrationChannel(
            element: JsonElement?,
            channel: CalibrationChannel,
        ): StickProcessor.ValidatedCalibration? {
            if (element == null || !element.isJsonObject) return null
            return try {
                val json = element.asJsonObject
                val min = requiredCalibrationNumber(json, "min")
                val max = requiredCalibrationNumber(json, "max")
                val rest = requiredCalibrationNumber(json, "rest")
                val spread = optionalCalibrationNumber(json, "spread", 0.0)
                val samples = optionalCalibrationNumber(json, "samples", 1.0)
                val interior = readBoolean(json, "restMustBeInterior", channel.isStick)
                val range = StickProcessor.MotionRangeLike(min.toFloat(), max.toFloat())
                val validRest = rest >= min && rest <= max && (!interior || (rest > min && rest < max))
                if (!range.isValid || !rest.isFinite() || !spread.isFinite() || spread < 0.0 ||
                    samples < 1.0 || samples != samples.toInt().toDouble() || !validRest
                ) {
                    null
                } else {
                    StickProcessor.ValidatedCalibration(
                        range = range,
                        rest = rest.toFloat(),
                        restSpread = spread.toFloat(),
                        sampleCount = samples.toInt(),
                        restMustBeInterior = interior,
                    )
                }
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun requiredCalibrationNumber(json: JsonObject, key: String): Double {
            val value = json.get(key)?.asPrimitiveOrNull()
                ?: throw ParseFailure("calibration.$key must be a finite number")
            if (!value.isNumber || !value.asDouble.isFinite()) {
                throw ParseFailure("calibration.$key must be a finite number")
            }
            return value.asDouble
        }

        private fun optionalCalibrationNumber(json: JsonObject, key: String, fallback: Double): Double {
            val value = json.get(key) ?: return fallback
            val primitive = value.asPrimitiveOrNull()
                ?: throw ParseFailure("calibration.$key must be a finite number")
            if (!primitive.isNumber || !primitive.asDouble.isFinite()) {
                throw ParseFailure("calibration.$key must be a finite number")
            }
            return primitive.asDouble
        }

        private fun readSchemaVersion(json: JsonObject): Int {
            val element = json.get(KEY_SCHEMA_VERSION) ?: return CURRENT_SCHEMA_VERSION
            val primitive = element.asPrimitiveOrNull()
                ?: throw ParseFailure("schemaVersion must be an integer")
            if (!primitive.isNumber) throw ParseFailure("schemaVersion must be an integer")
            val value = primitive.asDouble
            if (!value.isFinite() || value < 1.0 || value != value.toInt().toDouble()) {
                throw ParseFailure("schemaVersion must be a positive integer")
            }
            return value.toInt()
        }

        private fun schemaVersionHint(json: JsonObject): Int = try {
            readSchemaVersion(json)
        } catch (_: RuntimeException) {
            CURRENT_SCHEMA_VERSION
        }

        private fun <T> readEnum(
            json: JsonObject,
            key: String,
            defaultToken: String,
            parse: (String) -> T?,
        ): T {
            val token = readString(json, key, required = false, path = key) ?: defaultToken
            return parse(token) ?: throw ParseFailure("$key has unknown token '$token'")
        }

        private fun readString(
            json: JsonObject,
            key: String,
            required: Boolean,
            path: String,
        ): String? {
            val element = json.get(key)
            if (element == null) {
                if (required) throw ParseFailure("$path is required")
                return null
            }
            val primitive = element.asPrimitiveOrNull()
            if (primitive == null || !primitive.isString) {
                throw ParseFailure("$path must be a string")
            }
            return primitive.asString
        }

        private fun readNumber(
            json: JsonObject,
            key: String,
            fallbackElement: JsonElement?,
            defaultValue: Double,
        ): Double {
            val element = json.get(key) ?: fallbackElement ?: return defaultValue
            val primitive = element.asPrimitiveOrNull()
            if (primitive == null || !primitive.isNumber) {
                throw ParseFailure("$key must be a finite number")
            }
            val value = primitive.asDouble
            if (!value.isFinite()) throw ParseFailure("$key must be a finite number")
            return value
        }

        private fun readBoolean(json: JsonObject, key: String, defaultValue: Boolean): Boolean {
            val element = json.get(key) ?: return defaultValue
            val primitive = element.asPrimitiveOrNull()
            if (primitive == null || !primitive.isBoolean) {
                throw ParseFailure("$key must be a boolean")
            }
            return primitive.asBoolean
        }

        private fun unsupported(original: JsonObject, reason: String, schemaVersion: Int): ControllerConfig = create(
            enabled = false,
            leftStick = defaultLeftStick(),
            rightStick = defaultRightStick(),
            triggers = TriggerSettings(),
            pointer = PointerSettings(),
            calibrations = emptyMap(),
            schemaVersion = schemaVersion,
            originalJson = original,
            unsupportedReason =
                "Controller configuration is unsupported ($reason). Reset it to enable analog controls.",
            shouldWriteController = false,
            resolutionSource = ResolutionSource.CONTROLLER,
        )

        private fun defaultNavigation(shouldWrite: Boolean): ControllerConfig = create(
            enabled = true,
            leftStick = defaultLeftStick(),
            rightStick = defaultRightStick(),
            triggers = TriggerSettings(),
            pointer = PointerSettings(),
            calibrations = emptyMap(),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            originalJson = null,
            unsupportedReason = null,
            shouldWriteController = shouldWrite,
            resolutionSource = ResolutionSource.DEFAULT,
        )

        private fun create(
            enabled: Boolean,
            leftStick: StickSettings,
            rightStick: StickSettings,
            triggers: TriggerSettings,
            pointer: PointerSettings,
            calibrations: Map<String, GamepadCalibration>,
            schemaVersion: Int,
            originalJson: JsonObject?,
            unsupportedReason: String?,
            shouldWriteController: Boolean,
            resolutionSource: ResolutionSource,
        ): ControllerConfig = ControllerConfig(
            enabled = enabled,
            leftStick = leftStick,
            rightStick = rightStick,
            triggers = triggers,
            pointer = pointer,
            calibrations = calibrations,
            schemaVersion = schemaVersion,
            originalJson = originalJson?.deepCopy(),
            unsupportedReason = unsupportedReason,
            shouldWriteController = shouldWriteController,
            resolutionSource = resolutionSource,
        )

        private fun defaultLeftStick(): StickSettings = StickSettings(
            mode = StickMode.DIRECTIONS,
            directionMode = DirectionMode.EIGHT,
        )

        private fun defaultRightStick(): StickSettings = StickSettings(
            mode = StickMode.UNASSIGNED,
            directionMode = DirectionMode.EIGHT,
        )

        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_PRESET = "preset"
        private const val KEY_DIRECTION_MODE = "directionMode"
        private const val KEY_STICKS = "sticks"
        private const val KEY_BINDINGS = "bindings"
        private const val KEY_TRIGGERS = "triggers"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_TRIGGER_PRESS = "pressThreshold"
        private const val KEY_TRIGGER_RELEASE = "releaseThreshold"
        private const val KEY_TRIGGER_ENABLED = "enabled"
        private const val KEY_POINTER = "pointer"
        private const val KEY_CALIBRATIONS = "calibrations"
        private const val KEY_STICK_MODE = "mode"
        private const val KEY_INNER_DEADZONE = "innerDeadzone"
        private const val KEY_OUTER_SATURATION = "outerSaturation"
        private const val KEY_PRESS_RADIUS = "pressRadius"
        private const val KEY_RELEASE_RADIUS = "releaseRadius"
        private const val KEY_ANGULAR_HYSTERESIS = "angularHysteresisDegrees"
        private const val KEY_RESPONSE_EXPONENT = "responseExponent"
        private const val KEY_INVERT_X = "invertX"
        private const val KEY_INVERT_Y = "invertY"
        private const val KEY_SWAP_AXES = "swapAxes"
        private const val KEY_POINTER_MODE = "mode"
        private const val KEY_POINTER_STICK = "stick"
        private const val KEY_CLICK_ACTION = "clickAction"
        private const val KEY_JOYSTICK_MODE = "joystickMode"
        private const val KEY_CLICK_BINDING = "clickBinding"
        private const val KEY_BINDING_KIND = "kind"
        private const val KEY_BINDING_ACTION = "action"
        private const val KEY_POINTER_SPEED = "speed"
        private const val KEY_CENTER_X = "centerX"
        private const val KEY_CENTER_Y = "centerY"
        private const val KEY_RADIUS = "radius"
        private const val STICK_LEFT = "left"
        private const val STICK_RIGHT = "right"
    }
}

enum class DirectionMode(val token: String, val sectorWidthDegrees: Double) {
    FOUR("fourWay", 90.0),
    EIGHT("eightWay", 45.0),
    DIAGONAL_NUMBER("diagonalNumber", 45.0);

    companion object {
        fun fromToken(token: String): DirectionMode? = when (normalize(token)) {
            "FOUR", "FOURWAY" -> FOUR
            "EIGHT", "EIGHTWAY" -> EIGHT
            "DIAGONALNUMBER", "DIAGONAL" -> DIAGONAL_NUMBER
            else -> null
        }
    }
}

enum class StickMode(val token: String) {
    DIRECTIONS("directions"),
    UNASSIGNED("unassigned"),
    POINTER("pointer");

    companion object {
        fun fromToken(token: String): StickMode? = when (normalize(token)) {
            "DIRECTIONS", "DIRECTION" -> DIRECTIONS
            "UNASSIGNED", "NONE" -> UNASSIGNED
            "POINTER" -> POINTER
            else -> null
        }
    }
}

enum class StickId(val token: String) {
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromToken(token: String): StickId? = when (normalize(token)) {
            "LEFT", "LEFTSTICK" -> LEFT
            "RIGHT", "RIGHTSTICK" -> RIGHT
            else -> null
        }
    }
}

enum class PointerMode(val token: String) {
    OFF("off"),
    CURSOR("cursor"),
    TOUCH_JOYSTICK("touchJoystick");

    companion object {
        fun fromToken(token: String): PointerMode? = when (normalize(token)) {
            "OFF" -> OFF
            "CURSOR" -> CURSOR
            "TOUCHJOYSTICK", "JOYSTICK" -> TOUCH_JOYSTICK
            else -> null
        }
    }
}

enum class PointerAction(val token: String) {
    CLICK("CLICK");

    companion object {
        fun fromToken(token: String): PointerAction? = entries.firstOrNull {
            normalize(it.token) == normalize(token)
        }
    }
}

data class StickSettings(
    val mode: StickMode = StickMode.DIRECTIONS,
    val directionMode: DirectionMode = DirectionMode.EIGHT,
    val innerDeadzone: Double = DEFAULT_INNER,
    val outerSaturation: Double = DEFAULT_OUTER,
    val pressRadius: Double = DEFAULT_PRESS,
    val releaseRadius: Double = DEFAULT_RELEASE,
    val angularHysteresisDegrees: Double = DEFAULT_ANGULAR_HYSTERESIS,
    val responseExponent: Double = DEFAULT_RESPONSE_EXPONENT,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val swapAxes: Boolean = false,
) {
    internal fun validationIssues(path: String): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (!innerDeadzone.isFinite() || innerDeadzone < 0.0) {
            issues.add(ValidationIssue("$path.innerDeadzone", "must be finite and >= 0"))
        }
        if (!outerSaturation.isFinite() || outerSaturation > 1.0 || outerSaturation <= innerDeadzone) {
            issues.add(ValidationIssue("$path.outerSaturation", "must satisfy innerDeadzone < outerSaturation <= 1"))
        }
        if (!pressRadius.isFinite() || pressRadius > 1.0 || pressRadius <= innerDeadzone) {
            issues.add(ValidationIssue("$path.pressRadius", "must satisfy innerDeadzone < pressRadius <= 1"))
        }
        if (!releaseRadius.isFinite() || releaseRadius >= pressRadius || releaseRadius <= innerDeadzone) {
            issues.add(ValidationIssue("$path.releaseRadius", "must satisfy innerDeadzone < releaseRadius < pressRadius"))
        }
        if (!angularHysteresisDegrees.isFinite() ||
            angularHysteresisDegrees < 0.0 ||
            angularHysteresisDegrees >= directionMode.sectorWidthDegrees / 2.0
        ) {
            issues.add(ValidationIssue("$path.angularHysteresisDegrees", "must be finite and below half a sector"))
        }
        if (!responseExponent.isFinite() || responseExponent <= 0.0) {
            issues.add(ValidationIssue("$path.responseExponent", "must be finite and > 0"))
        }
        return issues
    }

    companion object {
        const val DEFAULT_INNER = 0.15
        const val DEFAULT_OUTER = 0.95
        const val DEFAULT_PRESS = 0.50
        const val DEFAULT_RELEASE = 0.35
        const val DEFAULT_ANGULAR_HYSTERESIS = 7.5
        const val DEFAULT_RESPONSE_EXPONENT = 1.0
    }
}

data class TriggerSettings(
    val pressThreshold: Double = DEFAULT_PRESS,
    val releaseThreshold: Double = DEFAULT_RELEASE,
    /** Trigger-to-digital adaptation is opt-in; standard MIDP has no analog trigger API. */
    val enabled: Boolean = false,
) {
    internal fun validationIssues(path: String): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (!pressThreshold.isFinite() || pressThreshold > 1.0 || pressThreshold < 0.0) {
            issues.add(ValidationIssue("$path.pressThreshold", "must be finite and in [0, 1]"))
        }
        if (!releaseThreshold.isFinite() || releaseThreshold < 0.0 || releaseThreshold >= pressThreshold) {
            issues.add(ValidationIssue("$path.releaseThreshold", "must satisfy 0 <= releaseThreshold < pressThreshold"))
        }
        return issues
    }

    companion object {
        const val DEFAULT_PRESS = 0.55
        const val DEFAULT_RELEASE = 0.40
    }
}

data class PointerSettings(
    val mode: PointerMode = PointerMode.OFF,
    val sourceStick: StickId = StickId.RIGHT,
    val clickAction: PointerAction? = null,
    val joystickMode: VirtualAnalogStickMode = VirtualAnalogStickMode.FIXED,
    val speed: Double = DEFAULT_SPEED,
    val centerX: Double = DEFAULT_CENTER,
    val centerY: Double = DEFAULT_CENTER,
    val radius: Double = DEFAULT_RADIUS,
) {
    internal fun validationIssues(path: String): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (!speed.isFinite() || speed <= 0.0) {
            issues.add(ValidationIssue("$path.speed", "must be finite and > 0"))
        }
        if (!centerX.isFinite() || centerX < 0.0 || centerX > 1.0) {
            issues.add(ValidationIssue("$path.centerX", "must be finite and in [0, 1]"))
        }
        if (!centerY.isFinite() || centerY < 0.0 || centerY > 1.0) {
            issues.add(ValidationIssue("$path.centerY", "must be finite and in [0, 1]"))
        }
        if (!radius.isFinite() || radius <= 0.0 || radius > 1.0) {
            issues.add(ValidationIssue("$path.radius", "must be finite and in (0, 1]"))
        }
        return issues
    }

    companion object {
        const val DEFAULT_SPEED = 1.0
        const val DEFAULT_CENTER = 0.5
        const val DEFAULT_RADIUS = 0.15
    }
}

data class ValidationIssue(val path: String, val message: String)

class ValidationResult internal constructor(issues: List<ValidationIssue>) {
    val issues: List<ValidationIssue> = Collections.unmodifiableList(ArrayList(issues))
    val isValid: Boolean
        get() = issues.isEmpty()

    fun messages(): List<String> = issues.map { "${it.path}: ${it.message}" }

    override fun toString(): String = if (isValid) "ValidationResult(valid)" else {
        "ValidationResult(${messages().joinToString("; ")})"
    }
}

enum class ResolutionSource {
    DEFAULT,
    CONTROLLER,
    EXPLICIT,
}

private class ParseFailure(message: String) : IllegalArgumentException(message)

private fun normalize(value: String): String = value
    .trim()
    .replace("-", "")
    .replace("_", "")
    .replace(" ", "")
    .uppercase(Locale.US)

private fun immutableCalibrations(
    values: Map<String, GamepadCalibration>,
): Map<String, GamepadCalibration> = Collections.unmodifiableMap(
    values.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { it.key }
        .associateTo(LinkedHashMap()) { it.key to it.value },
)

private fun JsonElement.asPrimitiveOrNull(): JsonPrimitive? =
    if (isJsonPrimitive) asJsonPrimitive else null

private fun mergeObject(parent: JsonObject, key: String): JsonObject {
    val current = parent.get(key)
    if (current != null && current.isJsonObject) return current.asJsonObject
    val replacement = JsonObject()
    parent.add(key, replacement)
    return replacement
}

private fun writeStick(parent: JsonObject, key: String, settings: StickSettings) {
    val json = mergeObject(parent, key)
    json.addProperty("mode", settings.mode.token)
    json.addProperty("directionMode", settings.directionMode.token)
    json.addProperty("innerDeadzone", settings.innerDeadzone)
    json.addProperty("outerSaturation", settings.outerSaturation)
    json.addProperty("pressRadius", settings.pressRadius)
    json.addProperty("releaseRadius", settings.releaseRadius)
    json.addProperty("angularHysteresisDegrees", settings.angularHysteresisDegrees)
    json.addProperty("responseExponent", settings.responseExponent)
    json.addProperty("invertX", settings.invertX)
    json.addProperty("invertY", settings.invertY)
    json.addProperty("swapAxes", settings.swapAxes)
}

private fun writePointer(parent: JsonObject, pointer: PointerSettings) {
    parent.addProperty("mode", pointer.mode.token)
    parent.addProperty("stick", pointer.sourceStick.token)
    if (pointer.clickAction == null) parent.remove("clickAction")
    else parent.addProperty("clickAction", pointer.clickAction.token)
    parent.addProperty("joystickMode", pointer.joystickMode.token)
    parent.remove("clickBinding")
    parent.addProperty("speed", pointer.speed)
    parent.addProperty("centerX", pointer.centerX)
    parent.addProperty("centerY", pointer.centerY)
    parent.addProperty("radius", pointer.radius)
}

private fun calibrationToken(channel: CalibrationChannel): String = when (channel) {
    CalibrationChannel.LEFT_X -> "left_x"
    CalibrationChannel.LEFT_Y -> "left_y"
    CalibrationChannel.RIGHT_X -> "right_x"
    CalibrationChannel.RIGHT_Y -> "right_y"
    CalibrationChannel.LEFT_TRIGGER -> "left_trigger"
    CalibrationChannel.RIGHT_TRIGGER -> "right_trigger"
}

private fun mergeCalibration(
    existing: JsonElement?,
    calibration: GamepadCalibration,
): JsonObject {
    val result = if (existing != null && existing.isJsonObject) existing.asJsonObject.deepCopy() else JsonObject()
    calibration.channels.forEach { (channel, value) ->
        result.add(calibrationToken(channel), mergeCalibrationChannel(result.get(calibrationToken(channel)), value))
    }
    return result
}

private fun mergeCalibrationChannel(
    existing: JsonElement?,
    calibration: StickProcessor.ValidatedCalibration,
): JsonObject {
    val result = if (existing != null && existing.isJsonObject) existing.asJsonObject.deepCopy() else JsonObject()
    result.addProperty("min", calibration.range.min)
    result.addProperty("max", calibration.range.max)
    result.addProperty("rest", calibration.rest)
    result.addProperty("spread", calibration.restSpread)
    result.addProperty("samples", calibration.sampleCount)
    result.addProperty("restMustBeInterior", calibration.restMustBeInterior)
    return result
}
