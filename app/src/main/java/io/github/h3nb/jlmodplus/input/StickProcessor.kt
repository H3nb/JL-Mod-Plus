package io.github.h3nb.jlmodplus.input

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Pure, platform-independent processing for controller axes.
 *
 * The processor deliberately returns values and next-state objects instead of
 * emitting input events. The eventual input ledger can therefore observe the
 * output and decide which target owns it.
 */
public object StickProcessor {
    public const val DEFAULT_INNER_DEADZONE: Float = 0.15f
    public const val DEFAULT_OUTER_CLAMP: Float = 0.95f
    public const val DEFAULT_RESPONSE_EXPONENT: Float = 1.0f
    public const val DEFAULT_PRESS_RADIUS: Float = 0.50f
    public const val DEFAULT_RELEASE_RADIUS: Float = 0.35f
    public const val DEFAULT_ANGULAR_HYSTERESIS_DEGREES: Float = 7.5f
    public const val DEFAULT_TRIGGER_PRESS: Float = 0.55f
    public const val DEFAULT_TRIGGER_RELEASE: Float = 0.40f

    /**
     * Small abstraction of Android's MotionRange. It intentionally has no
     * Android dependency. Signed axes use zero as neutral and scale each side
     * against its own endpoint; [AxisCalibration] is available when a
     * measured rest center is needed.
     */
    public data class MotionRangeLike(
        public val min: Float,
        public val max: Float,
    ) {
        public val isValid: Boolean
            get() = min.isFinite() && max.isFinite() && min < max

        public val isSigned: Boolean
            get() = isValid && min < 0.0f && max > 0.0f

        public companion object {
            public val SIGNED: MotionRangeLike = MotionRangeLike(-1.0f, 1.0f)
            public val UNIT: MotionRangeLike = MotionRangeLike(0.0f, 1.0f)
        }
    }

    /**
     * A signed axis range with an explicitly measured rest center.
     */
    public data class AxisCalibration(
        public val min: Float,
        public val max: Float,
        public val center: Float,
    ) {
        public val isValid: Boolean
            get() = min.isFinite() &&
                max.isFinite() &&
                center.isFinite() &&
                min < center &&
                center < max
    }

    /**
     * Maps an asymmetric signed MotionRange-like value to [-1, 1].
     *
     * Zero is the neutral point for a MotionRange-like signed axis. Each side
     * has its own denominator, so asymmetric ranges preserve neutral instead
     * of shifting it to a midpoint. Values outside the reported range are
     * clamped, while invalid/non-finite samples are ignored as zero.
     */
    public fun normalizeAxis(
        value: Float,
        range: MotionRangeLike,
        invert: Boolean = false,
    ): Float {
        if (!range.isSigned) {
            return 0.0f
        }
        return normalizeAxis(
            value = value,
            calibration = AxisCalibration(range.min, range.max, 0.0f),
            invert = invert,
        )
    }

