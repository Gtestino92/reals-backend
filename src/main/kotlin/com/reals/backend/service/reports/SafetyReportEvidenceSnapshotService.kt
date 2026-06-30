package com.reals.backend.service.reports

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportEvidenceSnapshot
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.SafetyReportEvidenceSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat

@Service
@Transactional
class SafetyReportEvidenceSnapshotService(
    private val snapshotRepository: SafetyReportEvidenceSnapshotRepository,
    private val chatMessageRepository: ChatMessageRepository
) {

    fun captureForReport(report: SafetyReport): SafetyReportEvidenceSnapshot {
        snapshotRepository.findBySafetyReportId(report.id)?.let { return it }

        val messages = report.chatId
            ?.let { chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(it) }
            ?.sortedWith(compareBy<ChatMessage> { it.sentAt }.thenBy { it.id })
            ?: emptyList()

        return snapshotRepository.save(
            SafetyReportEvidenceSnapshot(
                safetyReportId = report.id,
                chatId = report.chatId,
                matchId = report.matchId,
                connectionId = report.connectionId,
                messageCount = messages.size,
                firstMessageAt = messages.firstOrNull()?.sentAt,
                lastMessageAt = messages.lastOrNull()?.sentAt,
                transcriptSha256 = messages.takeIf { it.isNotEmpty() }?.let { transcriptHash(it) }
            )
        )
    }

    private fun transcriptHash(messages: List<ChatMessage>): String {
        val input = messages.joinToString(separator = "\n") { message ->
            "${message.id}|${message.senderId}|${message.sentAt}|${message.content}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
