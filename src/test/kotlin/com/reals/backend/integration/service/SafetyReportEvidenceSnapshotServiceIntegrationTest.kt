package com.reals.backend.integration.service

import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportReason
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
