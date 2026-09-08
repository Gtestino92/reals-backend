package com.reals.backend.service.engagement

import com.reals.backend.domain.EngagementType
import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.reliability.ReliabilityScoreProvider
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

data class EngagementCapacityAdmissionDecision(
    val userId: UUID,
    val capacity: EffectiveEngagementCapacity,
    val activeMatches: Int,
    val activeConnections: Int,
    val reliabilityBaseScore: Int,
    val outcome: EngagementCapacityOutcome
) {
    val allowed: Boolean = outcome == EngagementCapacityOutcome.ALLOWED
}

@Service
class EngagementCapacityAdmissionService(
    private val lockRepository: ActiveEngagementLockRepository,
    private val capacityPolicy: EngagementCapacityPolicy,
    private val reliabilityScoreProvider: ReliabilityScoreProvider,
    private val metrics: EngagementCapacityMetrics
) {

    fun evaluateUser(
        userId: UUID,
        now: OffsetDateTime,
        phase: EngagementCapacityEvaluationPhase
    ): EngagementCapacityAdmissionDecision =
        evaluateUsers(
            userIds = listOf(userId),
            now = now,
            phase = phase
        ).getValue(userId)

    fun evaluateUsers(
        userIds: Collection<UUID>,
        now: OffsetDateTime,
        phase: EngagementCapacityEvaluationPhase
    ): Map<UUID, EngagementCapacityAdmissionDecision> {
        val capacities = capacityPolicy.capacitiesFor(
            userIds = userIds,
            now = now
        )

        return capacities.mapValues { (userId, capacity) ->
            val activeMatches = lockRepository.countByUserIdAndEngagementType(
                userId = userId,
                engagementType = EngagementType.MATCH
            )
            val activeConnections = lockRepository.countByUserIdAndEngagementType(
                userId = userId,
                engagementType = EngagementType.CONNECTION
            )
            val outcome =
                when {
                    activeMatches >= capacity.matchCap -> EngagementCapacityOutcome.BLOCKED_MATCH_CAP
                    activeConnections >= capacity.connectionCap -> EngagementCapacityOutcome.BLOCKED_CONNECTION_CAP
                    else -> EngagementCapacityOutcome.ALLOWED
                }
            val decision =
                EngagementCapacityAdmissionDecision(
                    userId = userId,
                    capacity = capacity,
                    activeMatches = activeMatches,
                    activeConnections = activeConnections,
                    reliabilityBaseScore = reliabilityScoreProvider.baseScore,
                    outcome = outcome
                )
            metrics.recordDecision(
                phase = phase,
                decision = decision
            )
            decision
        }
    }

    fun requireUsersCanReceiveNewMatch(
        userIds: Collection<UUID>,
        now: OffsetDateTime,
        phase: EngagementCapacityEvaluationPhase
    ) {
        val decisions = evaluateUsers(
            userIds = userIds,
            now = now,
            phase = phase
        )

        userIds.distinct()
            .mapNotNull { decisions[it] }
            .firstOrNull { !it.allowed }
            ?.let { decision ->
                throw DomainConflictException(
                    code = decision.domainCode(),
                    message = decision.neutralMessage()
                )
            }
    }

    private fun EngagementCapacityAdmissionDecision.domainCode(): DomainErrorCode =
        when (outcome) {
            EngagementCapacityOutcome.BLOCKED_MATCH_CAP -> DomainErrorCode.ACTIVE_MATCH_LIMIT_REACHED
            EngagementCapacityOutcome.BLOCKED_CONNECTION_CAP -> DomainErrorCode.ACTIVE_CONNECTION_LIMIT_REACHED
            EngagementCapacityOutcome.ALLOWED -> error("Allowed capacity decision has no domain conflict")
        }

    private fun EngagementCapacityAdmissionDecision.neutralMessage(): String =
        when (outcome) {
            EngagementCapacityOutcome.BLOCKED_MATCH_CAP -> "User has reached the active match capacity"
            EngagementCapacityOutcome.BLOCKED_CONNECTION_CAP -> "User has reached the active connection capacity"
            EngagementCapacityOutcome.ALLOWED -> error("Allowed capacity decision has no blocker message")
        }
}
