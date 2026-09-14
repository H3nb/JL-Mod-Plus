package io.github.h3nb.jlmodplus.input

import kotlin.math.abs

/**
 * Android-free capture/remap state machine.
 *
 * The opener is identified by source and token. Its DOWN is never a candidate;
 * only its matching UP arms the capture. The caller owns the actual binding
 * editor and commits only after the candidate has been released and reviewed.
 */
enum class CapturePhase {
    WAIT_OPENING_RELEASE,
    ARMED,
    CANDIDATE,
    WAIT_CANDIDATE_RELEASE,
    REVIEW,
    COMMIT,
    CANCEL,
}

enum class CaptureCandidateKind {
    BUTTON,
    AXIS,
}

data class CaptureSource(
    val deviceId: String,
    val sessionId: Long,
    val channel: String,
)

data class CaptureOpening(
    val source: CaptureSource,
    val token: String,
)

data class CaptureCandidate(
    val kind: CaptureCandidateKind,
    val token: String,
    val value: Float,
    val source: CaptureSource,
)

enum class CaptureCancelReason {
    USER,
    TIMEOUT,
    RESERVED_START,
    AMBIGUOUS,
}

enum class CaptureEvent {
    NO_CHANGE,
    OPENING_RELEASED,
    CANDIDATE_FOUND,
    CANDIDATE_RELEASED,
    WAITING_FOR_RELEASE,
    REVIEW_READY,
    RETRY_READY,
    AMBIGUOUS,
    COMMITTED,
    CANCELLED,
    TIMED_OUT,
}

data class CaptureStep(
    val event: CaptureEvent,
    val phase: CapturePhase,
    val candidate: CaptureCandidate? = null,
    val cancelReason: CaptureCancelReason? = null,
)

