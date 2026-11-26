package com.reals.backend.integration.postgres

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.ChatMessageReactionType
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.service.S3StorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.util.HexFormat
import java.time.OffsetDateTime
import java.util.UUID

@AutoConfigureMockMvc
class BlockedAudioHistoryPostgresIT : PostgresITBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `closed chat audio and heart replays cannot issue a URL`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/audio/valid-aac-short.m4a"))
            .use { it.readBytes() }
        val clientId = UUID.randomUUID()
        val (pair, message) = requireNotNull(transactions.execute {
            val pair = createActiveSecondChat()
            val message = chatMessageRepository.saveAndFlush(ChatMessage(
                chatSessionId = pair.secondChatId, senderId = pair.userAId, messageType = ChatMessageType.AUDIO,
                clientMessageId = clientId, audioBucket = "fixture", audioObjectKey = "voice.m4a",
                audioContentType = "audio/mp4", audioDurationMillis = 1000, audioSizeBytes = bytes.size.toLong(),
                audioSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                reactionType = ChatMessageReactionType.HEART, sentAt = OffsetDateTime.now()
            ))
            pair to message
        })
        val chatId = pair.secondChatId
        Mockito.`when`(storageService.getReadUrl("fixture", "voice.m4a")).thenReturn("https://media.example.test/signed")

        fun audioReplay() = multipart("/api/chats/$chatId/audio-messages")
            .file(MockMultipartFile("file", "voice.m4a", "audio/mp4", bytes))
            .file(MockMultipartFile("clientMessageId", clientId.toString().toByteArray()))
            .with(authenticatedAs(pair.userAId))

        fun heartReplay() = put("/api/chats/$chatId/messages/${message.id}/reaction")
            .contentType("application/json").content("""{"type":"HEART"}""")
            .with(authenticatedAs(pair.userBId))

        for (readableStatus in listOf(ChatStatus.ACTIVE, ChatStatus.FINISHED)) {
            val chat = chatRepository.findById(chatId).orElseThrow()
            chat.status = readableStatus
            chatRepository.saveAndFlush(chat)
            for (request in listOf(audioReplay(), heartReplay())) {
                mockMvc.perform(request).andExpect(status().isOk)
                    .andExpect(jsonPath("$.id").value(message.id.toString()))
                    .andExpect(jsonPath("$.audio.url").value("https://media.example.test/signed"))
            }
        }
        val chat = chatRepository.findById(chatId).orElseThrow()
        chat.status = ChatStatus.CLOSED
        chatRepository.saveAndFlush(chat)
        Mockito.clearInvocations(storageService)

        for (request in listOf(audioReplay(), heartReplay())) {
            mockMvc.perform(request).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("CHAT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.audio").doesNotExist())
        }
        Mockito.verifyNoInteractions(storageService)
        assertEquals(ChatMessageReactionType.HEART, chatMessageRepository.findById(message.id).orElseThrow().reactionType)
        assertEquals(1L, chatMessageRepository.count())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `history discards audio if the chat closes while signing`(incremental: Boolean) {
        val (pair, cursor) = requireNotNull(transactions.execute {
            val pair = createActiveSecondChat()
            val time = OffsetDateTime.now()
            val cursor = chatMessageRepository.save(ChatMessage(
                chatSessionId = pair.secondChatId, senderId = pair.userAId, content = "history", sentAt = time
            ))
            chatMessageRepository.save(audioMessage(pair.secondChatId, pair.userAId, time.plusSeconds(1)))
            pair to cursor
        })
        Mockito.`when`(storageService.getReadUrl("fixture", "voice.m4a")).thenAnswer {
            jdbcTemplate.update("update chats set status = 'CLOSED' where id = ?", pair.secondChatId)
            "https://media.example.test/signed"
        }
        val history = get("/api/chats/${pair.secondChatId}/messages").with(authenticatedAs(pair.userBId))
        if (incremental) history.param("after", cursor.id.toString())
        mockMvc.perform(history).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CHAT_NOT_AVAILABLE"))
            .andExpect(jsonPath("$.audio").doesNotExist())
        Mockito.verify(storageService).getReadUrl("fixture", "voice.m4a")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `heart replay discards audio if the counterpart blocks while signing`() {
        val (pair, audio) = requireNotNull(transactions.execute {
            val pair = createMatchWithFirstChat()
            val audio = audioMessage(pair.firstChatId, pair.userAId, OffsetDateTime.now())
            audio.reactionType = ChatMessageReactionType.HEART
            pair to chatMessageRepository.save(audio)
        })
        Mockito.`when`(storageService.getReadUrl("fixture", "voice.m4a")).thenAnswer {
            userBlockCommandService.blockUserAndContain(pair.userAId, pair.userBId, UserBlockSource.MANUAL)
            "https://media.example.test/signed"
        }
        mockMvc.perform(put("/api/chats/${pair.firstChatId}/messages/${audio.id}/reaction")
            .contentType("application/json").content("""{"type":"HEART"}""")
            .with(authenticatedAs(pair.userBId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER_PAIR_BLOCKED"))
            .andExpect(jsonPath("$.audio").doesNotExist())
        Mockito.verify(storageService).getReadUrl("fixture", "voice.m4a")
    }

    private fun audioMessage(chatId: UUID, senderId: UUID, time: OffsetDateTime) = ChatMessage(
        chatSessionId = chatId, senderId = senderId, messageType = ChatMessageType.AUDIO,
        clientMessageId = UUID.randomUUID(), audioBucket = "fixture", audioObjectKey = "voice.m4a",
        audioContentType = "audio/mp4", audioDurationMillis = 1000, audioSizeBytes = 32,
        audioSha256 = "a".repeat(64), sentAt = time
    )

    private fun authenticatedAs(userId: UUID) = authentication(
        UsernamePasswordAuthenticationToken(userId.toString(), null, listOf(SimpleGrantedAuthority("ROLE_USER")))
    )
    @Test
    fun `blocked pages order timestamp ties and accept a hidden cursor`() {
        val pair = createMatchWithFirstChat()
        val time = OffsetDateTime.parse("2026-07-01T12:00:00Z")
        val chat = pair.firstChatId
        val viewer = pair.userBId
        fun audio(id: Long, sender: UUID) = ChatMessage(
            id = UUID(0, id), chatSessionId = chat, senderId = sender,
            messageType = ChatMessageType.AUDIO, clientMessageId = UUID.randomUUID(),
            audioBucket = "fixture", audioObjectKey = "voice-$id", audioContentType = "audio/mp4",
            audioDurationMillis = 1000, audioSizeBytes = 32, audioSha256 = "a".repeat(64), sentAt = time
        )
        val cursor = audio(1, pair.userAId)
        val own = audio(2, viewer)
        val text = ChatMessage(
            id = UUID(0, 4), chatSessionId = chat, senderId = pair.userAId, content = "history", sentAt = time
        )
        chatMessageRepository.saveAllAndFlush(listOf(text, audio(5, pair.userAId), own, audio(3, pair.userAId), cursor))
        userBlockCommandService.blockUserAndContain(viewer, pair.userAId, UserBlockSource.MANUAL)

        assertEquals(listOf(own.id, text.id), chatService.getMessages(chat, viewer, limit = 2).map { it.id })
        val first = chatService.getMessagesAfter(chat, viewer, cursor.id, limit = 1)
        assertEquals(listOf(own.id), first.messages.map { it.id })
        assertTrue(first.hasMore)
        val last = chatService.getMessagesAfter(chat, viewer, own.id, limit = 1)
        assertEquals(listOf(text.id), last.messages.map { it.id })
        assertFalse(last.hasMore)
    }
}
