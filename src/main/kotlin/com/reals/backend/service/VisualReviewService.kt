package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.MatchRepository
import com.reals.backend.repository.VisualReviewRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class VisualReviewService(

    private val visualReviewRepository: VisualReviewRepository,
    private val matchRepository: MatchRepository,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val schedulingService: SchedulingService,

    @param:Value("\${chat.visual-phase.duration-minutes:1440}")
    private val visualPhaseDurationMinutes: Long
) {

    fun findByMatchIdOrThrow(matchId: UUID): VisualReview =
        visualReviewRepository.findByMatchId(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")

    fun initializeForMatch(matchId: UUID): VisualReview {

        val existing = visualReviewRepository.findByMatchId(matchId)

        if (existing != null) {
            return existing
        }

        return visualReviewRepository.save(
            VisualReview(
                matchId = matchId,
                expiresAt = OffsetDateTime.now()
                    .plusMinutes(visualPhaseDurationMinutes)
            )
        )
    }

    fun recordDecision(
        matchId: UUID,
        userId: UUID,
        decision: VisualDecision
    ) {

        val match = matchService.findByIdOrThrow(matchId)
        val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")

        review.expiresAt?.let {
            check(OffsetDateTime.now().isBefore(it)) {
                "Visual phase expired"
            }
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Match is not in visual phase"
        }

        if (decision == VisualDecision.APPROVED) {
            requirePartnerMessageReadIfPresent(
                match = match,
                review = review,
                userId = userId
            )
        }

        when (userId) {
            match.userAId -> review.userAVisualDecision = decision
            match.userBId -> review.userBVisualDecision = decision
            else -> throw IllegalArgumentException(
                "User $userId does not belong to match $matchId"
            )
        }

        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.save(review)

        val aDecision = review.userAVisualDecision
        val bDecision = review.userBVisualDecision

        if (aDecision == null || bDecision == null) {
            return
        }

        if (
            aDecision == VisualDecision.APPROVED &&
            bDecision == VisualDecision.APPROVED
        ) {
            review.messagesVisible = true
            visualReviewRepository.save(review)

            val approvedMatch = matchService.approveVisualPhase(matchId)
            val connection = connectionService.createFromMatch(approvedMatch)

            if (schedulingService.findNegotiationOrNull(connection.id) == null) {
                schedulingService.initializeNegotiation(connection.id)
            }
        } else {
            matchService.rejectVisualPhase(matchId)
        }
    }

    fun recordPersonalMessage(
        matchId: UUID,
        userId: UUID,
        message: String
    ) {

        val match = matchService.findByIdOrThrow(matchId)
        val review = findByMatchIdOrThrow(matchId)

        check(match.state == MatchState.VISUAL_PHASE || match.state == MatchState.VISUAL_APPROVED) {
            "Personal messages are only available during visual review or scheduling"
        }

        when (userId) {

            match.userAId -> {
                check(review.personalMessageA == null) {
                    "User A already submitted a personal message"
                }
                review.personalMessageA = message
            }

            match.userBId -> {
                check(review.personalMessageB == null) {
                    "User B already submitted a personal message"
                }
                review.personalMessageB = message
            }

            else ->
                throw IllegalArgumentException(
                    "User $userId does not belong to match $matchId"
                )
        }

        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.save(review)
    }

    fun getPartnerMessage(
        matchId: UUID,
        requestingUserId: UUID
    ): String? {
        val match = matchService.findByIdOrThrow(matchId)
        val review = findByMatchIdOrThrow(matchId)

        check(match.state == MatchState.VISUAL_PHASE || match.state == MatchState.VISUAL_APPROVED) {
            "Partner message is only available during visual review or scheduling"
        }

        val message = when (requestingUserId) {
            match.userAId -> {
                if (review.personalMessageB != null && review.personalMessageBReadByAAt == null) {
                    review.personalMessageBReadByAAt = OffsetDateTime.now()
                }
                review.personalMessageB
            }

            match.userBId -> {
                if (review.personalMessageA != null && review.personalMessageAReadByBAt == null) {
                    review.personalMessageAReadByBAt = OffsetDateTime.now()
                }
                review.personalMessageA
            }

            else -> throw IllegalArgumentException(
                "User $requestingUserId does not belong to match $matchId"
            )
        }

        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.save(review)

        return message
    }

    private fun requirePartnerMessageReadIfPresent(
        match: Match,
        review: VisualReview,
        userId: UUID
    ) {
        when (userId) {
            match.userAId ->
                check(review.personalMessageB == null || review.personalMessageBReadByAAt != null) {
                    "Cannot approve visual review before reading partner personal message"
                }

            match.userBId ->
                check(review.personalMessageA == null || review.personalMessageAReadByBAt != null) {
                    "Cannot approve visual review before reading partner personal message"
                }

            else -> throw IllegalArgumentException(
                "User $userId does not belong to match ${match.id}"
            )
        }
    }
}
