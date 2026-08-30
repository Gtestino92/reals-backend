package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatDecision
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatExitRequestStatus
import com.reals.backend.domain.ChatExitRequestType
import com.reals.backend.domain.ChatParticipantDecisionStatus
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class FirstChatResolutionService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchService: MatchService,
    private val visualReviewService: VisualReviewService,
    private val chatExitService: ChatExitService,
    private val firstChatDecisionPolicyService: FirstChatDecisionPolicyService,
    private val chatAccessService: ChatAccessService,
    private val chatLifecycleService: ChatLifecycleService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val userReliabilityScoreService: UserReliabilityScoreService,
    private val userBlockService: UserBlockService,

    @param:Value("\${chat.first-chat.approval.min-elapsed-minutes:1}")
    private val firstChatApprovalMinElapsedMinutes: Long,

    @param:Value("\${chat.first-chat.approval.min-messages-per-user:3}")
    private val firstChatApprovalMinMessagesPerUser: Int
) {

    data class ParticipantDecisionStatuses(
        val myDecision: ChatParticipantDecisionStatus,
        val partnerDecision: ChatParticipantDecisionStatus
    )

    fun recordChatDecision(
        matchId: UUID,
        userId: UUID,
        decision: ChatContinueDecision
    ) {
        val match = matchService.findByIdOrThrow(matchId)
        if (decision == ChatContinueDecision.APPROVED) {
            userBlockService.requirePairNotBlocked(match.userAId, match.userBId)
        }

        if (match.state != MatchState.CHAT_ACTIVE) {
            throw chatDecisionNotAvailable()
        }

        val chat = chatLifecycleService.findActiveFirstChatForUpdateOrThrow(matchId)

        val existingChatDecision = chatDecisionRepository.findByChatId(chat.id)

        chatLifecycleService.requireNoPendingMutualCancellation(chat.id)

        when (userId) {
            match.userAId -> {
                if (existingChatDecision?.userADecision != null) {
                    throw chatDecisionAlreadySubmitted()
                }
            }

            match.userBId -> {
                if (existingChatDecision?.userBDecision != null) {
                    throw chatDecisionAlreadySubmitted()
                }
            }

            else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
        }

        val mismatchRejection =
            decision == ChatContinueDecision.REJECTED &&
                firstChatDecisionPolicyService.isDecisionOnlyForUser(chat, userId)

        if (decision == ChatContinueDecision.REJECTED && !mismatchRejection) {
            chatExitService.cancelChatUnilaterallyWithLockedChat(
                chat = chat,
                userId = userId,
                reason = ChatExitReason.NO_LONGER_INTERESTED
            )
            return
        }

        val chatDecision =
            existingChatDecision
                ?: chatDecisionRepository.save(
                    ChatDecision(
                        chatId = chat.id,
                        matchId = match.id
                    )
                )

        if (decision == ChatContinueDecision.APPROVED) {
            val partnerUserId =
                when (userId) {
                    match.userAId -> match.userBId
                    match.userBId -> match.userAId
                    else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
                }
            requireFirstChatApprovalEligible(
                chat = chat,
                approvingUserId = userId,
                partnerUserId = partnerUserId
            )
        }

        when (userId) {
            match.userAId -> {
                chatDecision.userADecision = decision
            }

            match.userBId -> {
                chatDecision.userBDecision = decision
            }

            else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
        }

        chatDecision.updatedAt = OffsetDateTime.now()
        chatDecisionRepository.save(chatDecision)

        val aDecision = chatDecision.userADecision
        val bDecision = chatDecision.userBDecision

        if (aDecision != null && bDecision != null) {
            if (aDecision == ChatContinueDecision.APPROVED && bDecision == ChatContinueDecision.APPROVED) {
                val preResolutionPairReliabilityScore =
                    preResolutionPairReliabilityScore(
                        userAId = match.userAId,
                        userBId = match.userBId
                    )
                chat.status = ChatStatus.FINISHED
                chat.endedAt = OffsetDateTime.now()
                chat.endedReason = ChatEndReason.SYSTEM_CLOSED
                chatRepository.save(chat)
                chatLifecycleService.recordChatEnded(chat)
                chatLifecycleService.publishFirstChatTerminated(chat)

                listOf(match.userAId, match.userBId).forEach { participantId ->
                    userReliabilityScoreService.recordEvent(
                        userId = participantId,
                        eventType = UserReliabilityEventType.FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION,
                        relatedMatchId = match.id,
                        relatedChatId = chat.id
                    )
                }

                matchService.transitionToVisualPhase(matchId)
                visualReviewService.initializeForMatch(
                    matchId = matchId,
                    preResolutionPairReliabilityScore = preResolutionPairReliabilityScore
                )
            } else {
                finishFirstChatDecisionMismatch(chat)
            }
        }

        homeStateInvalidationService.bumpBoth(
            userAId = match.userAId,
            userBId = match.userBId,
            reason = "first_chat_decision_recorded"
        )
    }

    fun getFirstChatDecisionStatuses(
        matchId: UUID,
        userId: UUID
    ): ParticipantDecisionStatuses {
        val match = matchService.findByIdOrThrow(matchId)
        val chat = chatLifecycleService.findActiveFirstChatOrThrow(matchId)
        chatAccessService.validateChatParticipant(chat, userId)

        val chatDecision = chatDecisionRepository.findByChatId(chat.id)

        val userADecision = resolveParticipantDecisionStatus(
            chat = chat,
            userId = match.userAId,
            chatDecisionValue = chatDecision?.userADecision
        )
        val userBDecision = resolveParticipantDecisionStatus(
            chat = chat,
            userId = match.userBId,
            chatDecisionValue = chatDecision?.userBDecision
        )

        return when (userId) {
            match.userAId -> ParticipantDecisionStatuses(
                myDecision = userADecision,
                partnerDecision = userBDecision
            )

            match.userBId -> ParticipantDecisionStatuses(
                myDecision = userBDecision,
                partnerDecision = userADecision
            )

            else -> throw AccessDeniedException("User $userId does not belong to match $matchId")
        }
    }

    private fun requireFirstChatApprovalEligible(
        chat: Chat,
        approvingUserId: UUID,
        partnerUserId: UUID
    ) {
        val now = OffsetDateTime.now()
        val elapsedThresholdMet =
            firstChatApprovalMinElapsedMinutes <= 0 ||
                !chat.startedAt.plusMinutes(firstChatApprovalMinElapsedMinutes).isAfter(now)

        if (!elapsedThresholdMet) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_APPROVAL_TOO_EARLY,
                message = "More conversation time is required before continuing"
            )
        }

        if (firstChatApprovalMinMessagesPerUser <= 0) {
            return
        }

        val approvingMessages =
            chatMessageRepository.countByChatSessionIdAndSenderId(
                chatSessionId = chat.id,
                senderId = approvingUserId
            )
        val partnerMessages =
            chatMessageRepository.countByChatSessionIdAndSenderId(
                chatSessionId = chat.id,
                senderId = partnerUserId
            )

        if (
            approvingMessages < firstChatApprovalMinMessagesPerUser.toLong() ||
            partnerMessages < firstChatApprovalMinMessagesPerUser.toLong()
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_APPROVAL_PARTICIPATION_REQUIRED,
                message = "Both participants need to take part in the conversation before continuing"
            )
        }
    }

    private fun finishFirstChatDecisionMismatch(chat: Chat) {
        val match = matchService.findByIdOrThrow(chat.matchId)
        chat.status = ChatStatus.FINISHED
        chat.endedAt = OffsetDateTime.now()
        chat.endedReason = ChatEndReason.FIRST_CHAT_DECISION_MISMATCH
        chatRepository.save(chat)
        chatLifecycleService.recordChatEnded(chat)
        chatLifecycleService.publishFirstChatTerminated(chat)

        matchService.rejectChatPhase(match.id)
    }

    private fun preResolutionPairReliabilityScore(
        userAId: UUID,
        userBId: UUID
    ): Double? {
        if (!userReliabilityScoreService.enabled) {
            return null
        }

        val scores = userReliabilityScoreService.effectiveScores(
            userIds = listOf(userAId, userBId)
        )

        return ((scores[userAId] ?: return null) + (scores[userBId] ?: return null)) / 2.0
    }

    private fun chatDecisionNotAvailable(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_DECISION_NOT_AVAILABLE,
            message = "Chat decision is not available"
        )

    private fun chatDecisionAlreadySubmitted(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.CHAT_DECISION_ALREADY_SUBMITTED,
            message = "Chat decision was already submitted"
        )

    private fun resolveParticipantDecisionStatus(
        chat: Chat,
        userId: UUID,
        chatDecisionValue: ChatContinueDecision?
    ): ChatParticipantDecisionStatus {
        if (chat.status == ChatStatus.ABANDONED) {
            return ChatParticipantDecisionStatus.ABANDONED
        }

        val terminalExit =
            chatExitService.findExitRequests(
                chatId = chat.id,
                userId = userId
            ).firstOrNull {
                it.status == ChatExitRequestStatus.ACCEPTED &&
                    (it.type == ChatExitRequestType.UNILATERAL_CANCEL ||
                        it.type == ChatExitRequestType.SAFETY_REPORT) &&
                    it.requesterUserId == userId
            }

        if (terminalExit != null) {
            return ChatParticipantDecisionStatus.REJECTED
        }

        return when (chatDecisionValue) {
            ChatContinueDecision.APPROVED -> ChatParticipantDecisionStatus.APPROVED
            ChatContinueDecision.REJECTED -> ChatParticipantDecisionStatus.REJECTED
            null -> ChatParticipantDecisionStatus.PENDING
        }
    }
}
