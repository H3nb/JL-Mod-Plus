package io.github.h3nb.jlmodplus.input

import java.util.Collections
import java.util.EnumMap

enum class CalibrationPhase {
    WAIT_NEUTRAL,
    STICK_RANGE,
    TRIGGER_RANGE,
    REVIEW,
    COMMITTED,
    CANCELLED,
}

enum class CalibrationChannel(
    val isStick: Boolean,
) {
    LEFT_X(true),
    LEFT_Y(true),
    RIGHT_X(true),
    RIGHT_Y(true),
    LEFT_TRIGGER(false),
    RIGHT_TRIGGER(false),
}

data class GamepadCalibration(
    val channels: Map<CalibrationChannel, StickProcessor.ValidatedCalibration>,
) {
    init {
        require(channels.isNotEmpty()) { "calibration must contain at least one channel" }
    }

    val immutableChannels: Map<CalibrationChannel, StickProcessor.ValidatedCalibration> =
        Collections.unmodifiableMap(EnumMap(channels))
}

enum class CalibrationEvent {
    NO_CHANGE,
    NEUTRAL_STARTED,
    NEUTRAL_RESET,
    NEUTRAL_READY,
    RANGE_ACCEPTED,
    INVALID,
    REVIEW_READY,
    COMMITTED,
    CANCELLED,
}

data class CalibrationStep(
    val event: CalibrationEvent,
    val phase: CalibrationPhase,
    val validation: StickProcessor.CalibrationValidationResult? = null,
    val candidate: GamepadCalibration? = null,
)

/**
 * Deterministic calibration session. It owns only a candidate; [profile]
 * remains the previous committed calibration until [commit] succeeds.
 */
