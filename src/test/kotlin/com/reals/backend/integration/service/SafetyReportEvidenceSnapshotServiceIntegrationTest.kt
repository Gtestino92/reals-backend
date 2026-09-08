package com.reals.backend.integration.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.ChatMessageType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.reports.SafetyReportEvidenceSnapshotService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID

class SafetyReportEvidenceSnapshotServiceIntegrationTest : BaseIT() {

    @Test
    fun `creates deterministic evidence snapshot for chat report`() {
        val setup = createMatchWithFirstChat()
        chatService.sendMessage(
            chatId = setup.firstChatId,
            senderId = setup.userAId,
            content = "First message content used only for hashing"
        )
        chatService.sendMessage(
            chatId = setup.firstChatId,
            senderId = setup.userBId,
            content = "Second message content used only for hashing"
        )
        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                chatId = setup.firstChatId,
                matchId = setup.matchId,
                contextType = SafetyReportContextType.CHAT,
                contextId = setup.firstChatId,
                reason = SafetyReportReason.HARASSMENT,
                details = "Details are stored on report only"
            )
        )

        val first = safetyReportEvidenceSnapshotService.captureForReport(report)
        val second = safetyReportEvidenceSnapshotService.captureForReport(report)

        assertEquals(first.id, second.id)
        assertEquals(report.id, first.safetyReportId)
        assertEquals(setup.firstChatId, first.chatId)
        assertEquals(setup.matchId, first.matchId)
        assertEquals(2, first.messageCount)
        assertNotNull(first.firstMessageAt)
        assertNotNull(first.lastMessageAt)
        assertEquals(64, first.transcriptSha256!!.length)
        assertTrue(first.transcriptSha256!!.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(!first.transcriptSha256!!.contains("First message content"))
        assertEquals(1, safetyReportEvidenceSnapshotRepository.count())
    }

    @Test
    fun `creates empty evidence snapshot for non chat report`() {
        val setup = createMatchInVisualPhase()
        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = setup.userAId,
                reportedUserId = setup.userBId,
                chatId = null,
                matchId = setup.matchId,
                contextType = SafetyReportContextType.VISUAL_PROFILE,
                contextId = setup.matchId,
                reason = SafetyReportReason.INAPPROPRIATE_BEHAVIOR,
                details = "Visual profile report details"
            )
        )

        val snapshot = safetyReportEvidenceSnapshotService.captureForReport(report)

        assertEquals(report.id, snapshot.safetyReportId)
        assertNull(snapshot.chatId)
        assertEquals(setup.matchId, snapshot.matchId)
        assertEquals(0, snapshot.messageCount)
        assertNull(snapshot.firstMessageAt)
        assertNull(snapshot.lastMessageAt)
        assertNull(snapshot.transcriptSha256)
    }

    @Test
    fun `text-only transcript hash preserves historical representation`() {
        val messages = listOf(
            textMessage(
                id = UUID.fromString("00000000-0000-0000-0000-000000000101"),
                senderId = UUID.fromString("00000000-0000-0000-0000-000000000201"),
                sentAt = OffsetDateTime.parse("2026-01-01T10:00:00Z"),
                content = "hello"
            ),
            textMessage(
                id = UUID.fromString("00000000-0000-0000-0000-000000000102"),
                senderId = UUID.fromString("00000000-0000-0000-0000-000000000202"),
                sentAt = OffsetDateTime.parse("2026-01-01T10:01:00Z"),
                content = "world"
            )
        )
        val historicalInput = messages.joinToString("\n") {
            "${it.id}|${it.senderId}|${it.sentAt}|${it.content}"
        }

        assertEquals(sha256(historicalInput), transcriptHash(messages))
    }

    @Test
    fun `audio transcript hash uses immutable audio metadata and ignores storage urls`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000301")
        val senderId = UUID.fromString("00000000-0000-0000-0000-000000000302")
        val sentAt = OffsetDateTime.parse("2026-01-01T11:00:00Z")
        val first = audioMessage(
            id = id,
            senderId = senderId,
            sentAt = sentAt,
            audioSha256 = "a".repeat(64),
            audioBucket = "bucket-a",
            audioObjectKey = "key-a"
        )
        val sameAudioDifferentStorage = audioMessage(
            id = id,
            senderId = senderId,
            sentAt = sentAt,
            audioSha256 = "a".repeat(64),
            audioBucket = "bucket-b",
            audioObjectKey = "key-b"
        )
        val differentAudio = audioMessage(
            id = id,
            senderId = senderId,
            sentAt = sentAt,
            audioSha256 = "b".repeat(64),
            audioBucket = "bucket-a",
            audioObjectKey = "key-a"
        )

        assertEquals(transcriptHash(listOf(first)), transcriptHash(listOf(sameAudioDifferentStorage)))
        assertEquals(
            sha256("AUDIO|$id|$senderId|$sentAt|1200|${"a".repeat(64)}"),
            transcriptHash(listOf(first))
        )
        assertTrue(transcriptHash(listOf(first)) != transcriptHash(listOf(differentAudio)))
    }

    private fun transcriptHash(messages: List<ChatMessage>): String {
        val method = SafetyReportEvidenceSnapshotService::class.java
            .getDeclaredMethod("transcriptHash", List::class.java)
        method.isAccessible = true
        return method.invoke(safetyReportEvidenceSnapshotService, messages) as String
    }

    private fun textMessage(
        id: UUID,
        senderId: UUID,
        sentAt: OffsetDateTime,
        content: String
    ): ChatMessage =
        ChatMessage(
            id = id,
            chatSessionId = UUID.fromString("00000000-0000-0000-0000-000000000401"),
            senderId = senderId,
            messageType = ChatMessageType.TEXT,
            content = content,
            sentAt = sentAt
        )

    private fun audioMessage(
        id: UUID,
        senderId: UUID,
        sentAt: OffsetDateTime,
        audioSha256: String,
        audioBucket: String,
        audioObjectKey: String
    ): ChatMessage =
        ChatMessage(
            id = id,
            chatSessionId = UUID.fromString("00000000-0000-0000-0000-000000000402"),
            senderId = senderId,
            messageType = ChatMessageType.AUDIO,
            clientMessageId = UUID.fromString("00000000-0000-0000-0000-000000000403"),
            content = null,
            audioBucket = audioBucket,
            audioObjectKey = audioObjectKey,
            audioContentType = "audio/mp4",
            audioSizeBytes = 128,
            audioDurationMillis = 1200,
            audioSha256 = audioSha256,
            sentAt = sentAt
        )

    private fun sha256(input: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
        )
}
