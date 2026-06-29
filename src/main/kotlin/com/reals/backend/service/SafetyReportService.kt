package com.reals.backend.service

import com.reals.backend.controller.dto.CreateSafetyReportRequest
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.toSafetyReportReason
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.SafetyReportRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.validation.PlainText
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.NoSuchElementException
import java.util.UUID

@Service
@Transactional
class SafetyReportService(
    private val safetyReportRepository: SafetyReportRepository,
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val matchService: MatchService,
    private val profileRepository: ProfileRepository,
    private val profilePhotoRepository: ProfilePhotoRepository,
    private val visualReviewRepository: VisualReviewRepository,
    private val penaltyRepository: PenaltyRepository,
    private val userRepository: UserRepository,
    private val userBlockService: UserBlockService,
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
    ): SafetyReport {
        val existing = safetyReportRepository.findByReporterUserIdAndReportedUserIdAndContextTypeAndContextId(
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            contextType = SafetyReportContextType.CHAT,
            contextId = chat.id
        )

        if (existing != null) {
            return existing
        }

        return safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUserId,
                reportedUserId = reportedUserId,
                chatId = chat.id,
                matchId = chat.matchId,
                connectionId = chat.connectionId,
                contextType = SafetyReportContextType.CHAT,
                contextId = chat.id,
                reason = reason.toSafetyReportReason(),
                details = details
            )
        )
    }

    fun createUserReport(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest
    ): SafetyReportCreationResult {
        val context = resolveContext(
            reporterUserId = reporterUserId,
            request = request
        )
        val details = normalizeReportDetails(request.details)

        val existing = safetyReportRepository.findByReporterUserIdAndReportedUserIdAndContextTypeAndContextId(
            reporterUserId = reporterUserId,
            reportedUserId = context.reportedUserId,
            contextType = context.contextType,
            contextId = context.contextId
        )

        if (existing != null) {
            userBlockService.blockUser(
                blockerUserId = reporterUserId,
                blockedUserId = context.reportedUserId,
                source = UserBlockSource.SAFETY_REPORT,
                sourceReportId = existing.id
            )
            return SafetyReportCreationResult(
                report = existing,
                created = false
            )
        }

        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUserId,
                reportedUserId = context.reportedUserId,
                chatId = context.chatId,
                matchId = context.matchId,
                connectionId = context.connectionId,
                contextType = context.contextType,
                contextId = context.contextId,
                reason = request.reason,
                details = details
            )
        )

        userBlockService.blockUser(
            blockerUserId = reporterUserId,
            blockedUserId = context.reportedUserId,
            source = UserBlockSource.SAFETY_REPORT,
            sourceReportId = report.id
        )

        return SafetyReportCreationResult(
            report = report,
            created = true
        )
    }

    @Transactional(readOnly = true)
    fun listReports(status: SafetyReportStatus): List<SafetyReport> =
        safetyReportRepository.findByStatusOrderByCreatedAtDesc(status)

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
            messages = report.chatId
                ?.let { chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(it) }
                ?: emptyList(),
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

    private fun resolveContext(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest
    ): SafetyReportContext {
        if (request.reportedUserId == reporterUserId) {
            throw invalidContext("Reporter cannot report themselves")
        }

        return when (request.contextType) {
            SafetyReportContextType.CHAT -> resolveChatContext(
                reporterUserId = reporterUserId,
                request = request
            )

            SafetyReportContextType.VISUAL_PROFILE -> resolveMatchContext(
                reporterUserId = reporterUserId,
                request = request,
                contextType = SafetyReportContextType.VISUAL_PROFILE
            )

            SafetyReportContextType.PERSONAL_MESSAGE -> resolvePersonalMessageContext(
                reporterUserId = reporterUserId,
                request = request
            )

            SafetyReportContextType.PROFILE_PHOTO -> resolveProfilePhotoContext(
                reporterUserId = reporterUserId,
                request = request
            )
        }
    }

    private fun resolveChatContext(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest
    ): SafetyReportContext {
        val chatId = request.chatId ?: throw invalidContext("chatId is required")
        val chat = chatRepository.findById(chatId)
            .orElseThrow { invalidContext("Chat context was not found") }
        val match = findMatchOrInvalid(chat.matchId)
        val reportedUserId = otherParticipantOrThrow(
            match = match,
            reporterUserId = reporterUserId
        )

        requireReportedUserMatches(
            requestedReportedUserId = request.reportedUserId,
            inferredReportedUserId = reportedUserId
        )
        requireOptionalIdMatches("matchId", request.matchId, chat.matchId)
        requireOptionalIdMatches("connectionId", request.connectionId, chat.connectionId)

        return SafetyReportContext(
            reportedUserId = reportedUserId,
            chatId = chat.id,
            matchId = chat.matchId,
            connectionId = chat.connectionId,
            contextType = SafetyReportContextType.CHAT,
            contextId = chat.id
        )
    }

    private fun resolveMatchContext(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest,
        contextType: SafetyReportContextType
    ): SafetyReportContext {
        val matchId = request.matchId ?: throw invalidContext("matchId is required")
        if (request.connectionId != null) {
            throw invalidContext("connectionId is not supported for this context")
        }
        val match = findMatchOrInvalid(matchId)
        requireVisualPhaseOrLater(match)
        val reportedUserId = otherParticipantOrThrow(
            match = match,
            reporterUserId = reporterUserId
        )

        requireReportedUserMatches(
            requestedReportedUserId = request.reportedUserId,
            inferredReportedUserId = reportedUserId
        )

        return SafetyReportContext(
            reportedUserId = reportedUserId,
            chatId = null,
            matchId = match.id,
            connectionId = null,
            contextType = contextType,
            contextId = match.id
        )
    }

    private fun resolvePersonalMessageContext(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest
    ): SafetyReportContext {
        val context = resolveMatchContext(
            reporterUserId = reporterUserId,
            request = request,
            contextType = SafetyReportContextType.PERSONAL_MESSAGE
        )
        val match = findMatchOrInvalid(context.matchId)
        val review = visualReviewRepository.findByMatchId(match.id)
            ?: throw invalidContext("Personal message context was not found")
        val reportedUserSubmittedMessage =
            when (context.reportedUserId) {
                match.userAId -> review.personalMessageA != null
                match.userBId -> review.personalMessageB != null
                else -> false
            }

        if (!reportedUserSubmittedMessage) {
            throw invalidContext("Reported user has no personal message in this match")
        }

        return context
    }

    private fun resolveProfilePhotoContext(
        reporterUserId: UUID,
        request: CreateSafetyReportRequest
    ): SafetyReportContext {
        val matchId = request.matchId ?: throw invalidContext("matchId is required")
        val photoId = request.profilePhotoId ?: throw invalidContext("profilePhotoId is required")
        if (request.connectionId != null) {
            throw invalidContext("connectionId is not supported for this context")
        }
        val match = findMatchOrInvalid(matchId)
        requireVisualPhaseOrLater(match)
        val profilePhoto = profilePhotoRepository.findById(photoId)
            .orElseThrow { invalidContext("Profile photo context was not found") }
        val ownerProfile = profileRepository.findById(profilePhoto.profileId)
            .orElseThrow { invalidContext("Profile photo owner was not found") }

        val matchReportedUserId = otherParticipantOrThrow(
            match = match,
            reporterUserId = reporterUserId
        )

        if (ownerProfile.userId != matchReportedUserId) {
            throw invalidContext("Profile photo does not belong to the matched partner")
        }

        requireReportedUserMatches(
            requestedReportedUserId = request.reportedUserId,
            inferredReportedUserId = ownerProfile.userId
        )

        return SafetyReportContext(
            reportedUserId = ownerProfile.userId,
            chatId = null,
            matchId = match.id,
            connectionId = null,
            contextType = SafetyReportContextType.PROFILE_PHOTO,
            contextId = profilePhoto.id
        )
    }

    private fun otherParticipantOrThrow(
        match: Match,
        reporterUserId: UUID
    ): UUID =
        when (reporterUserId) {
            match.userAId -> match.userBId
            match.userBId -> match.userAId
            else -> throw invalidContext("Reporter is not a participant in this context")
        }

    private fun findMatchOrInvalid(matchId: UUID): Match =
        try {
            matchService.findByIdOrThrow(matchId)
        } catch (_: NoSuchElementException) {
            throw invalidContext("Match context was not found")
        }

    private fun requireVisualPhaseOrLater(match: Match) {
        if (
            match.state !in setOf(
                MatchState.VISUAL_PHASE,
                MatchState.VISUAL_APPROVED,
                MatchState.VISUAL_REJECTED
            )
        ) {
            throw invalidContext("Match is not in a visual reporting context")
        }
    }

    private fun requireReportedUserMatches(
        requestedReportedUserId: UUID,
        inferredReportedUserId: UUID
    ) {
        if (requestedReportedUserId != inferredReportedUserId) {
            throw invalidContext("Reported user does not match the reporting context")
        }
    }

    private fun requireOptionalIdMatches(
        fieldName: String,
        requestedId: UUID?,
        inferredId: UUID?
    ) {
        if (requestedId != null && requestedId != inferredId) {
            throw invalidContext("$fieldName does not match the reporting context")
        }
    }

    private fun normalizeReportDetails(details: String): String {
        val normalized = details.trim()
        if (normalized.isBlank() || normalized.length > NOTES_MAX_LENGTH) {
            throw invalidContext("Report details are invalid")
        }
        if (normalized.any { it.isISOControl() || it == '<' || it == '>' }) {
            throw invalidContext("Report details are invalid")
        }
        return normalized
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

    private fun invalidContext(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.SAFETY_REPORT_CONTEXT_INVALID,
            message = message
        )
}

data class SafetyReportCreationResult(
    val report: SafetyReport,
    val created: Boolean
)

private data class SafetyReportContext(
    val reportedUserId: UUID,
    val chatId: UUID?,
    val matchId: UUID,
    val connectionId: UUID?,
    val contextType: SafetyReportContextType,
    val contextId: UUID
)
