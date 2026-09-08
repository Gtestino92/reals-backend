package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.repository.ChatRepository
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ChatAccessService(
    private val chatRepository: ChatRepository,
    private val matchService: MatchService,
    private val connectionService: ConnectionService,
    private val userBlockService: UserBlockService
) {

    fun findByIdOrThrow(chatId: UUID): Chat {
        return chatRepository.findById(chatId)
            .orElseThrow {
                chatNotFound()
            }
    }

    fun findByIdForUpdateOrThrow(chatId: UUID): Chat =
        chatRepository.findByIdForUpdate(chatId)
            ?: throw chatNotFound()

    fun findByIdForUserOrThrow(
        chatId: UUID,
        userId: UUID
    ): Chat {
        val chat = findByIdOrThrow(chatId)
        validateChatParticipant(chat, userId)
        return chat
    }

    fun validateChatParticipant(
        chat: Chat,
        userId: UUID
    ) {
        val match = matchService.findByIdOrThrow(chat.matchId)

        if (userId != match.userAId && userId != match.userBId) {
            throw AccessDeniedException("User $userId does not belong to match ${chat.matchId}")
        }
    }

    fun requireChatPairNotBlocked(chat: Chat) {
        val pair = chat.connectionId?.let { connectionService.findByIdOrThrow(it) }
        if (pair != null) {
            userBlockService.requirePairNotBlocked(pair.userAId, pair.userBId)
        } else {
            val match = matchService.findByIdOrThrow(chat.matchId)
            userBlockService.requirePairNotBlocked(match.userAId, match.userBId)
        }
    }

    fun chatNotFound(): DomainNotFoundException =
        DomainNotFoundException(
            code = DomainErrorCode.CHAT_NOT_FOUND,
            message = "Chat was not found"
        )
}
