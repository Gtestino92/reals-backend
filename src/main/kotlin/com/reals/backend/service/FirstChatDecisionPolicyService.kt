package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatContinueDecision
import com.reals.backend.domain.ChatType
import com.reals.backend.repository.ChatDecisionRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FirstChatDecisionPolicyService(
    private val chatDecisionRepository: ChatDecisionRepository,
    private val matchService: MatchService
) {

    fun isDecisionOnlyForUser(
        chat: Chat,
        userId: UUID
    ): Boolean {
        if (chat.chatType != ChatType.FIRST_CHAT) {
            return false
        }

        val decision = chatDecisionRepository.findByChatId(chat.id) ?: return false
        val match = matchService.findByIdOrThrow(chat.matchId)

        val myDecision: ChatContinueDecision?
        val partnerDecision: ChatContinueDecision?
        when (userId) {
            match.userAId -> {
                myDecision = decision.userADecision
                partnerDecision = decision.userBDecision
            }
            match.userBId -> {
                myDecision = decision.userBDecision
                partnerDecision = decision.userADecision
            }
            else -> throw AccessDeniedException("User $userId does not belong to match ${match.id}")
        }

        return myDecision == null && partnerDecision == ChatContinueDecision.APPROVED
    }

    fun requireOrdinaryFirstChatMutationAllowed(
        chat: Chat,
        userId: UUID
    ) {
        if (isDecisionOnlyForUser(chat, userId)) {
            throw DomainConflictException(
                code = DomainErrorCode.FIRST_CHAT_DECISION_ONLY,
                message = "Only a final first-chat decision is available now"
            )
        }
    }
}
