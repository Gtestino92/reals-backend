package com.reals.backend.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.roundToLong

fun interface VisualReviewAvailabilityRandomSource {
    fun nextUnitDouble(): Double
}

@Component
class ThreadLocalVisualReviewAvailabilityRandomSource : VisualReviewAvailabilityRandomSource {
    override fun nextUnitDouble(): Double =
        ThreadLocalRandom.current().nextDouble()
}

@Service
class VisualReviewAvailabilityPolicy(
    private val randomSource: VisualReviewAvailabilityRandomSource,

    @param:Value("\${chat.visual-phase.availability.base-reliability-score:100}")
    private val baseReliabilityScore: Double,

    @param:Value("\${chat.visual-phase.availability.base-delay-minutes:10}")
    private val baseDelayMinutes: Long,

    @param:Value("\${chat.visual-phase.availability.jitter-range-minutes:5}")
    private val jitterRangeMinutes: Long,

    @param:Value("\${chat.visual-phase.availability.max-reliability-shift-minutes:4}")
    private val maxReliabilityShiftMinutes: Long,

    @param:Value("\${chat.visual-phase.availability.reliability-influence-span-points:20}")
    private val reliabilityInfluenceSpanPoints: Double
) {

    init {
        require(jitterRangeMinutes >= 0) {
            "chat.visual-phase.availability.jitter-range-minutes must be non-negative"
        }
        require(maxReliabilityShiftMinutes >= 0) {
            "chat.visual-phase.availability.max-reliability-shift-minutes must be non-negative"
        }
        require(reliabilityInfluenceSpanPoints > 0.0) {
            "chat.visual-phase.availability.reliability-influence-span-points must be positive"
        }
        require(baseDelayMinutes > jitterRangeMinutes + maxReliabilityShiftMinutes) {
            "chat.visual-phase.availability.base-delay-minutes must be greater than jitter plus reliability shift"
        }
    }

    fun availableAt(
        now: OffsetDateTime,
        pairReliabilityScore: Double?
    ): OffsetDateTime =
        now.plus(sampleDelay(pairReliabilityScore))

    fun sampleDelay(pairReliabilityScore: Double?): Duration {
        val unit = randomSource.nextUnitDouble()
        require(unit in 0.0..1.0) {
            "Visual review availability random source must return a value in [0.0, 1.0]"
        }

        val jitterSeconds = ((unit * 2.0) - 1.0) * Duration.ofMinutes(jitterRangeMinutes).seconds
        val reliabilityShiftSeconds = reliabilityShift(pairReliabilityScore).toNanos().toDouble() / NANOS_PER_SECOND
        val delaySeconds = Duration.ofMinutes(baseDelayMinutes).seconds + reliabilityShiftSeconds + jitterSeconds

        return Duration.ofNanos((delaySeconds * NANOS_PER_SECOND).roundToLong())
    }

    private fun reliabilityShift(pairReliabilityScore: Double?): Duration {
        if (pairReliabilityScore == null || maxReliabilityShiftMinutes == 0L) {
            return Duration.ZERO
        }

        val normalized =
            ((pairReliabilityScore - baseReliabilityScore) / reliabilityInfluenceSpanPoints)
                .coerceIn(-1.0, 1.0)
        val shiftSeconds = -normalized * Duration.ofMinutes(maxReliabilityShiftMinutes).seconds

        return Duration.ofNanos((shiftSeconds * NANOS_PER_SECOND).roundToLong())
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
