package com.reals.backend.service

import com.reals.backend.domain.*
import com.reals.backend.repository.VisualReviewAffinityIndicatorRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.reliability.UserReliabilityScoreService
import com.reals.backend.validation.PlainText
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class VisualReviewService(

    private val visualReviewRepository: VisualReviewRepository,
    private val visualReviewAffinityIndicatorRepository: VisualReviewAffinityIndicatorRepository,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val homeStatusService: HomeStatusService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val userBlockService: UserBlockService,
    private val visualResourceAccessPolicy: VisualResourceAccessPolicy,
    private val visualReviewAvailabilityPolicy: VisualReviewAvailabilityPolicy,

    @param:Value("\${chat.visual-phase.duration-minutes:1440}")
    private val visualPhaseDurationMinutes: Long,

    @param:Value("\${notifications.visual-review-reminder.remaining-percentage:40}")
    private val visualReviewReminderRemainingPercentage: Long
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

    fun requireVisualContentAccess(
        matchId: UUID,
        userId: UUID
    ): VisualResourceAccess =
        visualResourceAccessPolicy.requireCanAccess(
            matchId = matchId,
            requestingUserId = userId
        )

    fun initializeForMatch(
        matchId: UUID,
        preResolutionPairReliabilityScore: Double? = null
    ): VisualReview {
        val match = matchService.findByIdOrThrow(matchId)
        userBlockService.requirePairNotBlocked(match.userAId, match.userBId)

        val existing = visualReviewRepository.findByMatchId(matchId)

        if (existing != null) {
            return existing
        }

        val now = OffsetDateTime.now()
        val availableAt = visualReviewAvailabilityPolicy.availableAt(
            now = now,
            pairReliabilityScore = preResolutionPairReliabilityScore
        )
        val expiresAt = availableAt.plusMinutes(visualPhaseDurationMinutes)
        val review = visualReviewRepository.save(
            VisualReview(
                matchId = matchId,
                expiresAt = expiresAt,
                availableAt = availableAt,
                reminderEligibleAt = visualReviewReminderEligibleAt(availableAt),
                createdAt = now,
                updatedAt = now
            )
        )
        homeStatusService.scheduleNextRefreshAtForBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            nextRefreshAt = availableAt
        )
        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "visual_review_initialized"
        )
        return review
    }

    private fun visualReviewReminderEligibleAt(availableAt: OffsetDateTime): OffsetDateTime {
        require(visualReviewReminderRemainingPercentage in 1..99) {
            "notifications.visual-review-reminder.remaining-percentage must be greater than 0 and less than 100"
        }

        val elapsedPercentage = 100L - visualReviewReminderRemainingPercentage
        val durationSeconds = Duration.ofMinutes(visualPhaseDurationMinutes).seconds
        val elapsedSeconds = durationSeconds * elapsedPercentage / 100L

        return availableAt.plusSeconds(elapsedSeconds)
    }

    fun expireVisualReview(matchId: UUID): Boolean {
        val match = matchService.findByIdOrThrow(matchId)
        if (match.state != MatchState.VISUAL_PHASE) {
            return false
        }

        val review = findByMatchIdOrThrow(matchId)
        val now = OffsetDateTime.now()
        val expiresAt = review.expiresAt
        if (expiresAt == null || expiresAt.isAfter(now)) {
            return false
        }

        if (review.userAVisualDecision == null) {
            userReliabilityScoreService.recordEvent(
                userId = match.userAId,
                eventType = UserReliabilityEventType.VISUAL_REVIEW_EXPIRED_NO_DECISION,
                relatedMatchId = match.id
            )
        }
        if (review.userBVisualDecision == null) {
            userReliabilityScoreService.recordEvent(
                userId = match.userBId,
                eventType = UserReliabilityEventType.VISUAL_REVIEW_EXPIRED_NO_DECISION,
                relatedMatchId = match.id
            )
        }

        return matchService.expireMatch(matchId)
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
        if (decision == VisualDecision.APPROVED) {
            userBlockService.requirePairNotBlocked(match.userAId, match.userBId)
        }
        val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")
        val now = OffsetDateTime.now()

        if (match.state == MatchState.VISUAL_PHASE) {
            requireVisualReviewAvailable(review, now)
        }

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
            requireVisualReviewNotExpired(review, now)
        }

        check(match.state == MatchState.VISUAL_PHASE) {
            "Match is not in visual phase"
        }

        review.recordDecisionFor(
            userId = userId,
            userAId = match.userAId,
            userBId = match.userBId,
            decision = decision
        )

        review.updatedAt = now
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
        val access = requireVisualContentAccess(matchId, userId)
        val match = access.match
        val review = access.review
        val normalizedMessage = normalizePersonalMessage(message)

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
        userReliabilityScoreService.recordEvent(
            userId = userId,
            eventType = UserReliabilityEventType.VISUAL_PERSONAL_MESSAGE_SUBMITTED,
            relatedMatchId = match.id
        )
    }

    fun getPartnerMessage(
        matchId: UUID,
        requestingUserId: UUID
    ): String? {
        val access = requireVisualContentAccess(matchId, requestingUserId)
        val match = access.match
        val review = access.review

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
        val access = requireVisualContentAccess(matchId, userId)
        val match = access.match
        val review = access.review

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
        val access = requireVisualContentAccess(matchId, userId)

        return personalMessageStatusFor(
            match = access.match,
            review = access.review,
            userId = userId
        )
    }

    fun getAffinityIndicators(matchId: UUID): List<VisualReviewAffinityIndicator> =
        visualReviewAffinityIndicatorRepository.findByMatchIdOrderByOrdinal(matchId)

    fun makeAvailableNowForLocalDev(matchId: UUID): VisualReview {
        val match = matchService.findByIdOrThrow(matchId)
        if (match.state != MatchState.VISUAL_PHASE) {
            throw DomainConflictException(
                code = DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE,
                message = "Match is not in visual phase"
            )
        }

        val review = visualReviewRepository.findByMatchIdForUpdate(matchId)
            ?: throw NoSuchElementException("VisualReview not found for match: $matchId")
        val now = OffsetDateTime.now()
        requireVisualReviewNotExpired(review, now)

        if (!now.isBefore(review.availableAt)) {
            return review
        }

        review.availableAt = now
        review.expiresAt = now.plusMinutes(visualPhaseDurationMinutes)
        review.reminderEligibleAt = visualReviewReminderEligibleAt(now)
        review.updatedAt = now
        val saved = visualReviewRepository.save(review)

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "local_dev_visual_review_available_now"
        )

        return saved
    }

    fun makeAvailableNowForTest(matchId: UUID): VisualReview =
        makeAvailableNowForLocalDev(matchId)

    private fun requireVisualReviewAvailable(
        review: VisualReview,
        now: OffsetDateTime
    ) {
        if (now.isBefore(review.availableAt)) {
            throw DomainConflictException(
                code = DomainErrorCode.VISUAL_CONTENT_NOT_AVAILABLE,
                message = "Visual review is not available"
            )
        }
    }

    private fun requireVisualReviewNotExpired(
        review: VisualReview,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        review.expiresAt?.let { expiresAt ->
            if (!now.isBefore(expiresAt)) {
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
            // Retained for backward-compatible response shape; no longer blocks visual decisions.
            decisionRequiresPartnerPersonalMessageRead = false
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
