package io.github.h3nb.jlmodplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadCalibrationSessionTest {
    @Test
    fun neutralMustRemainContinuousBeforeRangeCollection() {
        val session = GamepadCalibrationSession(
            neutralDurationMillis = 1_000L,
            minimumSamples = 2,
        )
        val neutral = mapOf(
            CalibrationChannel.LEFT_X to 0.0f,
            CalibrationChannel.LEFT_Y to 0.0f,
            CalibrationChannel.RIGHT_X to 0.0f,
            CalibrationChannel.RIGHT_Y to 0.0f,
            CalibrationChannel.LEFT_TRIGGER to 0.0f,
            CalibrationChannel.RIGHT_TRIGGER to 0.0f,
        )

        assertEquals(CalibrationEvent.NEUTRAL_STARTED, session.observeNeutral(0L, neutral, true).event)
        assertEquals(CalibrationEvent.NEUTRAL_RESET, session.observeNeutral(500L, neutral, false).event)
        assertEquals(CalibrationPhase.WAIT_NEUTRAL, session.phase)
        session.observeNeutral(600L, neutral, true)
        assertEquals(CalibrationPhase.WAIT_NEUTRAL, session.phase)
        assertEquals(CalibrationEvent.NEUTRAL_READY, session.observeNeutral(1_600L, neutral, true).event)
        assertEquals(CalibrationPhase.STICK_RANGE, session.phase)
    }

    @Test
    fun validStickAndTriggerRangesProduceReviewableCandidate() {
        val session = GamepadCalibrationSession(
            neutralDurationMillis = 0L + 1L,
            minimumSamples = 1,
        )
        val neutral = CalibrationChannel.entries.associateWith { 0.0f }
        session.observeNeutral(0L, neutral, true)
        session.observeNeutral(1L, neutral, true)
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_X to -1.0f,
                CalibrationChannel.LEFT_Y to -1.0f,
                CalibrationChannel.RIGHT_X to -1.0f,
                CalibrationChannel.RIGHT_Y to -1.0f,
            ),
        )
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_X to 1.0f,
                CalibrationChannel.LEFT_Y to 1.0f,
                CalibrationChannel.RIGHT_X to 1.0f,
                CalibrationChannel.RIGHT_Y to 1.0f,
            ),
        )
        assertEquals(CalibrationPhase.TRIGGER_RANGE, session.finishSticks().phase)
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_TRIGGER to 0.0f,
                CalibrationChannel.RIGHT_TRIGGER to 0.0f,
            ),
        )
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_TRIGGER to 1.0f,
                CalibrationChannel.RIGHT_TRIGGER to 1.0f,
            ),
        )

        val review = session.finishTriggers()
        assertEquals(CalibrationEvent.REVIEW_READY, review.event)
        assertEquals(CalibrationPhase.REVIEW, session.phase)
        assertNotNull(session.candidateProfile)
        assertEquals(CalibrationEvent.COMMITTED, session.commit().event)
        assertEquals(CalibrationPhase.COMMITTED, session.phase)
        assertNotNull(session.profile)
    }

    @Test
    fun offsetCenteredStickCalibrationDoesNotRequireZeroCrossing() {
        val session = GamepadCalibrationSession(
            neutralDurationMillis = 1L,
            minimumSamples = 1,
            requiredChannels = setOf(
                CalibrationChannel.LEFT_X,
                CalibrationChannel.LEFT_Y,
            ),
        )
        val neutral = mapOf(
            CalibrationChannel.LEFT_X to 127.0f,
            CalibrationChannel.LEFT_Y to 127.0f,
        )
        assertEquals(CalibrationEvent.NEUTRAL_STARTED, session.observeNeutral(0L, neutral, true).event)
        assertEquals(CalibrationEvent.NEUTRAL_READY, session.observeNeutral(1L, neutral, true).event)
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_X to 0.0f,
                CalibrationChannel.LEFT_Y to 0.0f,
            ),
        )
        session.observeRange(
            mapOf(
                CalibrationChannel.LEFT_X to 255.0f,
                CalibrationChannel.LEFT_Y to 255.0f,
            ),
        )
        assertEquals(CalibrationEvent.RANGE_ACCEPTED, session.finishSticks().event)
        val review = session.finishTriggers()
        assertEquals(CalibrationEvent.REVIEW_READY, review.event)
        assertEquals(CalibrationPhase.REVIEW, review.phase)
        assertNotNull(session.candidateProfile)
    }

    @Test
    fun invalidCandidateAndCancelDoNotOverwritePreviousProfile() {
        val previous = buildValidProfile()
        val session = GamepadCalibrationSession(previous = previous, minimumSamples = 2)
        val neutral = CalibrationChannel.entries.associateWith { 0.0f }
        session.observeNeutral(0L, neutral, true)
        session.observeNeutral(1_000L, neutral, true)
        session.observeRange(
            CalibrationChannel.entries
                .filter(CalibrationChannel::isStick)
                .associateWith { 0.0f },
        )
        val invalid = session.finishSticks()
        assertEquals(CalibrationEvent.INVALID, invalid.event)
        assertEquals(StickProcessor.CalibrationIssue.INSUFFICIENT_SAMPLES, invalid.validation?.issue)
        assertEquals(previous, session.profile)
        assertNull(session.candidateProfile)

        assertEquals(CalibrationEvent.CANCELLED, session.cancel().event)
        assertEquals(previous, session.profile)
    }

    private fun buildValidProfile(): GamepadCalibration {
        val channels = CalibrationChannel.entries.associateWith {
            StickProcessor.ValidatedCalibration(
                range = StickProcessor.MotionRangeLike(-1.0f, 1.0f),
                rest = 0.0f,
                restSpread = 0.0f,
                sampleCount = 1,
                restMustBeInterior = it.isStick,
            )
        }
        return GamepadCalibration(channels)
    }
}
