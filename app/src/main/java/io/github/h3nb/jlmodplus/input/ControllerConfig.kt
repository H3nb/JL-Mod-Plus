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
 * Immutable, Android-free controller configuration.
 *
 * This is deliberately a subtree model rather than a ProfileModel replacement.  The profile
 * integration can keep the subtree as a Gson JsonElement until the authoritative persistence path
 * is extended.  Keeping the original object here also lets a supported config round-trip fields
 * that this version does not know about.  An unsupported config is never rewritten implicitly:
 * its original JSON is returned until the user explicitly calls [reset] or changes a field.
 */
class ControllerConfig private constructor(
    val mode: ControllerMode,
    val preset: Preset,
    val directionMode: DirectionMode,
    val leftStick: StickSettings,
    val rightStick: StickSettings,
    val triggers: TriggerSettings,
    bindings: Map<String, Binding>,
    val pointer: PointerSettings,
    calibrations: Map<String, GamepadCalibration>,
    val schemaVersion: Int,
    private val originalJson: JsonObject?,
    val unsupportedReason: String?,
    val shouldWriteController: Boolean,
    val resolutionSource: ResolutionSource,
) {
    /** True when the controller router may use the new mapping. */
    val enabled: Boolean
        get() = mode == ControllerMode.ENABLED

    /** True when the explicit or compatibility fallback mode is Legacy. */
    val legacy: Boolean
        get() = mode == ControllerMode.LEGACY

    /** False for a newer/invalid payload that must stay opaque at runtime. */
    val isSupported: Boolean
        get() = unsupportedReason == null

    /** A clear user-facing explanation for an unsupported payload, when one exists. */
    val notice: String?
        get() = unsupportedReason

    /** Effective bindings are exposed through an unmodifiable insertion-ordered map. */
    val bindings: Map<String, Binding> = immutableBindings(bindings)

    /** Validated, device-independent calibration profiles keyed by descriptor/capability. */
    val calibrations: Map<String, GamepadCalibration> = immutableCalibrations(calibrations)

    /** Returns a defensive copy of the source subtree, if this config came from JSON. */
    val rawJson: JsonObject?
        get() = originalJson?.deepCopy()

    /** Convenient lookup that does not expose mutable JSON state. */
    fun binding(controlToken: String): Binding? = bindings[controlToken]

    /**
     * Validate a config assembled by the editor/builder.
     *
     * Many-to-one bindings are intentionally valid.  Validation concerns malformed thresholds,
     * unsupported mode state, and the existence of an explicit controller menu path.
     */
    fun validate(): ValidationResult {
        val issues = ArrayList<ValidationIssue>()
        if (unsupportedReason != null) {
            issues.add(ValidationIssue("controller", unsupportedReason))
            return ValidationResult(issues)
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
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
        bindings.forEach { (control, binding) ->
            if (control.trim().isEmpty()) {
                issues.add(ValidationIssue("bindings", "control token must not be blank"))
            }
            if (binding.kind == BindingKind.NONE && binding.token != null) {
                issues.add(
                    ValidationIssue(
                        "bindings.$control",
                        "none bindings must not contain an action",
                    ),
                )
            }
        }
        if (mode == ControllerMode.ENABLED &&
            bindings.values.none {
                it.kind == BindingKind.HOST_ACTION &&
                    it.token == HostAction.OPEN_MENU.token
            }
        ) {
            issues.add(
                ValidationIssue(
                    "bindings",
                    "enabled controller config must retain a host OPEN_MENU binding",
                ),
            )
        }
        return ValidationResult(issues)
    }

    /** Creates an editor builder retaining opaque fields from a supported source JSON object. */
    fun toBuilder(): Builder = Builder(this)

    /** Explicit, reset-friendly recovery from an unsupported or unwanted mapping. */
    fun reset(): ControllerConfig = defaultNavigation(shouldWrite = true)

    fun withBinding(controlToken: String, binding: Binding): ControllerConfig =
        toBuilder().binding(controlToken, binding).build()

    fun withoutBinding(controlToken: String): ControllerConfig =
        toBuilder().removeBinding(controlToken).build()

    fun withCalibration(signature: String, calibration: GamepadCalibration): ControllerConfig =
        toBuilder().calibration(signature, calibration).build()

    fun withoutCalibration(signature: String): ControllerConfig =
        toBuilder().removeCalibration(signature).build()

    /**
     * Merge known fields into the original source JSON.  Unknown top-level and nested fields are
     * retained.  For an unsupported source the exact original subtree is returned instead.
     */
    fun toJson(): JsonObject {
        if (unsupportedReason != null && originalJson != null) {
            return originalJson.deepCopy()
        }

        val root = originalJson?.deepCopy() ?: JsonObject()
        root.addProperty(KEY_SCHEMA_VERSION, schemaVersion)
        root.addProperty(KEY_MODE, mode.token)
        root.addProperty(KEY_PRESET, preset.token)
        root.addProperty(KEY_DIRECTION_MODE, directionMode.token)
        root.addProperty(KEY_TRIGGER_PRESS, triggers.pressThreshold)
        root.addProperty(KEY_TRIGGER_RELEASE, triggers.releaseThreshold)

        val sticks = mergeObject(root, KEY_STICKS)
        writeStick(sticks, STICK_LEFT, leftStick)
        writeStick(sticks, STICK_RIGHT, rightStick)

        val bindingObject = mergeObject(root, KEY_BINDINGS)
        bindings.forEach { (control, binding) ->
            val existing = bindingObject.get(control)
            bindingObject.add(control, mergeBinding(existing, binding))
        }

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
        return mode == other.mode &&
            preset == other.preset &&
            directionMode == other.directionMode &&
            leftStick == other.leftStick &&
            rightStick == other.rightStick &&
            triggers == other.triggers &&
            bindings == other.bindings &&
            pointer == other.pointer &&
            calibrations == other.calibrations &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + preset.hashCode()
        result = 31 * result + directionMode.hashCode()
        result = 31 * result + leftStick.hashCode()
        result = 31 * result + rightStick.hashCode()
        result = 31 * result + triggers.hashCode()
        result = 31 * result + bindings.hashCode()
        result = 31 * result + pointer.hashCode()
        result = 31 * result + calibrations.hashCode()
        result = 31 * result + schemaVersion
        return result
    }

    override fun toString(): String =
        "ControllerConfig(mode=$mode, preset=$preset, directionMode=$directionMode, " +
            "bindings=$bindings, pointer=$pointer, calibrations=${calibrations.keys}, " +
            "schemaVersion=$schemaVersion, " +
            "unsupportedReason=$unsupportedReason)"

    class Builder internal constructor(
        private var mode: ControllerMode,
        private var preset: Preset,
        private var directionMode: DirectionMode,
        private var leftStick: StickSettings,
        private var rightStick: StickSettings,
        private var triggers: TriggerSettings,
        bindings: Map<String, Binding>,
        private var pointer: PointerSettings,
        calibrations: Map<String, GamepadCalibration>,
        private var schemaVersion: Int,
        private var originalJson: JsonObject?,
        private var unsupportedReason: String?,
        private var shouldWriteController: Boolean,
        private var resolutionSource: ResolutionSource,
        private var preserveUnsupportedPayload: Boolean,
    ) {
        private var mutableBindings = LinkedHashMap(bindings)
        private var mutableCalibrations = LinkedHashMap(calibrations)
        private var modified = false

        constructor() : this(
            mode = ControllerMode.ENABLED,
            preset = Preset.NAVIGATION,
            directionMode = DirectionMode.EIGHT,
            leftStick = defaultLeftStick(),
            rightStick = defaultRightStick(),
            triggers = TriggerSettings(),
            bindings = defaultBindings(Preset.NAVIGATION),
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
            mode = source.mode,
            preset = source.preset,
            directionMode = source.directionMode,
            leftStick = source.leftStick,
            rightStick = source.rightStick,
            triggers = source.triggers,
            bindings = source.bindings,
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

        fun mode(value: ControllerMode): Builder = apply {
            mode = value
            markModified()
        }

        fun enabled(value: Boolean): Builder = apply {
            mode = if (value) ControllerMode.ENABLED else ControllerMode.LEGACY
            markModified()
        }

        fun legacy(value: Boolean): Builder = apply {
            mode = if (value) ControllerMode.LEGACY else ControllerMode.ENABLED
            markModified()
        }

        fun preset(value: Preset): Builder = apply {
            preset = value
            markModified()
        }

        fun directionMode(value: DirectionMode): Builder = apply {
            directionMode = value
            leftStick = leftStick.copy(directionMode = value)
            rightStick = rightStick.copy(directionMode = value)
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
            triggers(TriggerSettings(pressThreshold, releaseThreshold))

        fun bindings(values: Map<String, Binding>): Builder = apply {
            mutableBindings = LinkedHashMap(values)
            markModified()
        }

        fun binding(controlToken: String, value: Binding): Builder = apply {
            mutableBindings[controlToken] = value
            markModified()
        }

        fun removeBinding(controlToken: String): Builder = apply {
            mutableBindings.remove(controlToken)
            markModified()
        }

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
            val calibrationObject = originalJson?.get(KEY_CALIBRATIONS)
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
            calibrationObject?.remove(signature)
            markModified()
        }

        fun schemaVersion(value: Int): Builder = apply {
            schemaVersion = value
            markModified()
        }

        fun build(): ControllerConfig {
            val keepOpaque = preserveUnsupportedPayload && !modified
            val candidate = ControllerConfig(
                mode = mode,
                preset = preset,
                directionMode = directionMode,
                leftStick = leftStick,
                rightStick = rightStick,
                triggers = triggers,
                bindings = mutableBindings,
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
                mode = mode,
                preset = preset,
                directionMode = directionMode,
                leftStick = leftStick,
                rightStick = rightStick,
                triggers = triggers,
                bindings = mutableBindings,
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
        const val CURRENT_SCHEMA_VERSION: Int = 1

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun defaultNavigation(): ControllerConfig = defaultNavigation(shouldWrite = false)

        @JvmStatic
        fun defaultGame(): ControllerConfig = createDefault(Preset.GAME, shouldWrite = false)

        @JvmStatic
        fun defaultNumeric(): ControllerConfig = createDefault(Preset.NUMERIC, shouldWrite = false)

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

        /**
         * Resolve the new controller config without importing Android's SparseIntArray into the
         * pure model.  Legacy entries use stable control tokens and canonical guest-key tokens.
         * When no controller subtree exists, a read-only resolution never requires a new write.
         */
        @JvmStatic
        @JvmOverloads
        fun resolve(
            controllerJson: JsonObject?,
            legacyMappings: Map<String, String>? = null,
            options: ResolveOptions = ResolveOptions(),
        ): ControllerConfig {
            if (controllerJson != null) return parse(controllerJson)
            val selectedPreset = options.explicitPreset ?: Preset.NAVIGATION
            val selectedMode = options.explicitMode ?: ControllerMode.ENABLED
            val effective = overlayLegacy(
                base = defaultBindings(selectedPreset),
                legacyMappings = legacyMappings,
            )
            return create(
                mode = selectedMode,
                preset = selectedPreset,
                directionMode = DirectionMode.EIGHT,
                leftStick = defaultLeftStick(),
                rightStick = defaultRightStick(),
                triggers = TriggerSettings(),
                bindings = effective,
                pointer = PointerSettings(),
                calibrations = emptyMap(),
                schemaVersion = CURRENT_SCHEMA_VERSION,
                originalJson = null,
                unsupportedReason = null,
                shouldWriteController = options.explicitMode != null || options.explicitPreset != null,
                resolutionSource = if (legacyMappings.isNullOrEmpty()) {
                    ResolutionSource.DEFAULT
                } else {
                    ResolutionSource.LEGACY_MAP
                },
            )
        }

        /** Same resolver for callers that have already translated legacy values to bindings. */
        @JvmStatic
        fun resolveBindings(
            controllerJson: JsonObject?,
            legacyBindings: Map<String, Binding>?,
            options: ResolveOptions = ResolveOptions(),
        ): ControllerConfig {
            if (controllerJson != null) return parse(controllerJson)
            val selectedPreset = options.explicitPreset ?: Preset.NAVIGATION
            val selectedMode = options.explicitMode ?: ControllerMode.ENABLED
            val effective = LinkedHashMap(defaultBindings(selectedPreset))
            legacyBindings.orEmpty().entries.sortedBy { it.key }.forEach { (control, binding) ->
                effective[control] = binding
            }
            return create(
                mode = selectedMode,
                preset = selectedPreset,
                directionMode = DirectionMode.EIGHT,
                leftStick = defaultLeftStick(),
                rightStick = defaultRightStick(),
                triggers = TriggerSettings(),
                bindings = effective,
                pointer = PointerSettings(),
                calibrations = emptyMap(),
                schemaVersion = CURRENT_SCHEMA_VERSION,
                originalJson = null,
                unsupportedReason = null,
                shouldWriteController = options.explicitMode != null || options.explicitPreset != null,
                resolutionSource = if (legacyBindings.isNullOrEmpty()) {
                    ResolutionSource.DEFAULT
                } else {
                    ResolutionSource.LEGACY_MAP
                },
            )
        }

        private fun parseSupported(json: JsonObject, original: JsonObject): ControllerConfig {
            val schemaVersion = readSchemaVersion(json)
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw ParseFailure("unsupported controller schemaVersion $schemaVersion")
            }

            val mode = readEnum(json, KEY_MODE, ControllerMode.ENABLED.token, ControllerMode::fromToken)
            val preset = readEnum(json, KEY_PRESET, Preset.NAVIGATION.token, Preset::fromToken)
            val directionMode = readEnum(
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
                defaultDirectionMode = directionMode,
                path = "sticks.left",
            )
            val right = parseStick(
                stickObject?.get(STICK_RIGHT),
                defaultMode = StickMode.UNASSIGNED,
                defaultDirectionMode = directionMode,
                path = "sticks.right",
            )

            val triggerObject = when {
                json.get(KEY_TRIGGERS)?.isJsonObject == true -> json.getAsJsonObject(KEY_TRIGGERS)
                json.get(KEY_TRIGGER)?.isJsonObject == true -> json.getAsJsonObject(KEY_TRIGGER)
                else -> null
            }
            val press = readNumber(
                json,
                KEY_TRIGGER_PRESS,
                triggerObject?.get(KEY_TRIGGER_PRESS),
                TriggerSettings.DEFAULT_PRESS,
            )
            val release = readNumber(
                json,
                KEY_TRIGGER_RELEASE,
                triggerObject?.get(KEY_TRIGGER_RELEASE),
                TriggerSettings.DEFAULT_RELEASE,
            )
            val triggers = TriggerSettings(press, release)

            val bindingsElement = json.get(KEY_BINDINGS)
            if (bindingsElement != null && !bindingsElement.isJsonObject) {
                throw ParseFailure("bindings must be a JSON object")
            }
            val overrides = parseBindings(bindingsElement?.asJsonObject)
            val effectiveBindings = LinkedHashMap(defaultBindings(preset))
            overrides.forEach { (control, binding) -> effectiveBindings[control] = binding }

            val pointer = parsePointer(json.get(KEY_POINTER))
            val calibrations = parseCalibrations(json.get(KEY_CALIBRATIONS))
            val config = create(
                mode = mode,
                preset = preset,
                directionMode = directionMode,
                leftStick = left,
                rightStick = right,
                triggers = triggers,
                bindings = effectiveBindings,
                pointer = pointer,
                calibrations = calibrations,
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
            val clickElement = json.get(KEY_CLICK_BINDING)
            val clickBinding = if (clickElement == null) Binding.none() else parseBinding(clickElement, "pointer.clickBinding")
            val pointer = PointerSettings(
                mode = mode,
                sourceStick = sourceStick,
                clickBinding = clickBinding,
                speed = readNumber(json, KEY_POINTER_SPEED, null, PointerSettings.DEFAULT_SPEED),
                centerX = readNumber(json, KEY_CENTER_X, null, PointerSettings.DEFAULT_CENTER),
                centerY = readNumber(json, KEY_CENTER_Y, null, PointerSettings.DEFAULT_CENTER),
                radius = readNumber(json, KEY_RADIUS, null, PointerSettings.DEFAULT_RADIUS),
            )
            val issues = pointer.validationIssues("pointer")
            if (issues.isNotEmpty()) throw ParseFailure(issues.joinToString("; ") { it.message })
            return pointer
        }

        /**
         * Reads only validated calibration entries. A malformed optional calibration is ignored
         * at runtime and remains in [originalJson], so changing an unrelated setting does not
         * destroy data written by a newer build. Explicit reset is the only path that removes it.
         */
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
                    val channelElement = channelObject.get(calibrationToken(channel))
                    val parsed = parseCalibrationChannel(channelElement, channel)
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
                val validRest = rest >= min && rest <= max &&
                    (!interior || (rest > min && rest < max))
                if (!range.isValid || !rest.isFinite() || !spread.isFinite() ||
                    spread < 0.0 || samples < 1.0 || samples != samples.toInt().toDouble() ||
                    !validRest
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

        private fun optionalCalibrationNumber(
            json: JsonObject,
            key: String,
            fallback: Double,
        ): Double {
            val value = json.get(key) ?: return fallback
            val primitive = value.asPrimitiveOrNull()
                ?: throw ParseFailure("calibration.$key must be a finite number")
            if (!primitive.isNumber || !primitive.asDouble.isFinite()) {
                throw ParseFailure("calibration.$key must be a finite number")
            }
            return primitive.asDouble
        }

        private fun parseBindings(json: JsonObject?): LinkedHashMap<String, Binding> {
            val bindings = LinkedHashMap<String, Binding>()
            if (json == null) return bindings
            json.entrySet().toList().sortedBy { it.key }.forEach { entry ->
                if (entry.key.trim().isEmpty()) throw ParseFailure("bindings contains a blank control token")
                bindings[entry.key] = parseBinding(entry.value, "bindings.${entry.key}")
            }
            return bindings
        }

        private fun parseBinding(element: JsonElement, path: String): Binding {
            if (!element.isJsonObject) throw ParseFailure("$path must be a binding object")
            val json = element.asJsonObject
            val kindToken = readString(json, KEY_BINDING_KIND, required = true, path = "$path.kind")
                ?: throw ParseFailure("$path is missing binding kind")
            val kind = BindingKind.fromToken(kindToken)
                ?: throw ParseFailure("$path has unknown binding kind '$kindToken'")
            return when (kind) {
                BindingKind.NONE -> Binding.none()
                BindingKind.GUEST_KEY -> {
                    val keyToken = readString(json, KEY_BINDING_KEY, required = true, path = "$path.key")
                        ?: throw ParseFailure("$path is missing guest key")
                    try {
                        Binding.guestKey(keyToken)
                    } catch (_: IllegalArgumentException) {
                        throw ParseFailure("$path has unknown guest key '$keyToken'")
                    }
                }
                BindingKind.HOST_ACTION -> {
                    val actionToken = readString(json, KEY_BINDING_ACTION, required = true, path = "$path.action")
                        ?: throw ParseFailure("$path is missing host action")
                    val action = HostAction.fromToken(actionToken)
                        ?: throw ParseFailure("$path has unknown host action '$actionToken'")
                    Binding.hostAction(action)
                }
                BindingKind.POINTER_ACTION -> {
                    val actionToken = readString(json, KEY_BINDING_ACTION, required = true, path = "$path.action")
                        ?: throw ParseFailure("$path is missing pointer action")
                    val action = PointerAction.fromToken(actionToken)
                        ?: throw ParseFailure("$path has unknown pointer action '$actionToken'")
                    Binding.pointerAction(action)
                }
            }
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

        private fun unsupported(
            original: JsonObject,
            reason: String,
            schemaVersion: Int,
        ): ControllerConfig {
            val clearReason =
                "Controller configuration is unsupported ($reason). Runtime falls back to Legacy; " +
                    "reset the controller mapping to enable it."
            return create(
                mode = ControllerMode.LEGACY,
                preset = Preset.NAVIGATION,
                directionMode = DirectionMode.EIGHT,
                leftStick = defaultLeftStick(),
                rightStick = defaultRightStick(),
                triggers = TriggerSettings(),
                bindings = defaultBindings(Preset.NAVIGATION),
                pointer = PointerSettings(),
                calibrations = emptyMap(),
                schemaVersion = schemaVersion,
                originalJson = original,
                unsupportedReason = clearReason,
                shouldWriteController = false,
                resolutionSource = ResolutionSource.CONTROLLER,
            )
        }

        private fun createDefault(preset: Preset, shouldWrite: Boolean): ControllerConfig = create(
            mode = ControllerMode.ENABLED,
            preset = preset,
            directionMode = DirectionMode.EIGHT,
            leftStick = defaultLeftStick(),
            rightStick = defaultRightStick(),
            triggers = TriggerSettings(),
            bindings = defaultBindings(preset),
            pointer = PointerSettings(),
            calibrations = emptyMap(),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            originalJson = null,
            unsupportedReason = null,
            shouldWriteController = shouldWrite,
            resolutionSource = ResolutionSource.DEFAULT,
        )

        private fun defaultNavigation(shouldWrite: Boolean): ControllerConfig =
            createDefault(Preset.NAVIGATION, shouldWrite)

        private fun create(
            mode: ControllerMode,
            preset: Preset,
            directionMode: DirectionMode,
            leftStick: StickSettings,
            rightStick: StickSettings,
            triggers: TriggerSettings,
            bindings: Map<String, Binding>,
            pointer: PointerSettings,
            calibrations: Map<String, GamepadCalibration>,
            schemaVersion: Int,
            originalJson: JsonObject?,
            unsupportedReason: String?,
            shouldWriteController: Boolean,
            resolutionSource: ResolutionSource,
        ): ControllerConfig = ControllerConfig(
            mode = mode,
            preset = preset,
            directionMode = directionMode,
            leftStick = leftStick,
            rightStick = rightStick,
            triggers = triggers,
            bindings = bindings,
            pointer = pointer,
            calibrations = calibrations,
            schemaVersion = schemaVersion,
            originalJson = originalJson?.deepCopy(),
            unsupportedReason = unsupportedReason,
            shouldWriteController = shouldWriteController,
            resolutionSource = resolutionSource,
        )

        private fun overlayLegacy(
            base: Map<String, Binding>,
            legacyMappings: Map<String, String>?,
        ): LinkedHashMap<String, Binding> {
            val result = LinkedHashMap(base)
            legacyMappings.orEmpty().entries.sortedBy { it.key }.forEach { (control, keyToken) ->
                val binding = legacyBinding(keyToken) ?: return@forEach
                result[control] = binding
            }
            return result
        }

        private fun legacyBinding(token: String): Binding? {
            val normalized = normalize(token)
            if (normalized == "NONE") return Binding.none()
            if (normalized == "KEY_OPTIONS_MENU" || normalized == "LEGACY_MENU") {
                return Binding.hostAction(HostAction.OPEN_MENU)
            }
            val canonical = when {
                token.trim().matches(Regex("[0-9]")) -> "NUM${token.trim()}"
                token.trim() == "*" -> GuestKey.STAR.token
                token.trim() == "#" -> GuestKey.POUND.token
                else -> token
            }
            return try {
                Binding.guestKey(canonical)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun defaultLeftStick(): StickSettings = StickSettings(
            mode = StickMode.DIRECTIONS,
            directionMode = DirectionMode.EIGHT,
        )

        private fun defaultRightStick(): StickSettings = StickSettings(
            mode = StickMode.UNASSIGNED,
            directionMode = DirectionMode.EIGHT,
        )

        private fun defaultBindings(preset: Preset): LinkedHashMap<String, Binding> {
            val numeric = preset == Preset.NUMERIC
            return linkedMapOf(
                CONTROL_DPAD_UP to Binding.guestKey(if (numeric) GuestKey.NUM2 else GuestKey.UP),
                CONTROL_DPAD_DOWN to Binding.guestKey(if (numeric) GuestKey.NUM8 else GuestKey.DOWN),
                CONTROL_DPAD_LEFT to Binding.guestKey(if (numeric) GuestKey.NUM4 else GuestKey.LEFT),
                CONTROL_DPAD_RIGHT to Binding.guestKey(if (numeric) GuestKey.NUM6 else GuestKey.RIGHT),
                CONTROL_BUTTON_A to Binding.guestKey(if (numeric) GuestKey.NUM5 else GuestKey.FIRE),
                CONTROL_BUTTON_B to Binding.guestKey(GuestKey.NUM0),
                CONTROL_BUTTON_X to Binding.guestKey(GuestKey.NUM1),
                CONTROL_BUTTON_Y to Binding.guestKey(GuestKey.NUM3),
                CONTROL_BUTTON_L1 to Binding.guestKey(GuestKey.SOFT_LEFT),
                CONTROL_BUTTON_R1 to Binding.guestKey(GuestKey.SOFT_RIGHT),
                CONTROL_BUTTON_L2 to Binding.guestKey(GuestKey.STAR),
                CONTROL_BUTTON_R2 to Binding.guestKey(GuestKey.POUND),
                CONTROL_BUTTON_START to Binding.hostAction(HostAction.OPEN_MENU),
                CONTROL_BUTTON_SELECT to Binding.hostAction(HostAction.OPEN_MAPPING_HELP),
            )
        }

        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_MODE = "mode"
        private const val KEY_PRESET = "preset"
        private const val KEY_DIRECTION_MODE = "directionMode"
        private const val KEY_STICKS = "sticks"
        private const val KEY_BINDINGS = "bindings"
        private const val KEY_TRIGGERS = "triggers"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_TRIGGER_PRESS = "triggerPressThreshold"
        private const val KEY_TRIGGER_RELEASE = "triggerReleaseThreshold"
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
        private const val KEY_CLICK_BINDING = "clickBinding"
        private const val KEY_POINTER_SPEED = "speed"
        private const val KEY_CENTER_X = "centerX"
        private const val KEY_CENTER_Y = "centerY"
        private const val KEY_RADIUS = "radius"
        private const val KEY_BINDING_KIND = "kind"
        private const val KEY_BINDING_KEY = "key"
        private const val KEY_BINDING_ACTION = "action"
        private const val STICK_LEFT = "left"
        private const val STICK_RIGHT = "right"

        private const val CONTROL_DPAD_UP = "dpad_up"
        private const val CONTROL_DPAD_DOWN = "dpad_down"
        private const val CONTROL_DPAD_LEFT = "dpad_left"
        private const val CONTROL_DPAD_RIGHT = "dpad_right"
        private const val CONTROL_BUTTON_A = "button_a"
        private const val CONTROL_BUTTON_B = "button_b"
        private const val CONTROL_BUTTON_X = "button_x"
        private const val CONTROL_BUTTON_Y = "button_y"
        private const val CONTROL_BUTTON_L1 = "button_l1"
        private const val CONTROL_BUTTON_R1 = "button_r1"
        private const val CONTROL_BUTTON_L2 = "button_l2"
        private const val CONTROL_BUTTON_R2 = "button_r2"
        private const val CONTROL_BUTTON_START = "button_start"
        private const val CONTROL_BUTTON_SELECT = "button_select"
    }
}

enum class ControllerMode(val token: String) {
    ENABLED("enabled"),
    LEGACY("legacy");

    companion object {
        fun fromToken(token: String): ControllerMode? = when (normalize(token)) {
            "ENABLED" -> ENABLED
            "LEGACY" -> LEGACY
            else -> null
        }
    }
}

enum class Preset(val token: String) {
    NAVIGATION("navigation"),
    GAME("game"),
    NUMERIC("numeric"),
    CUSTOM("custom");

    companion object {
        fun fromToken(token: String): Preset? = when (normalize(token)) {
            "NAVIGATION" -> NAVIGATION
            "GAME" -> GAME
            "NUMERIC", "KEYPAD", "NUMBER" -> NUMERIC
            "CUSTOM", "MANUAL" -> CUSTOM
            else -> null
        }
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

enum class BindingKind(val token: String) {
    GUEST_KEY("guestKey"),
    HOST_ACTION("hostAction"),
    POINTER_ACTION("pointerAction"),
    NONE("none");

    companion object {
        fun fromToken(token: String): BindingKind? = when (normalize(token)) {
            "GUESTKEY" -> GUEST_KEY
            "HOSTACTION" -> HOST_ACTION
            "POINTERACTION" -> POINTER_ACTION
            "NONE" -> NONE
            else -> null
        }
    }
}

enum class GuestKey(val token: String) {
    NUM0("NUM0"),
    NUM1("NUM1"),
    NUM2("NUM2"),
    NUM3("NUM3"),
    NUM4("NUM4"),
    NUM5("NUM5"),
    NUM6("NUM6"),
    NUM7("NUM7"),
    NUM8("NUM8"),
    NUM9("NUM9"),
    STAR("STAR"),
    POUND("POUND"),
    UP("UP"),
    DOWN("DOWN"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    FIRE("FIRE"),
    SOFT_LEFT("SOFT_LEFT"),
    SOFT_RIGHT("SOFT_RIGHT"),
    CLEAR("CLEAR"),
    SEND("SEND"),
    END("END"),
    GAME_A("GAME_A"),
    GAME_B("GAME_B"),
    GAME_C("GAME_C"),
    GAME_D("GAME_D");

    companion object {
        fun fromToken(token: String): GuestKey? {
            val normalized = normalize(token)
            return entries.firstOrNull { normalize(it.token) == normalized }
        }
    }
}

enum class HostAction(val token: String) {
    OPEN_MENU("OPEN_MENU"),
    OPEN_MAPPING_HELP("OPEN_MAPPING_HELP"),
    BACK("BACK"),
    ACTIVATE("ACTIVATE"),
    NEXT_TAB("NEXT_TAB"),
    PREVIOUS_TAB("PREVIOUS_TAB"),
    OPEN_KEYPAD("OPEN_KEYPAD");

    companion object {
        fun fromToken(token: String): HostAction? = entries.firstOrNull {
            normalize(it.token) == normalize(token)
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

/** A typed assignment.  The token is always canonical for the selected kind. */
class Binding private constructor(
    val kind: BindingKind,
    val token: String?,
) {
    val guestKey: GuestKey?
        get() = if (kind == BindingKind.GUEST_KEY) GuestKey.fromToken(token.orEmpty()) else null

    val hostAction: HostAction?
        get() = if (kind == BindingKind.HOST_ACTION) HostAction.fromToken(token.orEmpty()) else null

    val pointerAction: PointerAction?
        get() = if (kind == BindingKind.POINTER_ACTION) PointerAction.fromToken(token.orEmpty()) else null

    companion object {
        @JvmStatic
        fun none(): Binding = Binding(BindingKind.NONE, null)

        @JvmStatic
        fun guestKey(key: GuestKey): Binding = Binding(BindingKind.GUEST_KEY, key.token)

        @JvmStatic
        fun guestKey(token: String): Binding {
            val key = GuestKey.fromToken(token)
                ?: throw IllegalArgumentException("unknown guest key '$token'")
            return Binding(BindingKind.GUEST_KEY, key.token)
        }

        @JvmStatic
        fun hostAction(action: HostAction): Binding = Binding(BindingKind.HOST_ACTION, action.token)

        @JvmStatic
        fun pointerAction(action: PointerAction): Binding =
            Binding(BindingKind.POINTER_ACTION, action.token)
    }

    override fun equals(other: Any?): Boolean =
        other is Binding && kind == other.kind && token == other.token

    override fun hashCode(): Int = 31 * kind.hashCode() + (token?.hashCode() ?: 0)

    override fun toString(): String = if (token == null) {
        "Binding(${kind.token})"
    } else {
        "Binding(${kind.token}:$token)"
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
) {
    internal fun validationIssues(path: String): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (!pressThreshold.isFinite() || pressThreshold > 1.0 || pressThreshold < 0.0) {
            issues.add(ValidationIssue("$path.pressThreshold", "must be finite and in [0, 1]"))
        }
        if (!releaseThreshold.isFinite() ||
            releaseThreshold < 0.0 ||
            releaseThreshold >= pressThreshold
        ) {
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
    val clickBinding: Binding = Binding.none(),
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
        if (clickBinding.kind != BindingKind.NONE && clickBinding.kind != BindingKind.POINTER_ACTION) {
            issues.add(ValidationIssue("$path.clickBinding", "must be none or a pointerAction binding"))
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
    val issues: List<ValidationIssue> =
        Collections.unmodifiableList(ArrayList(issues))
    val isValid: Boolean
        get() = issues.isEmpty()

    fun messages(): List<String> = issues.map { "${it.path}: ${it.message}" }

    override fun toString(): String = if (isValid) "ValidationResult(valid)" else {
        "ValidationResult(${messages().joinToString("; ")})"
    }
}

data class ResolveOptions(
    val explicitMode: ControllerMode? = null,
    val explicitPreset: Preset? = null,
)

enum class ResolutionSource {
    DEFAULT,
    LEGACY_MAP,
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

private fun immutableBindings(values: Map<String, Binding>): Map<String, Binding> =
    Collections.unmodifiableMap(LinkedHashMap(values))

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
    val existing = parent.get("clickBinding")
    parent.add("clickBinding", mergeBinding(existing, pointer.clickBinding))
    parent.addProperty("speed", pointer.speed)
    parent.addProperty("centerX", pointer.centerX)
    parent.addProperty("centerY", pointer.centerY)
    parent.addProperty("radius", pointer.radius)
}

private fun mergeBinding(existing: JsonElement?, binding: Binding): JsonObject {
    val result = if (existing != null && existing.isJsonObject) {
        existing.asJsonObject.deepCopy()
    } else {
        JsonObject()
    }
    result.addProperty("kind", binding.kind.token)
    when (binding.kind) {
        BindingKind.NONE -> {
            result.remove("key")
            result.remove("action")
        }
        BindingKind.GUEST_KEY -> {
            result.addProperty("key", binding.token)
            result.remove("action")
        }
        BindingKind.HOST_ACTION, BindingKind.POINTER_ACTION -> {
            result.addProperty("action", binding.token)
            result.remove("key")
        }
    }
    return result
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
    val result = if (existing != null && existing.isJsonObject) {
        existing.asJsonObject.deepCopy()
    } else {
        JsonObject()
    }
    calibration.immutableChannels.forEach { (channel, value) ->
        val key = calibrationToken(channel)
        result.add(key, mergeCalibrationChannel(result.get(key), value))
    }
    return result
}

private fun mergeCalibrationChannel(
    existing: JsonElement?,
    calibration: StickProcessor.ValidatedCalibration,
): JsonObject {
    val result = if (existing != null && existing.isJsonObject) {
        existing.asJsonObject.deepCopy()
    } else {
        JsonObject()
    }
    result.addProperty("min", calibration.range.min)
    result.addProperty("max", calibration.range.max)
    result.addProperty("rest", calibration.rest)
    result.addProperty("spread", calibration.restSpread)
    result.addProperty("samples", calibration.sampleCount)
    result.addProperty("restMustBeInterior", calibration.restMustBeInterior)
    return result
}
