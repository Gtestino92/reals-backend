package com.reals.backend.service.reports

import com.reals.backend.controller.dto.CreateAdminSafetyReportRequest
import com.reals.backend.controller.dto.CreateSafetyReportRequest
import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatExitReason
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchState
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.PenaltyType
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportContextType
import com.reals.backend.domain.SafetyReportSource
import com.reals.backend.domain.SafetyReportStatus
import com.reals.backend.domain.UserReliabilityEventType
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.domain.toSafetyReportReason
import com.reals.backend.domain.priorityReview
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ProfilePhotoRepository
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.SafetyReportRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.MatchService
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.PairInteractionContainmentCause
import com.reals.backend.service.PairInteractionContainmentService
import com.reals.backend.service.UserBlockService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.reliability.UserReliabilityScoreService
import com.reals.backend.validation.PlainText
import org.slf4j.LoggerFactory
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
    private val pairInteractionContainmentService: PairInteractionContainmentService,
    private val auditEventService: AuditEventService,
    private val evidenceSnapshotService: SafetyReportEvidenceSnapshotService,
    private val penaltyService: PenaltyService,
    private val userReliabilityScoreService: UserReliabilityScoreService
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        lockReportUsersForUpdate(reporterUserId, reportedUserId)
        rejectExistingUserReport(
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            contextType = SafetyReportContextType.CHAT,
            contextId = chat.id
        )

        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUserId,
                reportedUserId = reportedUserId,
                chatId = chat.id,
                matchId = chat.matchId,
                connectionId = chat.connectionId,
                source = SafetyReportSource.USER,
                createdByAdminUserId = null,
                contextType = SafetyReportContextType.CHAT,
                contextId = chat.id,
                reason = reason.toSafetyReportReason(),
                details = details
            )
        )

        captureEvidenceAndAuditReportCreated(report, actorUserId = reporterUserId)
        return report
    }

    private fun rejectExistingUserReport(
        reporterUserId: UUID,
        reportedUserId: UUID,
        contextType: SafetyReportContextType,
        contextId: UUID
    ) {
        safetyReportRepository.findBySourceAndReporterUserIdAndReportedUserIdAndContextTypeAndContextId(
            source = SafetyReportSource.USER,
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            contextType = contextType,
            contextId = contextId
        )?.let {
            throw DomainConflictException(
                code = DomainErrorCode.SAFETY_REPORT_ALREADY_EXISTS,
                message = "Safety report already exists"
            )
        }
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

        lockReportUsersForUpdate(reporterUserId, context.reportedUserId)
        rejectExistingUserReport(
            reporterUserId = reporterUserId,
            reportedUserId = context.reportedUserId,
            contextType = context.contextType,
            contextId = context.contextId
        )

        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUserId,
                reportedUserId = context.reportedUserId,
                chatId = context.chatId,
                matchId = context.matchId,
                connectionId = context.connectionId,
                source = SafetyReportSource.USER,
                createdByAdminUserId = null,
                contextType = context.contextType,
                contextId = context.contextId,
                reason = request.reason,
                details = details
            )
        )

        captureEvidenceAndAuditReportCreated(report, actorUserId = reporterUserId)

        pairInteractionContainmentService.containPair(
            userAId = reporterUserId,
            userBId = context.reportedUserId,
            cause = PairInteractionContainmentCause.SAFETY_REPORT
        )

        if (request.blockUser) {
            userBlockService.blockUserWithResult(
                blockerUserId = reporterUserId,
                blockedUserId = context.reportedUserId,
                source = UserBlockSource.SAFETY_REPORT,
                sourceReportId = report.id
            )
        }

        return SafetyReportCreationResult(
            report = report,
            created = true
        )
    }

    private fun lockReportUsersForUpdate(
        reporterUserId: UUID,
        reportedUserId: UUID
    ) {
        val orderedIds = listOf(reporterUserId, reportedUserId).sortedBy(UUID::toString)
        check(userRepository.findAllByIdForUpdate(orderedIds).size == 2) {
            "Cannot create safety report: one or more users were not found"
        }
    }

    fun createAdminReport(
        adminUserId: UUID,
        request: CreateAdminSafetyReportRequest
    ): SafetyReport {
        findUserOrInvalid(adminUserId, "Admin user was not found")
        val reportedUser = findUserOrInvalid(request.reportedUserId, "Reported user was not found")
        val reporterUser = request.reporterUserId?.let {
            if (it == request.reportedUserId) {
                throw invalidAdminCreate("Reporter cannot be the reported user")
            }
            findUserOrInvalid(it, "Reporter user was not found")
        }
        val details = normalizeReportDetails(request.details)
        val context = resolveAdminContext(
            request = request,
            reporterUserId = reporterUser?.id
        )

        if (context.reportedUserId != reportedUser.id) {
            throw invalidAdminCreate("Reported user does not match the reporting context")
        }

        val report = safetyReportRepository.save(
            SafetyReport(
                reporterUserId = reporterUser?.id,
                reportedUserId = reportedUser.id,
                chatId = context.chatId,
                matchId = context.matchId,
                connectionId = context.connectionId,
                source = SafetyReportSource.ADMIN,
                createdByAdminUserId = adminUserId,
                contextType = context.contextType,
                contextId = context.contextId,
                reason = request.reason,
                details = details
            )
        )

        captureEvidenceAndAuditReportCreated(report, actorUserId = adminUserId)
        return report
    }

    @Transactional(readOnly = true)
    fun listReports(status: SafetyReportStatus): List<SafetyReport> =
        safetyReportRepository.findByStatusOrderByCreatedAtDesc(status)

    @Transactional(readOnly = true)
    fun listReportDetails(
        status: SafetyReportStatus?,
        source: SafetyReportSource?,
        reportedUserId: UUID?,
        reporterUserId: UUID?
    ): List<SafetyReportDetail> =
        safetyReportRepository.findAllByOrderByCreatedAtDesc()
            .asSequence()
            .filter { status == null || it.status == status }
            .filter { source == null || it.source == source }
            .filter { reportedUserId == null || it.reportedUserId == reportedUserId }
            .filter { reporterUserId == null || it.reporterUserId == reporterUserId }
            .sortedWith(
                compareByDescending<SafetyReport> { it.priorityReview }
                    .thenByDescending { it.createdAt }
            )
            .take(100)
            .map { buildReportDetail(it, includeMessages = false, includePenalty = false) }
            .toList()

    @Transactional(readOnly = true)
    fun getReport(reportId: UUID): SafetyReport =
        safetyReportRepository.findById(reportId)
            .orElseThrow {
                DomainNotFoundException(
                    code = DomainErrorCode.SAFETY_REPORT_NOT_FOUND,
                    message = "Safety report was not found"
                )
            }

    @Transactional(readOnly = true)
    fun getReportDetail(reportId: UUID): SafetyReportDetail {
        val report = getReport(reportId)
        return buildReportDetail(report, includeMessages = true, includePenalty = true)
    }

    fun dismissReport(
        reportId: UUID,
        adminUserId: UUID,
        notes: String?
    ): SafetyReport {
        val report = getReport(reportId)
        val previousStatus = report.status
        validatePending(report)

        report.status = SafetyReportStatus.DISMISSED
        report.reviewedAt = OffsetDateTime.now()
        report.reviewedByUserId = adminUserId
        report.verdictNotes = normalizeNotes(notes)

        val saved = safetyReportRepository.save(report)
        auditEventService.record(
            eventType = AuditEventType.SAFETY_REPORT_DISMISSED,
            aggregateType = AuditAggregateType.SAFETY_REPORT,
            aggregateId = saved.id,
            actorUserId = adminUserId,
            targetUserId = saved.reportedUserId,
            metadata = mapOf(
                "source" to saved.source.name,
                "previousStatus" to previousStatus.name,
                "newStatus" to saved.status.name
            )
        )
        return saved
    }

    fun dismissAbusiveOrUnjustifiedReport(
        reportId: UUID,
        adminUserId: UUID,
        notes: String?
    ): SafetyReport {
        val report = getReport(reportId)
        val previousStatus = report.status
        validatePending(report)

        report.status = SafetyReportStatus.DISMISSED_ABUSIVE_OR_UNJUSTIFIED
        report.reviewedAt = OffsetDateTime.now()
        report.reviewedByUserId = adminUserId
        report.verdictNotes = normalizeNotes(notes)

        val saved = safetyReportRepository.save(report)

        saved.reporterUserId?.let { reporterUserId ->
            userReliabilityScoreService.recordEvent(
                userId = reporterUserId,
                eventType = UserReliabilityEventType.SAFETY_REPORT_DETERMINED_ABUSIVE,
                relatedSafetyReportId = saved.id
            )
        }

        auditEventService.record(
            eventType = AuditEventType.SAFETY_REPORT_DISMISSED,
            aggregateType = AuditAggregateType.SAFETY_REPORT,
            aggregateId = saved.id,
            actorUserId = adminUserId,
            targetUserId = saved.reporterUserId,
            metadata = mapOf(
                "source" to saved.source.name,
                "previousStatus" to previousStatus.name,
                "newStatus" to saved.status.name
            )
        )
        return saved
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
        val previousStatus = report.status
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

        val saved = safetyReportRepository.save(report)
        if (penalty.type == PenaltyType.TEMPORARY_BAN) {
            userReliabilityScoreService.recordEvent(
                userId = saved.reportedUserId,
                eventType = UserReliabilityEventType.SAFETY_REPORT_CONFIRMED_AGAINST_USER,
                relatedSafetyReportId = saved.id
            )
        }
        auditEventService.record(
            eventType = AuditEventType.SAFETY_REPORT_CONFIRMED,
            aggregateType = AuditAggregateType.SAFETY_REPORT,
            aggregateId = saved.id,
            actorUserId = adminUserId,
            targetUserId = saved.reportedUserId,
            metadata = mapOf(
                "source" to saved.source.name,
                "previousStatus" to previousStatus.name,
                "newStatus" to saved.status.name,
                "penaltyId" to penalty.id,
                "penaltyType" to penalty.type.name
            )
        )
        return saved
    }

    private fun validatePending(report: SafetyReport) {
        if (report.status != SafetyReportStatus.PENDING) {
            throw DomainConflictException(
                code = DomainErrorCode.SAFETY_REPORT_ALREADY_REVIEWED,
                message = "Safety report is already reviewed"
            )
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

            SafetyReportContextType.USER -> throw invalidContext("USER report context is admin-only")
        }
    }

    private fun resolveAdminContext(
        request: CreateAdminSafetyReportRequest,
        reporterUserId: UUID?
    ): SafetyReportContext =
        when (request.contextType) {
            SafetyReportContextType.USER -> resolveAdminUserContext(request)

            SafetyReportContextType.CHAT ->
                if (reporterUserId != null) {
                    resolveChatContext(reporterUserId, request.toUserRequest())
                } else {
                    resolveAdminChatContext(request)
                }

            SafetyReportContextType.VISUAL_PROFILE ->
                if (reporterUserId != null) {
                    resolveMatchContext(
                        reporterUserId = reporterUserId,
                        request = request.toUserRequest(),
                        contextType = SafetyReportContextType.VISUAL_PROFILE
                    )
                } else {
                    resolveAdminMatchContext(request, SafetyReportContextType.VISUAL_PROFILE)
                }

            SafetyReportContextType.PERSONAL_MESSAGE ->
                if (reporterUserId != null) {
                    resolvePersonalMessageContext(reporterUserId, request.toUserRequest())
                } else {
                    resolveAdminPersonalMessageContext(request)
                }

            SafetyReportContextType.PROFILE_PHOTO ->
                if (reporterUserId != null) {
                    resolveProfilePhotoContext(reporterUserId, request.toUserRequest())
                } else {
                    resolveAdminProfilePhotoContext(request)
                }
        }

    private fun resolveAdminUserContext(request: CreateAdminSafetyReportRequest): SafetyReportContext {
        if (
            request.chatId != null ||
            request.matchId != null ||
            request.connectionId != null ||
            request.profilePhotoId != null
        ) {
            throw invalidAdminCreate("USER context must not include chat, match, connection or photo ids")
        }

        return SafetyReportContext(
            reportedUserId = request.reportedUserId,
            chatId = null,
            matchId = null,
            connectionId = null,
            contextType = SafetyReportContextType.USER,
            contextId = request.reportedUserId
        )
    }

    private fun resolveAdminChatContext(request: CreateAdminSafetyReportRequest): SafetyReportContext {
        val chatId = request.chatId ?: throw invalidAdminCreate("chatId is required")
        val chat = chatRepository.findById(chatId)
            .orElseThrow { invalidAdminCreate("Chat context was not found") }
        val match = findMatchOrInvalid(chat.matchId)
        requireMatchParticipant(match, request.reportedUserId)
        requireOptionalIdMatches("matchId", request.matchId, chat.matchId)
        requireOptionalIdMatches("connectionId", request.connectionId, chat.connectionId)

        return SafetyReportContext(
            reportedUserId = request.reportedUserId,
            chatId = chat.id,
            matchId = chat.matchId,
            connectionId = chat.connectionId,
            contextType = SafetyReportContextType.CHAT,
            contextId = chat.id
        )
    }

    private fun resolveAdminMatchContext(
        request: CreateAdminSafetyReportRequest,
        contextType: SafetyReportContextType
    ): SafetyReportContext {
        val matchId = request.matchId ?: throw invalidAdminCreate("matchId is required")
        if (request.connectionId != null) {
            throw invalidAdminCreate("connectionId is not supported for this context")
        }
        val match = findMatchOrInvalid(matchId)
        requireVisualPhaseOrLater(match)
        requireMatchParticipant(match, request.reportedUserId)

        return SafetyReportContext(
            reportedUserId = request.reportedUserId,
            chatId = null,
            matchId = match.id,
            connectionId = null,
            contextType = contextType,
            contextId = match.id
        )
    }

    private fun resolveAdminPersonalMessageContext(request: CreateAdminSafetyReportRequest): SafetyReportContext {
        val context = resolveAdminMatchContext(
            request = request,
            contextType = SafetyReportContextType.PERSONAL_MESSAGE
        )
        val match = findMatchOrInvalid(requireNotNull(context.matchId))
        val review = visualReviewRepository.findByMatchId(match.id)
            ?: throw invalidAdminCreate("Personal message context was not found")
        val reportedUserSubmittedMessage =
            when (context.reportedUserId) {
                match.userAId -> review.personalMessageA != null
                match.userBId -> review.personalMessageB != null
                else -> false
            }

        if (!reportedUserSubmittedMessage) {
            throw invalidAdminCreate("Reported user has no personal message in this match")
        }

        return context
    }

    private fun resolveAdminProfilePhotoContext(request: CreateAdminSafetyReportRequest): SafetyReportContext {
        val matchId = request.matchId ?: throw invalidAdminCreate("matchId is required")
        val photoId = request.profilePhotoId ?: throw invalidAdminCreate("profilePhotoId is required")
        if (request.connectionId != null) {
            throw invalidAdminCreate("connectionId is not supported for this context")
        }
        val match = findMatchOrInvalid(matchId)
        requireVisualPhaseOrLater(match)
        requireMatchParticipant(match, request.reportedUserId)
        val profilePhoto = profilePhotoRepository.findById(photoId)
            .orElseThrow { invalidAdminCreate("Profile photo context was not found") }
        val ownerProfile = profileRepository.findById(profilePhoto.profileId)
            .orElseThrow { invalidAdminCreate("Profile photo owner was not found") }

        if (ownerProfile.userId != request.reportedUserId) {
            throw invalidAdminCreate("Profile photo does not belong to the reported user")
        }

        return SafetyReportContext(
            reportedUserId = ownerProfile.userId,
            chatId = null,
            matchId = match.id,
            connectionId = null,
            contextType = SafetyReportContextType.PROFILE_PHOTO,
            contextId = profilePhoto.id
        )
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
        val match = findMatchOrInvalid(requireNotNull(context.matchId))
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

    private fun requireMatchParticipant(match: Match, userId: UUID) {
        if (userId != match.userAId && userId != match.userBId) {
            throw invalidAdminCreate("Reported user does not belong to the reporting context")
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

    private fun buildReportDetail(
        report: SafetyReport,
        includeMessages: Boolean,
        includePenalty: Boolean
    ): SafetyReportDetail =
        SafetyReportDetail(
            report = report,
            reporter = report.reporterUserId?.let { userRepository.findById(it).orElse(null) },
            reported = userRepository.findById(report.reportedUserId).orElse(null),
            messages = if (includeMessages) {
                report.chatId
                    ?.let { chatMessageRepository.findByChatSessionIdOrderBySentAtAsc(it) }
                    ?: emptyList()
            } else {
                emptyList()
            },
            penalty = if (includePenalty) {
                report.penaltyId?.let { penaltyRepository.findById(it).orElse(null) }
            } else {
                null
            },
            evidence = evidenceSnapshotService.findByReportId(report.id),
            reportedUserCounters = reportCounters(report.reportedUserId)
        )

    private fun reportCounters(reportedUserId: UUID): SafetyReportUserCounters {
        val since = OffsetDateTime.now().minusDays(30)
        return SafetyReportUserCounters(
            pendingReportsTotal = safetyReportRepository.countByReportedUserIdAndStatus(
                reportedUserId = reportedUserId,
                status = SafetyReportStatus.PENDING
            ),
            confirmedReportsTotal = safetyReportRepository.countByReportedUserIdAndStatus(
                reportedUserId = reportedUserId,
                status = SafetyReportStatus.CONFIRMED
            ),
            confirmedReportsLast30Days = safetyReportRepository.countByReportedUserIdAndStatusAndCreatedAtGreaterThanEqual(
                reportedUserId = reportedUserId,
                status = SafetyReportStatus.CONFIRMED,
                createdAt = since
            )
        )
    }

    private fun captureEvidenceAndAuditReportCreated(
        report: SafetyReport,
        actorUserId: UUID?
    ) {
        runCatching {
            evidenceSnapshotService.captureForReport(report)
        }.onFailure {
            log.warn("Failed to capture safety report evidence snapshot for report={}", report.id, it)
        }

        auditEventService.record(
            eventType = AuditEventType.SAFETY_REPORT_CREATED,
            aggregateType = AuditAggregateType.SAFETY_REPORT,
            aggregateId = report.id,
            actorUserId = actorUserId,
            targetUserId = report.reportedUserId,
            metadata = mapOf(
                "source" to report.source.name,
                "contextType" to report.contextType.name,
                "contextId" to report.contextId,
                "reason" to report.reason.name,
                "status" to report.status.name
            )
        )
    }

    private fun findUserOrInvalid(
        userId: UUID,
        message: String
    ) =
        userRepository.findById(userId)
            .orElseThrow { invalidAdminCreate(message) }

    private fun invalidContext(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.SAFETY_REPORT_CONTEXT_INVALID,
            message = message
        )

    private fun invalidAdminCreate(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.SAFETY_REPORT_ADMIN_CREATE_INVALID,
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
    val matchId: UUID?,
    val connectionId: UUID?,
    val contextType: SafetyReportContextType,
    val contextId: UUID
)

private fun CreateAdminSafetyReportRequest.toUserRequest(): CreateSafetyReportRequest =
    CreateSafetyReportRequest(
        reportedUserId = reportedUserId,
        contextType = contextType,
        chatId = chatId,
        matchId = matchId,
        connectionId = connectionId,
        profilePhotoId = profilePhotoId,
        reason = reason,
        details = details
    )