class ControllerCaptureSession(
    private val opening: CaptureOpening,
    startTimeMillis: Long,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val axisThreshold: Float = DEFAULT_AXIS_THRESHOLD,
) {
    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(axisThreshold.isFinite() && axisThreshold > 0.0f && axisThreshold <= 1.0f) {
            "axisThreshold must be finite and in (0, 1]"
        }
    }

    private val deadlineMillis = saturatingAdd(startTimeMillis, timeoutMillis)
    private var currentPhase = CapturePhase.WAIT_OPENING_RELEASE
    private var currentCandidate: CaptureCandidate? = null
    private var candidateHeld = false
    private val blockedSources = LinkedHashSet<CaptureSource>()
    private var cancellation: CaptureCancelReason? = null

    val phase: CapturePhase
        get() = currentPhase

    val candidate: CaptureCandidate?
        get() = currentCandidate

    val deadline: Long
        get() = deadlineMillis

    val cancelReason: CaptureCancelReason?
        get() = cancellation

    fun snapshot(event: CaptureEvent = CaptureEvent.NO_CHANGE): CaptureStep =
        CaptureStep(event, currentPhase, currentCandidate, cancellation)

    /** Advances the timeout without requiring a new input event. */
    fun tick(nowMillis: Long): CaptureStep {
        if (expireIfNeeded(nowMillis)) {
            return snapshot(CaptureEvent.TIMED_OUT)
        }
        return snapshot()
    }

    fun onButton(
        source: CaptureSource,
        token: String,
        pressed: Boolean,
        nowMillis: Long,
    ): CaptureStep {
        if (expireIfNeeded(nowMillis)) return snapshot(CaptureEvent.TIMED_OUT)
        if (isTerminal()) return snapshot()

        if (currentPhase == CapturePhase.WAIT_OPENING_RELEASE) {
            return if (!pressed && source == opening.source && token == opening.token) {
                currentPhase = CapturePhase.ARMED
                snapshot(CaptureEvent.OPENING_RELEASED)
            } else {
                snapshot()
            }
        }

        if (pressed && token == RESERVED_START_TOKEN) {
            cancelInternal(CaptureCancelReason.RESERVED_START)
            return snapshot(CaptureEvent.CANCELLED)
        }

        return when (currentPhase) {
            CapturePhase.ARMED -> {
                if (!pressed) {
                    snapshot()
                } else {
                    findCandidate(
                        CaptureCandidate(
                            CaptureCandidateKind.BUTTON,
                            token,
                            1.0f,
                            source,
                        ),
                    )
                }
            }
            CapturePhase.CANDIDATE -> {
                val candidate = currentCandidate
                when {
                    candidate == null -> snapshot()
                    matches(candidate, source, token) && !pressed -> {
                        candidateHeld = false
                        snapshot(CaptureEvent.CANDIDATE_RELEASED)
                    }
                    matches(candidate, source, token) -> snapshot()
                    pressed -> becomeAmbiguous(source)
                    else -> snapshot()
                }
            }
            CapturePhase.WAIT_CANDIDATE_RELEASE -> {
                if (!pressed) releaseSource(source) else snapshot()
            }
            CapturePhase.REVIEW,
            CapturePhase.COMMIT,
            CapturePhase.CANCEL,
            CapturePhase.WAIT_OPENING_RELEASE -> snapshot()
        }
    }

    fun onAxis(
        source: CaptureSource,
        axisToken: String,
        value: Float,
        nowMillis: Long,
    ): CaptureStep {
        if (expireIfNeeded(nowMillis)) return snapshot(CaptureEvent.TIMED_OUT)
        if (isTerminal() || !value.isFinite()) return snapshot()
        val active = abs(value) >= axisThreshold

        return when (currentPhase) {
            CapturePhase.WAIT_OPENING_RELEASE -> snapshot()
            CapturePhase.ARMED -> if (active) {
                findCandidate(
                    CaptureCandidate(
                        CaptureCandidateKind.AXIS,
                        axisToken,
                        value,
                        source,
                    ),
                )
            } else {
                snapshot()
            }
            CapturePhase.CANDIDATE -> {
                val candidate = currentCandidate
                when {
                    candidate == null -> snapshot()
                    matches(candidate, source, axisToken) && !active -> {
                        candidateHeld = false
                        snapshot(CaptureEvent.CANDIDATE_RELEASED)
                    }
                    matches(candidate, source, axisToken) -> snapshot()
                    active -> becomeAmbiguous(source)
                    else -> snapshot()
                }
            }
            CapturePhase.WAIT_CANDIDATE_RELEASE -> {
                if (!active) releaseSource(source) else snapshot()
            }
            CapturePhase.REVIEW,
            CapturePhase.COMMIT,
            CapturePhase.CANCEL -> snapshot()
        }
    }

    /**
     * Moves a found candidate into its release barrier. A held candidate must
     * release before review; an already released candidate can be reviewed
     * immediately.
     */
    fun beginReview(nowMillis: Long): CaptureStep {
        if (expireIfNeeded(nowMillis)) return snapshot(CaptureEvent.TIMED_OUT)
        if (currentPhase != CapturePhase.CANDIDATE || currentCandidate == null) return snapshot()
        if (candidateHeld) {
            blockedSources.clear()
            blockedSources += currentCandidate!!.source
            currentPhase = CapturePhase.WAIT_CANDIDATE_RELEASE
            return snapshot(CaptureEvent.WAITING_FOR_RELEASE)
        }
        currentPhase = CapturePhase.REVIEW
        return snapshot(CaptureEvent.REVIEW_READY)
    }

    /** Commits only the reviewed candidate. */
    fun commit(): CaptureStep {
        if (currentPhase != CapturePhase.REVIEW || currentCandidate == null) return snapshot()
        currentPhase = CapturePhase.COMMIT
        return snapshot(CaptureEvent.COMMITTED)
    }

    fun cancel(reason: CaptureCancelReason = CaptureCancelReason.USER): CaptureStep {
        if (isTerminal()) return snapshot()
        cancelInternal(reason)
        return snapshot(CaptureEvent.CANCELLED)
    }

    /**
     * Allows an ambiguous capture to be retried after every involved source
     * has released. No held input is silently reused as a new binding.
     */
    fun retry(): CaptureStep {
        if (currentPhase != CapturePhase.WAIT_CANDIDATE_RELEASE ||
            currentCandidate != null ||
            blockedSources.isNotEmpty()
        ) {
            return snapshot()
        }
        currentPhase = CapturePhase.ARMED
        cancellation = null
        return snapshot(CaptureEvent.RETRY_READY)
    }

    private fun findCandidate(candidate: CaptureCandidate): CaptureStep {
        currentCandidate = candidate
        candidateHeld = true
        currentPhase = CapturePhase.CANDIDATE
        return snapshot(CaptureEvent.CANDIDATE_FOUND)
    }

    private fun becomeAmbiguous(source: CaptureSource): CaptureStep {
        currentCandidate?.source?.let(blockedSources::add)
        blockedSources += source
        currentCandidate = null
        candidateHeld = false
        cancellation = CaptureCancelReason.AMBIGUOUS
        currentPhase = CapturePhase.WAIT_CANDIDATE_RELEASE
        return snapshot(CaptureEvent.AMBIGUOUS)
    }

    private fun releaseSource(source: CaptureSource): CaptureStep {
        if (!blockedSources.remove(source)) return snapshot()
        if (blockedSources.isNotEmpty()) return snapshot(CaptureEvent.CANDIDATE_RELEASED)
        return if (currentCandidate == null) {
            currentPhase = CapturePhase.ARMED
            snapshot(CaptureEvent.RETRY_READY)
        } else {
            currentPhase = CapturePhase.REVIEW
            snapshot(CaptureEvent.REVIEW_READY)
        }
    }

    private fun matches(
        candidate: CaptureCandidate,
        source: CaptureSource,
        token: String,
    ): Boolean = candidate.source == source && candidate.token == token

    private fun expireIfNeeded(nowMillis: Long): Boolean {
        if (isTerminal() || nowMillis < deadlineMillis) return false
        cancelInternal(CaptureCancelReason.TIMEOUT)
        return true
    }

    private fun cancelInternal(reason: CaptureCancelReason) {
        cancellation = reason
        currentCandidate = null
        candidateHeld = false
        blockedSources.clear()
        currentPhase = CapturePhase.CANCEL
    }

    private fun isTerminal(): Boolean =
        currentPhase == CapturePhase.COMMIT || currentPhase == CapturePhase.CANCEL

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000L
        const val DEFAULT_AXIS_THRESHOLD: Float = 0.50f
        const val RESERVED_START_TOKEN: String = "button_start"

        private fun saturatingAdd(first: Long, second: Long): Long =
            if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE
            else first + second
    }
}
