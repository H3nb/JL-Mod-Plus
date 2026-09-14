package io.github.h3nb.jlmodplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerCaptureSessionTest {
    private val openingSource = CaptureSource("pad", 1L, "button-a")
    private val candidateSource = CaptureSource("pad", 1L, "button-b")

    @Test
    fun openerMustReleaseBeforeCandidateCapture() {
        val session = ControllerCaptureSession(
            opening = CaptureOpening(openingSource, "button_a"),
            startTimeMillis = 0L,
        )

        assertEquals(CapturePhase.WAIT_OPENING_RELEASE, session.phase)
        assertEquals(
            CaptureEvent.NO_CHANGE,
            session.onButton(candidateSource, "button_x", true, 1L).event,
        )
        assertEquals(
            CaptureEvent.OPENING_RELEASED,
            session.onButton(openingSource, "button_a", false, 2L).event,
        )
        val found = session.onButton(candidateSource, "button_b", true, 3L)
        assertEquals(CaptureEvent.CANDIDATE_FOUND, found.event)
        assertEquals(CaptureCandidateKind.BUTTON, found.candidate?.kind)
        assertEquals(CapturePhase.CANDIDATE, session.phase)
    }

    @Test
    fun candidateRequiresReleaseAndStaleReleaseCannotAdvanceReview() {
        val session = armedSession()
        val found = session.onAxis(candidateSource, "right_x", 0.8f, 10L)
        assertEquals(CaptureCandidateKind.AXIS, found.candidate?.kind)

        assertEquals(
            CaptureEvent.NO_CHANGE,
            session.onAxis(candidateSource.copy(sessionId = 2L), "right_x", 0.0f, 11L).event,
        )
        assertEquals(CapturePhase.CANDIDATE, session.phase)
        assertEquals(CaptureEvent.WAITING_FOR_RELEASE, session.beginReview(12L).event)
        assertEquals(
            CaptureEvent.REVIEW_READY,
            session.onAxis(candidateSource, "right_x", 0.0f, 13L).event,
        )
        assertEquals(CapturePhase.REVIEW, session.phase)
        assertEquals(CaptureEvent.COMMITTED, session.commit().event)
        assertEquals(CapturePhase.COMMIT, session.phase)
    }

    @Test
    fun reservedStartCancelsAndAmbiguityCanRetryAfterAllSourcesRelease() {
        val session = armedSession()
        assertEquals(
            CaptureEvent.CANCELLED,
            session.onButton(candidateSource, ControllerCaptureSession.RESERVED_START_TOKEN, true, 5L)
                .event,
        )
        assertEquals(CaptureCancelReason.RESERVED_START, session.cancelReason)

        val retryable = armedSession()
        retryable.onButton(candidateSource, "button_b", true, 5L)
        val other = CaptureSource("pad", 1L, "button-x")
        assertEquals(
            CaptureEvent.AMBIGUOUS,
            retryable.onButton(other, "button_x", true, 6L).event,
        )
        retryable.onButton(candidateSource, "button_b", false, 7L)
        assertEquals(CapturePhase.WAIT_CANDIDATE_RELEASE, retryable.phase)
        assertEquals(
            CaptureEvent.RETRY_READY,
            retryable.onButton(other, "button_x", false, 8L).event,
        )
        assertEquals(CapturePhase.ARMED, retryable.phase)
        assertNull(retryable.candidate)
        assertTrue(retryable.retry().event == CaptureEvent.NO_CHANGE)
    }

    @Test
    fun timeoutIsDeterministicAndLateEventsStayCancelled() {
        val session = ControllerCaptureSession(
            opening = CaptureOpening(openingSource, "button_a"),
            startTimeMillis = 0L,
            timeoutMillis = 10L,
        )
        val timeout = session.tick(10L)
        assertEquals(CaptureEvent.TIMED_OUT, timeout.event)
        assertEquals(CaptureCancelReason.TIMEOUT, session.cancelReason)
        assertEquals(
            CapturePhase.CANCEL,
            session.onButton(openingSource, "button_a", false, 11L).phase,
        )
    }

    private fun armedSession(): ControllerCaptureSession {
        val session = ControllerCaptureSession(
            opening = CaptureOpening(openingSource, "button_a"),
            startTimeMillis = 0L,
        )
        session.onButton(openingSource, "button_a", false, 1L)
        return session
    }
}
