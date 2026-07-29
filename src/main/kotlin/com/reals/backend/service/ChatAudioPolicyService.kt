package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import com.reals.backend.config.FirstChatAudioProperties
import com.reals.backend.config.SecondChatAudioProperties
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.FirstChatGuidanceRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.max

enum class ChatAudioUnavailableReason {
    FEATURE_DISABLED,
    CHAT_NOT_WRITABLE,
    GUIDANCE_NOT_AVAILABLE,
    GUIDANCE_REQUIRED,
    LIMIT_REACHED,
    WAITING_FOR_BOTH,
    WAITING_DELAY
}

data class ChatAudioPolicy(
    val enabled: Boolean,
    val unavailableReason: ChatAudioUnavailableReason?,
    val enabledAt: OffsetDateTime?,
    val maxDurationMillis: Long,
    val maxFileSizeBytes: Long,
    val remainingMessages: Int?
)

@Service
class ChatAudioPolicyService(
    private val audioProperties: ChatAudioProperties,
    private val firstChatAudioProperties: FirstChatAudioProperties,
    private val secondChatAudioProperties: SecondChatAudioProperties,
    private val guidanceRepository: FirstChatGuidanceRepository,
    private val chatMessageRepository: ChatMessageRepository,
    @param:Value("\${chat.first-chat.inactivity-threshold-minutes:5}")
    private val firstChatInactivityThresholdMinutes: Long
) {
    fun policyFor(
        chat: Chat,
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ChatAudioPolicy =
        when (chat.chatType) {
            ChatType.FIRST_CHAT -> firstChatPolicy(chat, userId, now)
            ChatType.SECOND_CHAT -> secondChatPolicy(chat, now)
        }

    fun requireAudioEnabled(
        chat: Chat,
        userId: UUID,
        now: OffsetDateTime
    ) {
        val policy = policyFor(chat, userId, now)
        if (policy.enabled) {
            return
        }
        throw when (policy.unavailableReason) {
            ChatAudioUnavailableReason.FEATURE_DISABLED ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_FEATURE_DISABLED,
                    message = "Chat audio messages are disabled"
                )
            ChatAudioUnavailableReason.GUIDANCE_NOT_AVAILABLE ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_GUIDANCE_NOT_AVAILABLE,
                    message = "First-chat guidance is not available for this chat"
                )
            ChatAudioUnavailableReason.GUIDANCE_REQUIRED ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_GUIDANCE_REQUIRED,
                    message = "First-chat guidance progress is required before audio is available"
                )
            ChatAudioUnavailableReason.LIMIT_REACHED ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_LIMIT_REACHED,
                    message = "First-chat audio message limit is reached"
                )
            ChatAudioUnavailableReason.WAITING_FOR_BOTH ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_WAITING_FOR_BOTH,
                    message = "Second-chat audio requires both participants to join"
                )
            ChatAudioUnavailableReason.WAITING_DELAY ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_AUDIO_NOT_AVAILABLE_YET,
                    message = "Second-chat audio is available at ${policy.enabledAt}"
                )
            ChatAudioUnavailableReason.CHAT_NOT_WRITABLE,
            null ->
                com.reals.backend.service.exception.DomainConflictException(
                    code = com.reals.backend.service.exception.DomainErrorCode.CHAT_NOT_AVAILABLE,
                    message = "Chat is not writable"
                )
        }
    }

    private fun firstChatPolicy(
        chat: Chat,
        userId: UUID,
        now: OffsetDateTime
    ): ChatAudioPolicy {
        val remaining = remainingFirstChatAudioMessages(chat.id, userId)
        if (!audioProperties.enabled) {
            return unavailable(ChatAudioUnavailableReason.FEATURE_DISABLED, remainingMessages = remaining)
        }
        val inactivityExpiresAt = (chat.lastMessageAt ?: chat.startedAt)
            .plusMinutes(firstChatInactivityThresholdMinutes)
        if (
            chat.status != ChatStatus.ACTIVE ||
            !now.isBefore(chat.timeoutAt) ||
            !now.isBefore(inactivityExpiresAt)
        ) {
            return unavailable(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, remainingMessages = remaining)
        }

        val guidance = guidanceRepository.findByChatId(chat.id)
            ?: return unavailable(ChatAudioUnavailableReason.GUIDANCE_NOT_AVAILABLE, remainingMessages = remaining)
        val answeredGuidanceQuestions = max(guidance.currentQuestionOrdinal - 1, 0)
        if (answeredGuidanceQuestions < firstChatAudioProperties.requiredAnsweredGuidanceQuestions) {
            return unavailable(ChatAudioUnavailableReason.GUIDANCE_REQUIRED, remainingMessages = remaining)
        }
        if (remaining <= 0) {
            return unavailable(ChatAudioUnavailableReason.LIMIT_REACHED, remainingMessages = 0)
        }
        return available(remainingMessages = remaining)
    }

    private fun secondChatPolicy(
        chat: Chat,
        now: OffsetDateTime
    ): ChatAudioPolicy {
        if (!audioProperties.enabled) {
            return unavailable(ChatAudioUnavailableReason.FEATURE_DISABLED, remainingMessages = null)
        }
        if (chat.status != ChatStatus.ACTIVE || !now.isBefore(chat.timeoutAt)) {
            return unavailable(ChatAudioUnavailableReason.CHAT_NOT_WRITABLE, remainingMessages = null)
        }
        val conversationStartedAt = chat.conversationStartedAt
            ?: return unavailable(ChatAudioUnavailableReason.WAITING_FOR_BOTH, remainingMessages = null)
        val enabledAt = conversationStartedAt.plusMinutes(secondChatAudioProperties.enabledAfterConversationMinutes)
        if (now.isBefore(enabledAt)) {
            return unavailable(
                reason = ChatAudioUnavailableReason.WAITING_DELAY,
                enabledAt = enabledAt,
                remainingMessages = null
            )
        }
        return available(remainingMessages = null)
    }

    private fun remainingFirstChatAudioMessages(
        chatId: UUID,
        userId: UUID
    ): Int {
        val used = chatMessageRepository.countByChatSessionIdAndSenderIdAndMessageType(
            chatSessionId = chatId,
            senderId = userId,
            messageType = ChatMessageType.AUDIO
        )
        return (firstChatAudioProperties.maxPerUser - used).coerceAtLeast(0).toInt()
    }

    private fun available(remainingMessages: Int?): ChatAudioPolicy =
        ChatAudioPolicy(
            enabled = true,
            unavailableReason = null,
            enabledAt = null,
            maxDurationMillis = audioProperties.maxDurationMillis,
            maxFileSizeBytes = audioProperties.maxFileSizeBytes,
            remainingMessages = remainingMessages
        )

    private fun unavailable(
        reason: ChatAudioUnavailableReason,
        enabledAt: OffsetDateTime? = null,
        remainingMessages: Int?
    ): ChatAudioPolicy =
        ChatAudioPolicy(
            enabled = false,
            unavailableReason = reason,
            enabledAt = enabledAt,
            maxDurationMillis = audioProperties.maxDurationMillis,
            maxFileSizeBytes = audioProperties.maxFileSizeBytes,
            remainingMessages = remainingMessages
        )
}
