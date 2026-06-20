package com.reals.backend.service

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.User
import com.reals.backend.domain.toSafetyReportReason
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.SafetyReportRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.validation.PlainText
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.NoSuchElementException
import java.util.UUID

data class SafetyReportDetail(
    val report: SafetyReport,
    val reporter: User?,
    val reported: User?,
    val messages: List<ChatMessage>,
    val penalty: Penalty?
)

@Service
@Transactional
class SafetyReportService(
    private val safetyReportRepository: SafetyReportRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val penaltyRepository: PenaltyRepository,
    private val userRepository: UserRepository,
    private val penaltyService: PenaltyService
) {

    private companion object {
        const val NOTES_MAX_LENGTH = 1000
    }

    fun createPendingReport(
        chat: Chat,
        reporterUserId: UUID,
        reportedUserId: UUID,
        reason: ChatExitReason,
        details: String
    ): SafetyReport =
        safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUserId,
                reportedUserId = reportedUserId,
                chatId = chat.id,
                matchId = chat.matchId,
                connectionId = chat.connectionId,
                reason = reason.toSafetyReportReason(),
                details = details
            )
        )

    @Transactional(readOnly = true)
    fun listReports(status: SafetyReportStatus?): List<SafetyReport> =
        if (status == null) {
            safetyReportRepository.findAllByOrderByCreatedAtDesc()
        } else {
            safetyReportRepository.findByStatusOrderByCreatedAtDesc(status)
        }

    @Transactional(readOnly = true)
    fun getReport(reportId: UUID): SafetyReport =
        safetyReportRepository.findById(reportId)
            .orElseThrow {
                NoSuchElementException("Safety report not found: $reportId")
            }

    @Transactional(readOnly = true)
    fun getReportDetail(reportId: UUID): SafetyReportDetail {
        val report = getReport(reportId)
        return SafetyReportDetail(
            report = report,
            reporter = userRepository.findById(report.reporterUserId).orElse(null),
            reported = userRepository.findById(report.reportedUserId).orElse(null),
            messages = chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(report.chatId),
            penalty = report.penaltyId?.let { penaltyRepository.findById(it).orElse(null) }
        )
    }

    fun dismissReport(
        reportId: UUID,
        adminUserId: UUID,
        notes: String?
    ): SafetyReport {
        val report = getReport(reportId)
        validatePending(report)

        report.status = SafetyReportStatus.DISMISSED
        report.reviewedAt = OffsetDateTime.now()
        report.reviewedByUserId = adminUserId
        report.verdictNotes = normalizeNotes(notes)

        return safetyReportRepository.save(report)
    }

    fun confirmReportWithPenalty(
        reportId: UUID,
        adminUserId: UUID,
        penaltyType: PenaltyType,
        durationHours: Long?,
        reason: String,
        notes: String?
    ): SafetyReport {
        val report = getReport(reportId)
        validatePending(report)

        val normalizedReason = reason.trim()
        require(normalizedReason.isNotBlank()) {
            "Penalty reason is required"
        }
        PlainText.requireValid("Penalty reason", normalizedReason)

        val penalty =
            when (penaltyType) {
                PenaltyType.TEMPORARY_BAN -> {
                    require(durationHours != null && durationHours > 0) {
                        "durationHours is required and must be positive for temporary penalties"
                    }
                    penaltyService.createTemporaryPenalty(
                        userId = report.reportedUserId,
                        reason = normalizedReason,
                        duration = Duration.ofHours(durationHours),
                        sourceReportId = report.id,
                        appliedByUserId = adminUserId
                    )
                }

                PenaltyType.PERMANENT_BAN -> {
                    require(durationHours == null) {
                        "durationHours must not be provided for permanent penalties"
                    }
                    penaltyService.createPermanentPenalty(
                        userId = report.reportedUserId,
                        reason = normalizedReason,
                        sourceReportId = report.id,
                        appliedByUserId = adminUserId
                    )
                }
            }

        report.status = SafetyReportStatus.CONFIRMED
        report.reviewedAt = OffsetDateTime.now()
        report.reviewedByUserId = adminUserId
        report.verdictNotes = normalizeNotes(notes)
        report.penaltyId = penalty.id

        return safetyReportRepository.save(report)
    }

    private fun validatePending(report: SafetyReport) {
        check(report.status == SafetyReportStatus.PENDING) {
            "Safety report ${report.id} is not pending"
        }
    }

    private fun normalizeNotes(notes: String?): String? {
        val normalized = notes?.trim()?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            require(normalized.length <= NOTES_MAX_LENGTH) {
                "Notes must be at most $NOTES_MAX_LENGTH characters"
            }
            PlainText.requireValid("Notes", normalized)
        }
        return normalized
    }
}
