package com.reals.backend.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID

class ChatAudioServiceTest {

    @Test
    fun `existing replay returns before preflight upload and cleanup`() {
        val fixture = serviceFixture()
        val existing = audioMessage()
        Mockito.`when`(
            fixture.chatMessageService.findAudioMessageReplayOrThrowOnConflict(
                chatId = CHAT_ID,
                senderId = SENDER_ID,
                clientMessageId = CLIENT_MESSAGE_ID,
                audioSha256 = SHA_256
            )
        ).thenReturn(existing)

        val result = fixture.service.sendAudioMessage(
            chatId = CHAT_ID,
            senderId = SENDER_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            contentType = "audio/mp4",
            bytes = BYTES
        )

        assertSame(existing, (result as ChatAudioSendResult.Replayed).message)
        Mockito.verify(fixture.chatMessageService).findAudioMessageReplayOrThrowOnConflict(
            chatId = CHAT_ID,
            senderId = SENDER_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            audioSha256 = SHA_256
        )
        Mockito.verifyNoMoreInteractions(fixture.chatMessageService)
        Mockito.verifyNoInteractions(fixture.storageService, fixture.mediaCleanupTaskService)
    }

    @Test
    fun `feature-disabled preflight rejection does not upload or create cleanup task`() {
        assertPreflightRejectionDoesNotUpload(DomainErrorCode.CHAT_AUDIO_FEATURE_DISABLED)
    }

    @Test
    fun `guidance-locked preflight rejection does not upload or create cleanup task`() {
        assertPreflightRejectionDoesNotUpload(DomainErrorCode.CHAT_AUDIO_GUIDANCE_REQUIRED)
    }

    @Test
    fun `delay-locked preflight rejection does not upload or create cleanup task`() {
        assertPreflightRejectionDoesNotUpload(DomainErrorCode.CHAT_AUDIO_NOT_AVAILABLE_YET)
    }

    @Test
    fun `limit-reached preflight rejection does not upload or create cleanup task`() {
        assertPreflightRejectionDoesNotUpload(DomainErrorCode.CHAT_AUDIO_LIMIT_REACHED)
    }

    private fun assertPreflightRejectionDoesNotUpload(code: DomainErrorCode) {
        val fixture = serviceFixture()
        Mockito.doThrow(DomainConflictException(code = code, message = "preflight rejected"))
            .`when`(fixture.chatMessageService)
            .preflightNewAudioMessage(
                chatId = eqValue(CHAT_ID),
                senderId = eqValue(SENDER_ID),
                now = anyOffsetDateTime(),
                replyTarget = Mockito.isNull(ChatMessageService.ChatReplyTarget::class.java)
            )

        val ex = assertThrows<DomainConflictException> {
            fixture.service.sendAudioMessage(
                chatId = CHAT_ID,
                senderId = SENDER_ID,
                clientMessageId = CLIENT_MESSAGE_ID,
                contentType = "audio/mp4",
                bytes = BYTES
            )
        }

        assertSame(code, ex.code)
        Mockito.verifyNoInteractions(fixture.storageService, fixture.mediaCleanupTaskService)
    }

    private fun serviceFixture(): Fixture {
        val validationService = Mockito.mock(ChatAudioValidationService::class.java)
        val storageService = Mockito.mock(S3StorageService::class.java)
        val mediaCleanupTaskService = Mockito.mock(MediaCleanupTaskService::class.java)
        val chatMessageService = Mockito.mock(ChatMessageService::class.java)

        Mockito.`when`(
            validationService.inspect(
                eqValue("audio/mp4"),
                eqValue(BYTES)
            )
        ).thenReturn(
            ChatAudioInspection(
                contentType = "audio/mp4",
                sizeBytes = BYTES.size.toLong(),
                durationMillis = 1_000
            )
        )

        return Fixture(
            service = ChatAudioService(
                validationService = validationService,
                storageService = storageService,
                mediaCleanupTaskService = mediaCleanupTaskService,
                chatMessageService = chatMessageService
            ),
            storageService = storageService,
            mediaCleanupTaskService = mediaCleanupTaskService,
            chatMessageService = chatMessageService
        )
    }

    private fun audioMessage(): ChatMessage =
        ChatMessage(
            id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
            chatSessionId = CHAT_ID,
            senderId = SENDER_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            messageType = ChatMessageType.AUDIO,
            content = null,
            audioBucket = "bucket",
            audioObjectKey = "chats/$CHAT_ID/messages/00000000-0000-0000-0000-000000000010.m4a",
            audioContentType = "audio/mp4",
            audioSizeBytes = BYTES.size.toLong(),
            audioDurationMillis = 1_000,
            audioSha256 = SHA_256
        )

    private data class Fixture(
        val service: ChatAudioService,
        val storageService: S3StorageService,
        val mediaCleanupTaskService: MediaCleanupTaskService,
        val chatMessageService: ChatMessageService
    )

    private companion object {
        val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val SENDER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CLIENT_MESSAGE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val BYTES: ByteArray = byteArrayOf(1, 2, 3)
        const val SHA_256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    }
}

private fun <T> eqValue(value: T): T {
    Mockito.eq(value)
    return value
}

private fun anyOffsetDateTime(): OffsetDateTime {
    Mockito.any(OffsetDateTime::class.java)
    return OffsetDateTime.MIN
}
