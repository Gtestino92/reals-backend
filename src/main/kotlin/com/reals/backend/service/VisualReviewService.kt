package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.validation.PlainText
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class VisualReviewService(

    private val visualReviewRepository: VisualReviewRepository,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val homeStateInvalidationService: HomeStateInvalidationService,

    @param:Value("\${chat.visual-phase.duration-minutes:1440}")
    private val visualPhaseDurationMinutes: Long
) {

    private companion object {
        const val PERSONAL_MESSAGE_MAX_LENGTH = 280
    }

    fun findByMatchIdOrThrow(matchId: UUID): VisualReview =
        visualReviewRepository.findByMatchId(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")

    fun findByMatchIdOrNull(matchId: UUID): VisualReview? =
        visualReviewRepository.findByMatchId(matchId)

    fun visualExpiresAt(matchId: UUID): OffsetDateTime? =
        findByMatchIdOrNull(matchId)?.expiresAt

    fun initializeForMatch(matchId: UUID): VisualReview {
        val match = matchService.findByIdOrThrow(matchId)

        val existing = visualReviewRepository.findByMatchId(matchId)

        if (existing != null) {
            return existing
        }

        val review = visualReviewRepository.save(
            VisualReview(
                matchId = matchId,
                expiresAt = OffsetDateTime.now()
                    .plusMinutes(visualPhaseDurationMinutes)
            )
        )
        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "visual_review_available"
        )
        return review
    }

    fun recordDecision(
        matchId: UUID,
        userId: UUID,
        decision: VisualDecision
    ) {

        val match = matchService.findByIdForUserOrThrow(
            matchId = matchId,
            userId = userId
        )
        val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")

        val existingDecision =
            review.decisionFor(
                userId = userId,
                userAId = match.userAId,
                userBId = match.userBId
            )

        if (existingDecision != null) {
            check(existingDecision == decision) {
                "Cannot change visual decision once it has been recorded"
            }

            matchService.releaseMatchLockForUser(
                matchId = matchId,
                userId = userId
            )
            resolveVisualPhaseIfReady(match = match, review = review)
            homeStateInvalidationService.bumpBoth(
                userAId = match.userAId,
                userBId = match.userBId,
                reason = "visual_review_decision_replayed"
            )
            return
        }

        if (match.state == MatchState.VISUAL_PHASE) {
            requireVisualReviewNotExpired(review)
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Match is not in visual phase"
        }

        requirePartnerMessageReadBeforeDecisionIfPresent(
            match = match,
            review = review,
            userId = userId
        )

        review.recordDecisionFor(
            userId = userId,
            userAId = match.userAId,
            userBId = match.userBId,
            decision = decision
        )

        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.save(review)

        matchService.releaseMatchLockForUser(
            matchId = matchId,
            userId = userId
        )
        resolveVisualPhaseIfReady(match = match, review = review)
        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "visual_review_decision_recorded"
        )
    }

    fun recordPersonalMessage(
        matchId: UUID,
        userId: UUID,
        message: String
    ) {
        val normalizedMessage = normalizePersonalMessage(message)

        val match = matchService.findByIdOrThrow(matchId)
        val review = findByMatchIdOrThrow(matchId)

        check(match.state == MatchState.VISUAL_PHASE || match.state == MatchState.VISUAL_APPROVED) {
            "Personal messages are only available during visual review or scheduling"
        }

        if (match.state == MatchState.VISUAL_PHASE) {
            requireVisualReviewNotExpired(review)
        }

        when (userId) {

            match.userAId -> {
                check(review.personalMessageA == null) {
                    "User A already submitted a personal message"
                }
                review.personalMessageA = normalizedMessage
            }

            match.userBId -> {
                check(review.personalMessageB == null) {
                    "User B already submitted a personal message"
                }
                review.personalMessageB = normalizedMessage
            }

            else ->
                throw AccessDeniedException(
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

            else -> throw AccessDeniedException(
                "User $requestingUserId does not belong to match $matchId"
            )
        }

        review.updatedAt = OffsetDateTime.now()
        visualReviewRepository.save(review)

        return message
    }

    fun hasPersonalMessageSubmitted(
        matchId: UUID,
        userId: UUID
    ): Boolean {
        val match = matchService.findByIdOrThrow(matchId)
        val review = findByMatchIdOrThrow(matchId)

        return when (userId) {
            match.userAId -> review.personalMessageA != null
            match.userBId -> review.personalMessageB != null
            else -> throw AccessDeniedException(
                "User $userId does not belong to match $matchId"
            )
        }
    }

    fun getPersonalMessageStatusForUser(
        matchId: UUID,
        userId: UUID
    ): VisualReviewPersonalMessageStatus {
        val match = matchService.findByIdForUserOrThrow(
            matchId = matchId,
            userId = userId
        )
        val review = findByMatchIdOrThrow(matchId)

        return personalMessageStatusFor(
            match = match,
            review = review,
            userId = userId
        )
    }

    private fun requirePartnerMessageReadBeforeDecisionIfPresent(
        match: Match,
        review: VisualReview,
        userId: UUID
    ) {
        val status = personalMessageStatusFor(
            match = match,
            review = review,
            userId = userId
        )

        if (status.decisionRequiresPartnerPersonalMessageRead) {
            throw DomainConflictException(
                code = DomainErrorCode.VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ,
                message = "Read the partner personal message before making a visual decision."
            )
        }
    }

    private fun requireVisualReviewNotExpired(review: VisualReview) {
        review.expiresAt?.let { expiresAt ->
            if (!OffsetDateTime.now().isBefore(expiresAt)) {
                throw DomainConflictException(
                    code = DomainErrorCode.VISUAL_REVIEW_EXPIRED,
                    message = "Visual review has expired"
                )
            }
        }
    }

    private fun personalMessageStatusFor(
        match: Match,
        review: VisualReview,
        userId: UUID
    ): VisualReviewPersonalMessageStatus {
        val partnerMessageReadAt =
            when (userId) {
                match.userAId -> review.personalMessageBReadByAAt
                match.userBId -> review.personalMessageAReadByBAt
                else -> throw AccessDeniedException(
                    "User $userId does not belong to match ${match.id}"
                )
            }
        val partnerMessageSubmitted =
            when (userId) {
                match.userAId -> review.personalMessageB != null
                match.userBId -> review.personalMessageA != null
                else -> throw AccessDeniedException(
                    "User $userId does not belong to match ${match.id}"
                )
            }
        val partnerMessageRead = !partnerMessageSubmitted || partnerMessageReadAt != null

        return VisualReviewPersonalMessageStatus(
            partnerPersonalMessageSubmitted = partnerMessageSubmitted,
            partnerPersonalMessageRead = partnerMessageRead,
            decisionRequiresPartnerPersonalMessageRead =
                partnerMessageSubmitted && !partnerMessageRead
        )
    }

    private fun resolveVisualPhaseIfReady(
        match: Match,
        review: VisualReview
    ) {
        if (!review.bothDecided()) {
            return
        }

        if (review.bothApproved()) {
            review.messagesVisible = true
            review.updatedAt = OffsetDateTime.now()
            visualReviewRepository.save(review)

            val approvedMatch =
                when (match.state) {
                    MatchState.VISUAL_PHASE -> matchService.approveVisualPhase(match.id)
                    MatchState.VISUAL_APPROVED -> match
                    else -> return
                }

            connectionService.createFromMatch(approvedMatch)
            return
        }

        if (review.anyRejected() && match.state == MatchState.VISUAL_PHASE) {
            matchService.rejectVisualPhase(match.id)
        }
    }

    private fun normalizePersonalMessage(message: String): String {
        val normalized = message.trim()

        require(normalized.isNotBlank()) {
            "Personal message is required"
        }

        require(normalized.length <= PERSONAL_MESSAGE_MAX_LENGTH) {
            "Personal message must be at most $PERSONAL_MESSAGE_MAX_LENGTH characters"
        }

        PlainText.requireValid("Personal message", normalized)

        return normalized
    }
}