class GamepadCalibrationSession @JvmOverloads constructor(
    previous: GamepadCalibration? = null,
    private val neutralDurationMillis: Long = DEFAULT_NEUTRAL_DURATION_MILLIS,
    private val minimumSamples: Int = DEFAULT_MINIMUM_SAMPLES,
    private val minimumSpan: Float = DEFAULT_MINIMUM_SPAN,
    private val maximumRestSpread: Float = DEFAULT_MAXIMUM_REST_SPREAD,
    requiredChannels: Set<CalibrationChannel> = CalibrationChannel.entries.toSet(),
) {
    init {
        require(neutralDurationMillis > 0L) { "neutralDurationMillis must be positive" }
        require(minimumSamples > 0) { "minimumSamples must be positive" }
        require(minimumSpan.isFinite() && minimumSpan > 0.0f) {
            "minimumSpan must be finite and positive"
        }
        require(maximumRestSpread.isFinite() && maximumRestSpread >= 0.0f) {
            "maximumRestSpread must be finite and non-negative"
        }
        require(requiredChannels.isNotEmpty()) { "requiredChannels must not be empty" }
    }

    private val rest = EnumMap<CalibrationChannel, SampleStats>(CalibrationChannel::class.java)
    private val ranges = EnumMap<CalibrationChannel, RangeStats>(CalibrationChannel::class.java)
    private val requiredChannels = requiredChannels.toSet()
    private var neutralStartedAt: Long? = null
    private var currentPhase = CalibrationPhase.WAIT_NEUTRAL
    private var candidate: GamepadCalibration? = null
    private var committed: GamepadCalibration? = previous
    private var validation: StickProcessor.CalibrationValidationResult? = null

    val phase: CalibrationPhase
        get() = currentPhase

    val profile: GamepadCalibration?
        get() = committed

    val candidateProfile: GamepadCalibration?
        get() = candidate

    val lastValidation: StickProcessor.CalibrationValidationResult?
        get() = validation

    /**
     * Feeds a neutral sample batch. A non-neutral sample restarts the
     * continuous neutral timer and clears the new candidate's rest samples.
     */
    fun observeNeutral(
        nowMillis: Long,
        values: Map<CalibrationChannel, Float>,
        allNeutral: Boolean,
    ): CalibrationStep {
        if (currentPhase != CalibrationPhase.WAIT_NEUTRAL) return step()
        if (!allNeutral) {
            neutralStartedAt = null
            rest.clear()
            return step(CalibrationEvent.NEUTRAL_RESET)
        }
        if (values.values.any { !it.isFinite() }) {
            return invalidStep(StickProcessor.CalibrationIssue.NON_FINITE)
        }
        val started = neutralStartedAt
        if (started == null) {
            neutralStartedAt = nowMillis
            addSamples(rest, values)
            return step(CalibrationEvent.NEUTRAL_STARTED)
        }
        addSamples(rest, values)
        val elapsed = if (nowMillis >= started) nowMillis - started else 0L
        if (elapsed < neutralDurationMillis || !hasMinimumRestSamples()) {
            return step()
        }
        currentPhase = CalibrationPhase.STICK_RANGE
        return step(CalibrationEvent.NEUTRAL_READY)
    }

    /** Advances the neutral timer without fabricating another hardware sample. */
    fun tickNeutral(nowMillis: Long): CalibrationStep {
        if (currentPhase != CalibrationPhase.WAIT_NEUTRAL) return step()
        val started = neutralStartedAt ?: return step()
        val elapsed = if (nowMillis >= started) nowMillis - started else 0L
        if (elapsed < neutralDurationMillis || !hasMinimumRestSamples()) return step()
        currentPhase = CalibrationPhase.STICK_RANGE
        return step(CalibrationEvent.NEUTRAL_READY)
    }

    fun observeRange(
        values: Map<CalibrationChannel, Float>,
    ): CalibrationStep {
        if (currentPhase != CalibrationPhase.STICK_RANGE &&
            currentPhase != CalibrationPhase.TRIGGER_RANGE
        ) {
            return step()
        }
        if (values.values.any { !it.isFinite() }) {
            return invalidStep(StickProcessor.CalibrationIssue.NON_FINITE)
        }
        val allowed = if (currentPhase == CalibrationPhase.STICK_RANGE) {
            CalibrationChannel.entries.filter(CalibrationChannel::isStick).toSet()
        } else {
            CalibrationChannel.entries.filterNot(CalibrationChannel::isStick).toSet()
        }
        addRanges(ranges, values.filterKeys(allowed::contains))
        return step(CalibrationEvent.RANGE_ACCEPTED)
    }

    fun finishSticks(): CalibrationStep {
        if (currentPhase != CalibrationPhase.STICK_RANGE) return step()
        if (!hasMinimumSamplesForSticks()) {
            return invalidStep(StickProcessor.CalibrationIssue.INSUFFICIENT_SAMPLES)
        }
        currentPhase = CalibrationPhase.TRIGGER_RANGE
        return step(CalibrationEvent.RANGE_ACCEPTED)
    }

    fun finishTriggers(): CalibrationStep {
        if (currentPhase != CalibrationPhase.TRIGGER_RANGE) return step()
        if (!hasMinimumRangeSamples()) {
            return invalidStep(StickProcessor.CalibrationIssue.INSUFFICIENT_SAMPLES)
        }
        val observations = requiredChannels.toList().sortedBy(CalibrationChannel::ordinal).map { channel ->
            val restStats = rest[channel] ?: return invalidStep(
                StickProcessor.CalibrationIssue.NO_CHANNELS,
            )
            val rangeStats = ranges[channel] ?: return invalidStep(
                StickProcessor.CalibrationIssue.INVALID_RANGE,
            )
            StickProcessor.CalibrationObservation(
                min = rangeStats.min,
                max = rangeStats.max,
                rest = restStats.average,
                restSpread = restStats.spread,
                sampleCount = minOf(restStats.count, rangeStats.count),
                restMustBeInterior = channel.isStick,
            )
        }
        val result = StickProcessor.validateCalibration(
            observations = observations,
            constraints = StickProcessor.CalibrationConstraints(
                minimumSpan = minimumSpan,
                maximumRestSpread = maximumRestSpread,
                minimumSamples = minimumSamples,
            ),
        )
        validation = result
        if (!result.accepted) return CalibrationStep(
            event = CalibrationEvent.INVALID,
            phase = currentPhase,
            validation = result,
        )
        val channels = EnumMap<CalibrationChannel, StickProcessor.ValidatedCalibration>(
            CalibrationChannel::class.java,
        )
        requiredChannels.toList().sortedBy(CalibrationChannel::ordinal).forEachIndexed { index, channel ->
            channels[channel] = result.channels[index]
        }
        candidate = GamepadCalibration(channels)
        currentPhase = CalibrationPhase.REVIEW
        return step(CalibrationEvent.REVIEW_READY)
    }

    fun commit(): CalibrationStep {
        if (currentPhase != CalibrationPhase.REVIEW || candidate == null) return step()
        committed = candidate
        currentPhase = CalibrationPhase.COMMITTED
        return step(CalibrationEvent.COMMITTED)
    }

    fun cancel(): CalibrationStep {
        if (currentPhase == CalibrationPhase.COMMITTED ||
            currentPhase == CalibrationPhase.CANCELLED
        ) {
            return step()
        }
        candidate = null
        currentPhase = CalibrationPhase.CANCELLED
        return step(CalibrationEvent.CANCELLED)
    }

    private fun invalidStep(issue: StickProcessor.CalibrationIssue): CalibrationStep {
        val result = StickProcessor.CalibrationValidationResult(
            accepted = false,
            issue = issue,
        )
        validation = result
        candidate = null
        return CalibrationStep(CalibrationEvent.INVALID, currentPhase, result)
    }

    private fun step(event: CalibrationEvent = CalibrationEvent.NO_CHANGE): CalibrationStep =
        CalibrationStep(event, currentPhase, validation, candidate)

    private fun hasMinimumRestSamples(): Boolean =
        requiredChannels.all { (rest[it]?.count ?: 0) >= minimumSamples }

    private fun hasMinimumRangeSamples(): Boolean =
        requiredChannels.all { (ranges[it]?.count ?: 0) >= minimumSamples }

    private fun hasMinimumSamplesForSticks(): Boolean =
        requiredChannels.filter(CalibrationChannel::isStick).all {
            (ranges[it]?.count ?: 0) >= minimumSamples &&
                (rest[it]?.count ?: 0) >= minimumSamples
        }

    private data class SampleStats(
        var count: Int = 0,
        var sum: Double = 0.0,
        var min: Float = Float.POSITIVE_INFINITY,
        var max: Float = Float.NEGATIVE_INFINITY,
    ) {
        val average: Float
            get() = (sum / count).toFloat()
        val spread: Float
            get() = max - min

        fun add(value: Float) {
            count += 1
            sum += value.toDouble()
            min = minOf(min, value)
            max = maxOf(max, value)
        }
    }

    private data class RangeStats(
        var count: Int = 0,
        var min: Float = Float.POSITIVE_INFINITY,
        var max: Float = Float.NEGATIVE_INFINITY,
    ) {
        fun add(value: Float) {
            count += 1
            min = minOf(min, value)
            max = maxOf(max, value)
        }
    }

    private fun addSamples(
        destination: MutableMap<CalibrationChannel, SampleStats>,
        values: Map<CalibrationChannel, Float>,
    ) {
        values.forEach { (channel, value) ->
            destination.getOrPut(channel) { SampleStats() }.add(value)
        }
    }

    private fun addRanges(
        destination: MutableMap<CalibrationChannel, RangeStats>,
        values: Map<CalibrationChannel, Float>,
    ) {
        values.forEach { (channel, value) ->
            destination.getOrPut(channel) { RangeStats() }.add(value)
        }
    }

    companion object {
        const val DEFAULT_NEUTRAL_DURATION_MILLIS: Long = 1_000L
        const val DEFAULT_MINIMUM_SAMPLES: Int = 1
        const val DEFAULT_MINIMUM_SPAN: Float = 0.10f
        const val DEFAULT_MAXIMUM_REST_SPREAD: Float = 0.15f

    }
}
