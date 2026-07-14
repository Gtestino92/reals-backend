package com.reals.backend.service.reliability

import com.reals.backend.domain.UserReliabilityEvent
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.UserReliabilityEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

@Service
@Transactional
class UserReliabilityScoreService(
    private val eventRepository: UserReliabilityEventRepository,

    @param:Value("\${user-reliability.enabled:false}")
    val enabled: Boolean,

    @param:Value("\${user-reliability.base-score:100}")
    private val baseScore: Int,

    @param:Value("\${user-reliability.full-weight-days:10}")
    private val fullWeightDays: Long,

    @param:Value("\${user-reliability.half-weight-days:10}")
    private val halfWeightDays: Long,

    @param:Value("\${user-reliability.expiration-days:20}")
    private val expirationDays: Long,

    @param:Value("\${user-reliability.matchmaking.max-modifier:0.05}")
    private val maxMatchmakingModifier: Double
) {

    data class ScoreBreakdown(
        val userId: UUID,
        val enabled: Boolean,
        val baseScore: Int,
        val weightedDelta: Double,
        val effectiveScore: Double,
        val events: List<EventBreakdown>
    )

    data class EventBreakdown(
        val event: UserReliabilityEvent,
        val temporalWeight: Double,
        val effectiveDelta: Double
    )

    fun recordEvent(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedMatchId: UUID? = null,
        relatedConnectionId: UUID? = null,
        relatedChatId: UUID? = null,
        relatedSafetyReportId: UUID? = null,
        occurredAt: OffsetDateTime = OffsetDateTime.now()
    ): UserReliabilityEvent? {
        if (!enabled) {
            return null
        }

        if (
            alreadyRecorded(
                userId = userId,
                eventType = eventType,
                relatedMatchId = relatedMatchId,
                relatedConnectionId = relatedConnectionId,
                relatedChatId = relatedChatId,
                relatedSafetyReportId = relatedSafetyReportId
            )
        ) {
            return null
        }

        return try {
            eventRepository.save(
                UserReliabilityEvent(
                    userId = userId,
                    relatedMatchId = relatedMatchId,
                    relatedConnectionId = relatedConnectionId,
                    relatedChatId = relatedChatId,
                    relatedSafetyReportId = relatedSafetyReportId,
                    eventType = eventType,
                    dimension = eventType.dimension,
                    delta = eventType.delta,
                    occurredAt = occurredAt,
                    expiresAt = occurredAt.plusDays(expirationDays)
                )
            )
        } catch (_: DataIntegrityViolationException) {
            null
        }
    }

    fun effectiveScore(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Double =
        effectiveScoreForEvents(
            events = eventRepository.findByUserIdAndExpiresAtAfterOrderByOccurredAtDesc(userId, now),
            now = now
        )

    @Transactional(readOnly = true)
    fun scoreBreakdown(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ScoreBreakdown {
        if (!enabled) {
            return ScoreBreakdown(
                userId = userId,
                enabled = false,
                baseScore = baseScore,
                weightedDelta = 0.0,
                effectiveScore = baseScore.toDouble(),
                events = emptyList()
            )
        }

        val eventBreakdowns =
            eventRepository.findByUserIdAndExpiresAtAfterOrderByOccurredAtDesc(userId, now)
                .map { event ->
                    val weight = weightFor(event.occurredAt, now)
                    EventBreakdown(
                        event = event,
                        temporalWeight = weight,
                        effectiveDelta = event.delta * weight
                    )
                }
                .filter { it.temporalWeight > 0.0 }

        val weightedDelta = eventBreakdowns.sumOf { it.effectiveDelta }

        return ScoreBreakdown(
            userId = userId,
            enabled = true,
            baseScore = baseScore,
            weightedDelta = weightedDelta,
            effectiveScore = baseScore + weightedDelta,
            events = eventBreakdowns
        )
    }

    @Transactional(readOnly = true)
    fun effectiveScores(
        userIds: Collection<UUID>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Map<UUID, Double> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }
        if (!enabled) {
            return userIds.associateWith { baseScore.toDouble() }
        }

        val eventsByUser =
            eventRepository.findByUserIdInAndExpiresAtAfter(userIds, now)
                .groupBy { it.userId }

        return userIds.associateWith { userId ->
            effectiveScoreForEvents(
                events = eventsByUser[userId].orEmpty(),
                now = now
            )
        }
    }

    @Transactional(readOnly = true)
    fun matchmakingModifierForPair(
        userAId: UUID,
        userBId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Double {
        if (!enabled) {
            return 0.0
        }

        val scores = effectiveScores(listOf(userAId, userBId), now)
        return matchmakingModifierForScores(
            userAScore = scores[userAId] ?: baseScore.toDouble(),
            userBScore = scores[userBId] ?: baseScore.toDouble()
        )
    }

    fun matchmakingModifierForScores(
        userAScore: Double,
        userBScore: Double
    ): Double {
        if (!enabled) {
            return 0.0
        }

        val maxModifier = maxMatchmakingModifier.coerceAtLeast(0.0)
        if (maxModifier == 0.0 || baseScore <= 0) {
            return 0.0
        }

        val averageOffset = ((userAScore + userBScore) / 2.0 - baseScore) / baseScore
        val reliabilityGap = abs(userAScore - userBScore) / baseScore
        val rawModifier = (averageOffset * maxModifier) - (reliabilityGap * maxModifier * 0.25)

        return rawModifier.coerceIn(-maxModifier, maxModifier)
    }

    fun deleteExpiredEvents(now: OffsetDateTime = OffsetDateTime.now()): Int =
        eventRepository.deleteExpiredEvents(now)

    private fun effectiveScoreForEvents(
        events: List<UserReliabilityEvent>,
        now: OffsetDateTime
    ): Double =
        baseScore + events.sumOf { event ->
            event.delta * weightFor(event.occurredAt, now)
        }

    private fun weightFor(
        occurredAt: OffsetDateTime,
        now: OffsetDateTime
    ): Double {
        val ageDays = ChronoUnit.DAYS.between(occurredAt, now)

        return when {
            ageDays < 0 -> 1.0
            ageDays < fullWeightDays -> 1.0
            ageDays < fullWeightDays + halfWeightDays -> 0.5
            else -> 0.0
        }
    }

    private fun alreadyRecorded(
        userId: UUID,
        eventType: UserReliabilityEventType,
        relatedMatchId: UUID?,
        relatedConnectionId: UUID?,
        relatedChatId: UUID?,
        relatedSafetyReportId: UUID?
    ): Boolean =
        relatedMatchId?.let {
            eventRepository.existsByUserIdAndEventTypeAndRelatedMatchId(userId, eventType, it)
        } == true ||
            relatedConnectionId?.let {
                eventRepository.existsByUserIdAndEventTypeAndRelatedConnectionId(userId, eventType, it)
            } == true ||
            relatedChatId?.let {
                eventRepository.existsByUserIdAndEventTypeAndRelatedChatId(userId, eventType, it)
            } == true ||
            relatedSafetyReportId?.let {
                eventRepository.existsByUserIdAndEventTypeAndRelatedSafetyReportId(userId, eventType, it)
            } == true
}
