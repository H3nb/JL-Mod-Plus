package io.github.h3nb.jlmodplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StickProcessorTest {
    @Test
    fun normalizeAxisUsesEachSideOfAnAsymmetricRange() {
        val range = StickProcessor.MotionRangeLike(min = -2.0f, max = 6.0f)

        assertEquals(-1.0f, StickProcessor.normalizeAxis(-2.0f, range), 0.0001f)
        assertEquals(-0.5f, StickProcessor.normalizeAxis(-1.0f, range), 0.0001f)
        assertEquals(0.0f, StickProcessor.normalizeAxis(0.0f, range), 0.0f)
        assertEquals(0.5f, StickProcessor.normalizeAxis(3.0f, range), 0.0001f)
        assertEquals(1.0f, StickProcessor.normalizeAxis(20.0f, range), 0.0001f)
        assertEquals(-0.5f, StickProcessor.normalizeAxis(3.0f, range, invert = true), 0.0001f)
        assertEquals(0.0f, StickProcessor.normalizeAxis(Float.NaN, range), 0.0f)
    }

    @Test
    fun normalizeAxisCanUseMeasuredRestCenter() {
        val calibration = StickProcessor.AxisCalibration(
            min = -2.0f,
            max = 6.0f,
            center = 1.0f,
        )

        assertEquals(-1.0f, StickProcessor.normalizeAxis(-2.0f, calibration), 0.0001f)
        assertEquals(-0.5f, StickProcessor.normalizeAxis(-0.5f, calibration), 0.0001f)
        assertEquals(0.6f, StickProcessor.normalizeAxis(4.0f, calibration), 0.0001f)
        assertEquals(0.0f, StickProcessor.normalizeAxis(1.0f, calibration), 0.0f)
    }

    @Test
    fun radialDeadzoneOuterClampAndExponentAreObservable() {
        val config = StickProcessor.StickConfig(
            innerDeadzone = 0.15f,
            outerClamp = 0.95f,
            responseExponent = 2.0f,
        )

        val belowDeadzone = StickProcessor.processNormalizedStick(0.10f, 0.0f, config)
        assertEquals(0.0f, belowDeadzone.x, 0.0f)
        assertEquals(0.0f, belowDeadzone.radialMagnitude, 0.0f)

        val mid = StickProcessor.processNormalizedStick(0.55f, 0.0f, config)
        assertEquals(0.50f, mid.radialMagnitude, 0.0001f)
        assertEquals(0.25f, mid.magnitude, 0.0001f)
        assertEquals(0.25f, mid.x, 0.0001f)

        val atOuter = StickProcessor.processNormalizedStick(1.0f, 0.0f, config)
        assertEquals(1.0f, atOuter.radialMagnitude, 0.0001f)
        assertEquals(1.0f, atOuter.magnitude, 0.0001f)
        assertEquals(0.0f, atOuter.y, 0.0f)
    }

    @Test
    fun processStickNormalizesAndInvertsBeforeRadialProcessing() {
        val config = StickProcessor.StickConfig(
            innerDeadzone = 0.0f,
            outerClamp = 1.0f,
            invertY = true,
        )
        val result = StickProcessor.processStick(
            rawX = 1.0f,
            rawY = 1.0f,
            xRange = StickProcessor.MotionRangeLike(-2.0f, 2.0f),
            yRange = StickProcessor.MotionRangeLike(-2.0f, 2.0f),
            config = config,
        )

        assertTrue(result.x > 0.0f)
        assertTrue(result.y < 0.0f)
        assertEquals(result.magnitude, result.rawMagnitude, 0.0001f)
    }

    @Test
    fun directionsRespectYDownAndEmitStableDiagonalOutputs() {
        val stickConfig = StickProcessor.StickConfig(
            innerDeadzone = 0.0f,
            outerClamp = 1.0f,
        )
        val downRight = StickProcessor.processNormalizedStick(0.8f, 0.8f, stickConfig)

        val cardinal = StickProcessor.resolveDirection(
            downRight,
            config = StickProcessor.DirectionConfig(
                mode = StickProcessor.DirectionMode.EIGHT_WAY,
                diagonalOutput = StickProcessor.DiagonalOutputMode.CARDINALS,
            ),
        )
        assertEquals(StickProcessor.StickDirection.DOWN_RIGHT, cardinal.direction)
        assertEquals(
            listOf(
                StickProcessor.DirectionKey.DOWN,
                StickProcessor.DirectionKey.RIGHT,
            ),
            cardinal.keys,
        )

        val number = StickProcessor.resolveDirection(
            downRight,
            config = StickProcessor.DirectionConfig(
                mode = StickProcessor.DirectionMode.EIGHT_WAY,
                diagonalOutput = StickProcessor.DiagonalOutputMode.NUMBER_KEYS,
            ),
        )
        assertEquals(listOf(StickProcessor.DirectionKey.NUM3), number.keys)

        val up = StickProcessor.processNormalizedStick(0.0f, -1.0f, stickConfig)
        assertEquals(
            StickProcessor.StickDirection.UP,
            StickProcessor.resolveDirection(
                up,
                config = StickProcessor.DirectionConfig(
                    mode = StickProcessor.DirectionMode.FOUR_WAY,
                ),
            ).direction,
        )
    }

    @Test
    fun fourWayAndEightWayUseDigitalThresholdBeforeResponseCurve() {
        val curved = StickProcessor.processNormalizedStick(
            normalizedX = 0.60f,
            normalizedY = 0.0f,
            config = StickProcessor.StickConfig(
                innerDeadzone = 0.0f,
                outerClamp = 1.0f,
                responseExponent = 2.0f,
            ),
        )
        assertEquals(0.60f, curved.radialMagnitude, 0.0001f)
        assertEquals(0.36f, curved.magnitude, 0.0001f)

        val result = StickProcessor.resolveDirection(
            curved,
            config = StickProcessor.DirectionConfig(
                mode = StickProcessor.DirectionMode.FOUR_WAY,
                pressRadius = 0.50f,
                releaseRadius = 0.30f,
            ),
        )
        assertEquals(StickProcessor.StickDirection.RIGHT, result.direction)
    }

    @Test
    fun directionThresholdAndAngularHysteresisPreventFlapping() {
        val stickConfig = StickProcessor.StickConfig(
            innerDeadzone = 0.0f,
            outerClamp = 1.0f,
        )
        val directionConfig = StickProcessor.DirectionConfig(
            mode = StickProcessor.DirectionMode.EIGHT_WAY,
            pressRadius = 0.50f,
            releaseRadius = 0.35f,
            angularHysteresisDegrees = 7.5f,
        )

        fun at(angleDegrees: Double, radius: Float = 1.0f): StickProcessor.ProcessedStick {
            val radians = Math.toRadians(angleDegrees)
            return StickProcessor.processNormalizedStick(
                (cos(radians) * radius).toFloat(),
                (sin(radians) * radius).toFloat(),
                stickConfig,
            )
        }

        var result = StickProcessor.resolveDirection(at(0.0), config = directionConfig)
        assertEquals(StickProcessor.StickDirection.RIGHT, result.direction)

        result = StickProcessor.resolveDirection(
            at(28.0),
            previous = result.state,
            config = directionConfig,
        )
        assertEquals(StickProcessor.StickDirection.RIGHT, result.direction)

        result = StickProcessor.resolveDirection(
            at(31.0),
            previous = result.state,
            config = directionConfig,
        )
        assertEquals(StickProcessor.StickDirection.DOWN_RIGHT, result.direction)

        result = StickProcessor.resolveDirection(
            at(31.0, radius = 0.40f),
            previous = result.state,
            config = directionConfig,
        )
        assertEquals(StickProcessor.StickDirection.DOWN_RIGHT, result.direction)

        result = StickProcessor.resolveDirection(
            at(31.0, radius = 0.34f),
            previous = result.state,
            config = directionConfig,
        )
        assertTrue(result.isNeutral)

        result = StickProcessor.resolveDirection(
            at(0.0, radius = 0.49f),
            previous = result.state,
            config = directionConfig,
        )
        assertTrue(result.isNeutral)

        result = StickProcessor.resolveDirection(
            at(0.0, radius = 0.50f),
            previous = result.state,
            config = directionConfig,
        )
        assertEquals(StickProcessor.StickDirection.RIGHT, result.direction)
    }

    @Test
    fun angleNormalizationUsesShortestSignedDelta() {
        assertEquals(-170.0f, StickProcessor.normalizeAngleDegrees(190.0f), 0.0f)
        assertEquals(170.0f, StickProcessor.normalizeAngleDegrees(-190.0f), 0.0f)
        assertEquals(180.0f, StickProcessor.normalizeAngleDegrees(540.0f), 0.0f)
        assertEquals(-180.0f, StickProcessor.normalizeAngleDegrees(-540.0f), 0.0f)
        assertEquals(2.0f, StickProcessor.shortestAngleDeltaDegrees(179.0f, -179.0f), 0.0f)
        assertEquals(-2.0f, StickProcessor.shortestAngleDeltaDegrees(-179.0f, 179.0f), 0.0f)
    }

    @Test
    fun triggerNormalizationAndHysteresisAreIndependentFromStickState() {
        val range = StickProcessor.MotionRangeLike(-1.0f, 3.0f)
        assertEquals(0.0f, StickProcessor.normalizeTrigger(-1.0f, range), 0.0f)
        assertEquals(0.50f, StickProcessor.normalizeTrigger(1.0f, range), 0.0001f)
        assertEquals(1.0f, StickProcessor.normalizeTrigger(3.0f, range), 0.0f)

        val thresholds = StickProcessor.TriggerThresholds(press = 0.55f, release = 0.40f)
        var state = StickProcessor.updateTrigger(0.54f, thresholds = thresholds)
        assertFalse(state.pressed)
        state = StickProcessor.updateTrigger(0.55f, state, thresholds)
        assertTrue(state.pressed)
        state = StickProcessor.updateTrigger(0.50f, state, thresholds)
        assertTrue(state.pressed)
        state = StickProcessor.updateTrigger(0.40f, state, thresholds)
        assertFalse(state.pressed)

        state = StickProcessor.updateTrigger(0.80f, thresholds = thresholds)
        assertTrue(state.pressed)
        state = StickProcessor.updateTrigger(Float.NaN, state, thresholds)
        assertTrue(state.pressed)
    }

    @Test
    fun calibrationValidationRejectsBadOrNoisyCandidatesWithoutReplacementChannels() {
        val constraints = StickProcessor.CalibrationConstraints(
            minimumSpan = 0.50f,
            maximumRestSpread = 0.10f,
            minimumSamples = 10,
        )
        val valid = StickProcessor.validateCalibration(
            observations = listOf(
                StickProcessor.CalibrationObservation(
                    min = -1.0f,
                    max = 1.0f,
                    rest = 0.02f,
                    restSpread = 0.03f,
                    sampleCount = 10,
                ),
                StickProcessor.CalibrationObservation(
                    min = 0.0f,
                    max = 1.0f,
                    rest = 0.0f,
                    restSpread = 0.01f,
                    sampleCount = 10,
                    restMustBeInterior = false,
                ),
            ),
            constraints = constraints,
        )
        assertTrue(valid.accepted)
        assertEquals(2, valid.channels.size)

        val noisy = StickProcessor.validateCalibration(
            listOf(
                StickProcessor.CalibrationObservation(
                    min = -1.0f,
                    max = 1.0f,
                    rest = 0.0f,
                    restSpread = 0.11f,
                    sampleCount = 10,
                ),
            ),
            constraints,
        )
        assertFalse(noisy.accepted)
        assertEquals(StickProcessor.CalibrationIssue.REST_TOO_NOISY, noisy.issue)
        assertTrue(noisy.channels.isEmpty())

        val invalidRange = StickProcessor.validateCalibration(
            listOf(
                StickProcessor.CalibrationObservation(
                    min = 0.0f,
                    max = 0.01f,
                    rest = 0.005f,
                    sampleCount = 10,
                ),
            ),
            constraints,
        )
        assertFalse(invalidRange.accepted)
        assertEquals(StickProcessor.CalibrationIssue.INVALID_RANGE, invalidRange.issue)
    }
}