    /**
     * Variant of [normalizeAxis] that uses a measured rest center.
     */
    public fun normalizeAxis(
        value: Float,
        calibration: AxisCalibration,
        invert: Boolean = false,
    ): Float {
        if (!value.isFinite() || !calibration.isValid) {
            return 0.0f
        }

        val clamped = value.coerceIn(calibration.min, calibration.max)
        val normalized = if (clamped < calibration.center) {
            (clamped - calibration.center) / (calibration.center - calibration.min)
        } else {
            (clamped - calibration.center) / (calibration.max - calibration.center)
        }
        val result = if (invert) -normalized else normalized
        return if (result == 0.0f) 0.0f else result.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Maps a trigger range to [0, 1]. Triggers are not centered axes: their
     * reported minimum is the released position and their maximum is fully
     * pressed.
     */
    public fun normalizeTrigger(
        value: Float,
        range: MotionRangeLike,
    ): Float {
        if (!value.isFinite() || !range.isValid) {
            return 0.0f
        }
        return ((value - range.min) / (range.max - range.min)).coerceIn(0.0f, 1.0f)
    }

    public data class StickConfig(
        public val innerDeadzone: Float = DEFAULT_INNER_DEADZONE,
        public val outerClamp: Float = DEFAULT_OUTER_CLAMP,
        public val responseExponent: Float = DEFAULT_RESPONSE_EXPONENT,
        public val invertX: Boolean = false,
        public val invertY: Boolean = false,
    ) {
        public val isValid: Boolean
            get() = innerDeadzone.isFinite() &&
                outerClamp.isFinite() &&
                responseExponent.isFinite() &&
                innerDeadzone >= 0.0f &&
                outerClamp > innerDeadzone &&
                outerClamp <= 1.0f &&
                responseExponent > 0.0f
    }

    /**
     * [radialMagnitude] is the magnitude after the radial deadzone and before
     * the response curve. Digital direction thresholds intentionally consume
     * this value so changing the exponent cannot change the press point.
     */
    public data class ProcessedStick(
        public val x: Float,
        public val y: Float,
        public val rawMagnitude: Float,
        public val radialMagnitude: Float,
        public val magnitude: Float,
        public val angleDegrees: Float,
    ) {
        public val isNeutral: Boolean
            get() = magnitude <= 0.0f
    }

    /**
     * Normalizes both raw axes, applies inversion, then applies a radial
     * deadzone, outer clamp, and response exponent.
     */
    public fun processStick(
        rawX: Float,
        rawY: Float,
        xRange: MotionRangeLike = MotionRangeLike.SIGNED,
        yRange: MotionRangeLike = MotionRangeLike.SIGNED,
        config: StickConfig = StickConfig(),
    ): ProcessedStick {
        if (!config.isValid) {
            return neutralStick()
        }
        val normalizedX = normalizeAxis(rawX, xRange, config.invertX)
        val normalizedY = normalizeAxis(rawY, yRange, config.invertY)
        return processNormalizedStick(normalizedX, normalizedY, config)
    }

    /**
     * Processes a stick using measured per-axis rest/range calibration. A missing calibration
     * falls back to the Android-reported signed range, which keeps old profiles and devices with
     * incomplete capability metadata usable. The calibration is applied before the radial stage,
     * so deadzone, response, and directional hysteresis see the same normalized coordinate space.
     */
    public fun processCalibratedStick(
        rawX: Float,
        rawY: Float,
        xCalibration: ValidatedCalibration?,
        yCalibration: ValidatedCalibration?,
        xRange: MotionRangeLike = MotionRangeLike.SIGNED,
        yRange: MotionRangeLike = MotionRangeLike.SIGNED,
        config: StickConfig = StickConfig(),
    ): ProcessedStick {
        if (!config.isValid) return neutralStick()
        val normalizedX = normalizeCalibratedAxis(rawX, xCalibration, xRange, config.invertX)
        val normalizedY = normalizeCalibratedAxis(rawY, yCalibration, yRange, config.invertY)
        return processNormalizedStick(normalizedX, normalizedY, config)
    }

    /** Converts a calibrated channel to a signed axis without coupling callers to its storage. */
    public fun normalizeCalibratedAxis(
        value: Float,
        calibration: ValidatedCalibration?,
        fallbackRange: MotionRangeLike = MotionRangeLike.SIGNED,
        invert: Boolean = false,
    ): Float {
        return if (calibration != null && calibration.range.isValid &&
            calibration.rest.isFinite()
        ) {
            normalizeAxis(
                value,
                AxisCalibration(
                    min = calibration.range.min,
                    max = calibration.range.max,
                    center = calibration.rest,
                ),
                invert,
            )
        } else {
            normalizeAxis(value, fallbackRange, invert)
        }
    }

    /**
     * Applies the radial stage to axes that are already normalized to
     * approximately [-1, 1]. This function also clamps each component before
     * computing the radius so a malformed caller cannot produce an unbounded
     * output.
     */
    public fun processNormalizedStick(
        normalizedX: Float,
        normalizedY: Float,
        config: StickConfig = StickConfig(),
    ): ProcessedStick {
        if (!config.isValid ||
            !normalizedX.isFinite() ||
            !normalizedY.isFinite()
        ) {
            return neutralStick()
        }

        val x = normalizedX.coerceIn(-1.0f, 1.0f)
        val y = normalizedY.coerceIn(-1.0f, 1.0f)
        val rawMagnitude = hypot(x.toDouble(), y.toDouble()).toFloat()
        val angleDegrees = angleForVector(x, y)
        if (rawMagnitude <= config.innerDeadzone) {
            return ProcessedStick(
                x = 0.0f,
                y = 0.0f,
                rawMagnitude = rawMagnitude,
                radialMagnitude = 0.0f,
                magnitude = 0.0f,
                angleDegrees = angleDegrees,
            )
        }

        val radialMagnitude = (
            (rawMagnitude - config.innerDeadzone) /
                (config.outerClamp - config.innerDeadzone)
            ).coerceIn(0.0f, 1.0f)
        val magnitude = Math.pow(
            radialMagnitude.toDouble(),
            config.responseExponent.toDouble(),
        )
            .toFloat()
            .coerceIn(0.0f, 1.0f)
        val directionX = x / rawMagnitude
        val directionY = y / rawMagnitude
        return ProcessedStick(
            x = directionX * magnitude,
            y = directionY * magnitude,
            rawMagnitude = rawMagnitude,
            radialMagnitude = radialMagnitude,
            magnitude = magnitude,
            angleDegrees = angleDegrees,
        )
    }

    public enum class DirectionMode {
        FOUR_WAY,
        EIGHT_WAY,
    }

    public enum class DiagonalOutputMode {
        CARDINALS,
        NUMBER_KEYS,
    }

    public enum class StickDirection {
        NEUTRAL,
        UP,
        UP_RIGHT,
        RIGHT,
        DOWN_RIGHT,
        DOWN,
        DOWN_LEFT,
        LEFT,
        UP_LEFT,
    }

    /**
     * Directional output tokens are deliberately not Canvas key codes. The
     * later ownership/ledger layer can map these tokens to canonical guest
     * keys without coupling this pure processor to the emulator.
     */
    public enum class DirectionKey {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        NUM1,
        NUM3,
        NUM7,
        NUM9,
    }

    public data class DirectionConfig(
        public val mode: DirectionMode = DirectionMode.EIGHT_WAY,
        public val diagonalOutput: DiagonalOutputMode = DiagonalOutputMode.CARDINALS,
        public val pressRadius: Float = DEFAULT_PRESS_RADIUS,
        public val releaseRadius: Float = DEFAULT_RELEASE_RADIUS,
        public val angularHysteresisDegrees: Float = DEFAULT_ANGULAR_HYSTERESIS_DEGREES,
    ) {
        public val isValid: Boolean
            get() = pressRadius.isFinite() &&
                releaseRadius.isFinite() &&
                angularHysteresisDegrees.isFinite() &&
                pressRadius >= 0.0f &&
                pressRadius <= 1.0f &&
                releaseRadius >= 0.0f &&
                releaseRadius <= pressRadius &&
                angularHysteresisDegrees >= 0.0f &&
                angularHysteresisDegrees <= 180.0f
    }

    public data class DirectionState(
        public val direction: StickDirection = StickDirection.NEUTRAL,
    )

    public data class DirectionResult(
        public val direction: StickDirection,
        public val keys: List<DirectionKey>,
    ) {
        public val state: DirectionState
            get() = DirectionState(direction)

        public val isNeutral: Boolean
            get() = direction == StickDirection.NEUTRAL
    }

    /**
     * Resolves a stick sample to a stable digital direction. Direction
     * thresholds use [ProcessedStick.radialMagnitude] (before the exponent);
     * the previous direction remains active in the release/press band and
     * across the configured angular hysteresis margin.
     */
    public fun resolveDirection(
        stick: ProcessedStick,
        previous: DirectionState = DirectionState(),
        config: DirectionConfig = DirectionConfig(),
    ): DirectionResult {
        if (!config.isValid) {
            return directionResult(StickDirection.NEUTRAL, config.diagonalOutput)
        }

        val magnitude = if (stick.radialMagnitude.isFinite()) {
            stick.radialMagnitude.coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
        val previousDirection = if (isAllowed(previous.direction, config.mode)) {
            previous.direction
        } else {
            StickDirection.NEUTRAL
        }
        if (previousDirection != StickDirection.NEUTRAL &&
            magnitude <= config.releaseRadius
        ) {
            return directionResult(StickDirection.NEUTRAL, config.diagonalOutput)
        }
        if (previousDirection == StickDirection.NEUTRAL &&
            magnitude < config.pressRadius
        ) {
            return directionResult(StickDirection.NEUTRAL, config.diagonalOutput)
        }

        val safeAngle = if (stick.angleDegrees.isFinite()) {
            normalizeAngleDegrees(stick.angleDegrees)
        } else {
            0.0f
        }
        val nominal = sectorDirection(safeAngle, config.mode)
        val resolved = if (previousDirection == StickDirection.NEUTRAL) {
            nominal
        } else {
            val sectorHalfWidth = if (config.mode == DirectionMode.FOUR_WAY) {
                45.0f
            } else {
                22.5f
            }
            val distanceFromPrevious = abs(
                shortestAngleDeltaDegrees(
                    directionCenterDegrees(previousDirection),
                    safeAngle,
                ),
            )
            if (distanceFromPrevious <= sectorHalfWidth + config.angularHysteresisDegrees) {
                previousDirection
            } else {
                nominal
            }
        }
        return directionResult(resolved, config.diagonalOutput)
    }

    public fun normalizeAngleDegrees(angle: Float): Float {
        if (!angle.isFinite()) {
            return 0.0f
        }
        var normalized = angle % 360.0f
        if (normalized > 180.0f) {
            normalized -= 360.0f
        } else if (normalized < -180.0f) {
            normalized += 360.0f
        }
        return if (normalized == 0.0f) 0.0f else normalized
    }

    /**
     * Returns the shortest signed delta from [from] to [to] in [-180, 180].
     */
    public fun shortestAngleDeltaDegrees(
        from: Float,
        to: Float,
    ): Float = normalizeAngleDegrees(to - from)

    public fun angleForVector(
        x: Float,
        y: Float,
    ): Float {
        if (!x.isFinite() || !y.isFinite() || (x == 0.0f && y == 0.0f)) {
            return 0.0f
        }
        return normalizeAngleDegrees(
            Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat(),
        )
    }

    public data class TriggerThresholds(
        public val press: Float = DEFAULT_TRIGGER_PRESS,
        public val release: Float = DEFAULT_TRIGGER_RELEASE,
    ) {
        public val isValid: Boolean
            get() = press.isFinite() &&
                release.isFinite() &&
                press >= 0.0f &&
                press <= 1.0f &&
                release >= 0.0f &&
                release <= press
    }

    public data class TriggerState(
        public val pressed: Boolean = false,
    )

    /**
     * Applies press/release hysteresis to a normalized trigger value. A
     * non-finite sample or invalid thresholds is ignored and leaves the
     * previous state untouched.
     */
    public fun updateTrigger(
        normalizedValue: Float,
        previous: TriggerState = TriggerState(),
        thresholds: TriggerThresholds = TriggerThresholds(),
    ): TriggerState {
        if (!normalizedValue.isFinite() || !thresholds.isValid) {
            return previous
        }
        val value = normalizedValue.coerceIn(0.0f, 1.0f)
        val pressed = if (previous.pressed) {
            value > thresholds.release
        } else {
            value >= thresholds.press
        }
        return TriggerState(pressed)
    }

    public data class CalibrationObservation(
        public val min: Float,
        public val max: Float,
        public val rest: Float,
        public val restSpread: Float = 0.0f,
        public val sampleCount: Int = 1,
        /**
         * Stick rests should be inside the measured range. Set this to false
         * for trigger observations whose released value is exactly min.
         */
        public val restMustBeInterior: Boolean = true,
    )

    public data class CalibrationConstraints(
        public val minimumSpan: Float = 0.10f,
        public val maximumRestSpread: Float = 0.15f,
        public val minimumSamples: Int = 1,
    ) {
        public val isValid: Boolean
            get() = minimumSpan.isFinite() &&
                maximumRestSpread.isFinite() &&
                minimumSpan > 0.0f &&
                maximumRestSpread >= 0.0f &&
                minimumSamples > 0
    }

    public enum class CalibrationIssue {
        NONE,
        NO_CHANNELS,
        INVALID_CONSTRAINTS,
        NON_FINITE,
        INSUFFICIENT_SAMPLES,
        INVALID_RANGE,
        REST_OUTSIDE_RANGE,
        REST_TOO_NOISY,
    }

    public data class ValidatedCalibration(
        public val range: MotionRangeLike,
        public val rest: Float,
        public val restSpread: Float,
        public val sampleCount: Int,
        public val restMustBeInterior: Boolean,
    )

    public data class CalibrationValidationResult(
        public val accepted: Boolean,
        public val issue: CalibrationIssue,
        public val channels: List<ValidatedCalibration> = emptyList(),
    )

    /**
     * Validates a completed calibration candidate. Callers must only replace
     * a persisted calibration when [CalibrationValidationResult.accepted] is
     * true; rejected/noisy candidates intentionally carry no replacement
     * state.
     */
    public fun validateCalibration(
        observations: List<CalibrationObservation>,
        constraints: CalibrationConstraints = CalibrationConstraints(),
    ): CalibrationValidationResult {
        if (!constraints.isValid) {
            return CalibrationValidationResult(false, CalibrationIssue.INVALID_CONSTRAINTS)
        }
        if (observations.isEmpty()) {
            return CalibrationValidationResult(false, CalibrationIssue.NO_CHANNELS)
        }

        val validated = ArrayList<ValidatedCalibration>(observations.size)
        for (observation in observations) {
            if (!observation.min.isFinite() ||
                !observation.max.isFinite() ||
                !observation.rest.isFinite() ||
                !observation.restSpread.isFinite()
            ) {
                return CalibrationValidationResult(false, CalibrationIssue.NON_FINITE)
            }
            if (observation.sampleCount < constraints.minimumSamples) {
                return CalibrationValidationResult(false, CalibrationIssue.INSUFFICIENT_SAMPLES)
            }

            val span = observation.max.toDouble() - observation.min.toDouble()
            if (!span.isFinite() ||
                observation.min >= observation.max ||
                span < constraints.minimumSpan.toDouble()
            ) {
                return CalibrationValidationResult(false, CalibrationIssue.INVALID_RANGE)
            }
            if (observation.rest < observation.min ||
                observation.rest > observation.max ||
                (observation.restMustBeInterior &&
                    (observation.rest <= observation.min ||
                        observation.rest >= observation.max))
            ) {
                return CalibrationValidationResult(false, CalibrationIssue.REST_OUTSIDE_RANGE)
            }
            if (observation.restSpread < 0.0f ||
                observation.restSpread > constraints.maximumRestSpread
            ) {
                return CalibrationValidationResult(false, CalibrationIssue.REST_TOO_NOISY)
            }
            validated += ValidatedCalibration(
                range = MotionRangeLike(observation.min, observation.max),
                rest = observation.rest,
                restSpread = observation.restSpread,
                sampleCount = observation.sampleCount,
                restMustBeInterior = observation.restMustBeInterior,
            )
        }
        return CalibrationValidationResult(
            accepted = true,
            issue = CalibrationIssue.NONE,
            channels = validated,
        )
    }

    private fun neutralStick(): ProcessedStick = ProcessedStick(
        x = 0.0f,
        y = 0.0f,
        rawMagnitude = 0.0f,
        radialMagnitude = 0.0f,
        magnitude = 0.0f,
        angleDegrees = 0.0f,
    )

    private fun directionResult(
        direction: StickDirection,
        diagonalOutput: DiagonalOutputMode,
    ): DirectionResult = DirectionResult(
        direction = direction,
        keys = keysFor(direction, diagonalOutput),
    )

    private fun sectorDirection(
        angleDegrees: Float,
        mode: DirectionMode,
    ): StickDirection {
        val positiveAngle = (normalizeAngleDegrees(angleDegrees) + 360.0f) % 360.0f
        return if (mode == DirectionMode.FOUR_WAY) {
            FOUR_WAY[floor((positiveAngle + 45.0f) / 90.0f).toInt() % FOUR_WAY.size]
        } else {
            EIGHT_WAY[floor((positiveAngle + 22.5f) / 45.0f).toInt() % EIGHT_WAY.size]
        }
    }

    private fun isAllowed(
        direction: StickDirection,
        mode: DirectionMode,
    ): Boolean = mode == DirectionMode.EIGHT_WAY ||
        direction == StickDirection.NEUTRAL ||
        direction == StickDirection.UP ||
        direction == StickDirection.RIGHT ||
        direction == StickDirection.DOWN ||
        direction == StickDirection.LEFT

    private fun directionCenterDegrees(direction: StickDirection): Float = when (direction) {
        StickDirection.NEUTRAL -> 0.0f
        StickDirection.RIGHT -> 0.0f
        StickDirection.DOWN_RIGHT -> 45.0f
        StickDirection.DOWN -> 90.0f
        StickDirection.DOWN_LEFT -> 135.0f
        StickDirection.LEFT -> 180.0f
        StickDirection.UP_LEFT -> -135.0f
        StickDirection.UP -> -90.0f
        StickDirection.UP_RIGHT -> -45.0f
    }

    private fun keysFor(
        direction: StickDirection,
        diagonalOutput: DiagonalOutputMode,
    ): List<DirectionKey> = when (direction) {
        StickDirection.NEUTRAL -> emptyList()
        StickDirection.UP -> listOf(DirectionKey.UP)
        StickDirection.RIGHT -> listOf(DirectionKey.RIGHT)
        StickDirection.DOWN -> listOf(DirectionKey.DOWN)
        StickDirection.LEFT -> listOf(DirectionKey.LEFT)
        StickDirection.UP_LEFT -> if (diagonalOutput == DiagonalOutputMode.NUMBER_KEYS) {
            listOf(DirectionKey.NUM7)
        } else {
            listOf(DirectionKey.UP, DirectionKey.LEFT)
        }
        StickDirection.UP_RIGHT -> if (diagonalOutput == DiagonalOutputMode.NUMBER_KEYS) {
            listOf(DirectionKey.NUM9)
        } else {
            listOf(DirectionKey.UP, DirectionKey.RIGHT)
        }
        StickDirection.DOWN_LEFT -> if (diagonalOutput == DiagonalOutputMode.NUMBER_KEYS) {
            listOf(DirectionKey.NUM1)
        } else {
            listOf(DirectionKey.DOWN, DirectionKey.LEFT)
        }
        StickDirection.DOWN_RIGHT -> if (diagonalOutput == DiagonalOutputMode.NUMBER_KEYS) {
            listOf(DirectionKey.NUM3)
        } else {
            listOf(DirectionKey.DOWN, DirectionKey.RIGHT)
        }
    }

    private val FOUR_WAY: List<StickDirection> = listOf(
        StickDirection.RIGHT,
        StickDirection.DOWN,
        StickDirection.LEFT,
        StickDirection.UP,
    )

    private val EIGHT_WAY: List<StickDirection> = listOf(
        StickDirection.RIGHT,
        StickDirection.DOWN_RIGHT,
        StickDirection.DOWN,
        StickDirection.DOWN_LEFT,
        StickDirection.LEFT,
        StickDirection.UP_LEFT,
        StickDirection.UP,
        StickDirection.UP_RIGHT,
    )
}
