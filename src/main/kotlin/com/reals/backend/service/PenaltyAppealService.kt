package com.reals.backend.service

import com.reals.backend.controller.dto.PenaltyAppealDecision
import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.PenaltyAppealStatus
import com.reals.backend.domain.PenaltyType
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.validation.PlainText
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class PenaltyAppealService(
    private val penaltyRepository: PenaltyRepository,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService
) {

    @Transactional(readOnly = true)
    fun getMyAppeal(userId: UUID): Penalty =
        penaltyRepository.findFirstByUserIdAndTypeAndActiveTrueOrderByCreatedAtDesc(
            userId = userId,
            type = PenaltyType.PERMANENT_BAN
        )
            ?: penaltyRepository.findFirstByUserIdAndTypeAndAppealStatusInOrderByAppealedAtDesc(
                userId = userId,
                type = PenaltyType.PERMANENT_BAN,
                appealStatuses = listOf(PenaltyAppealStatus.APPROVED, PenaltyAppealStatus.REJECTED)
            )
            ?: throw DomainConflictException(
                code = DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE,
                message = "Penalty appeal is not available"
            )

    fun submitMyAppeal(
        userId: UUID,
        statement: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Penalty {
        val normalizedStatement = normalizeText(statement, "Appeal statement")
        val penalty = penaltyRepository.findActivePermanentByUserIdForUpdate(userId).singleOrNull()
            ?: throw DomainConflictException(
                code = DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE,
                message = "Penalty appeal is not available"
            )

        if (penalty.appealStatus != null) {
            throw DomainConflictException(
                code = DomainErrorCode.PENALTY_APPEAL_ALREADY_SUBMITTED,
                message = "Penalty appeal has already been submitted"
            )
        }

        penalty.appealStatus = PenaltyAppealStatus.PENDING
        penalty.appealStatement = normalizedStatement
        penalty.appealedAt = now

        val saved = penaltyRepository.save(penalty)
        auditEventService.record(
            eventType = AuditEventType.PENALTY_APPEAL_SUBMITTED,
            aggregateType = AuditAggregateType.PENALTY,
            aggregateId = saved.id,
            actorUserId = userId,
            targetUserId = userId,
            metadata = mapOf("status" to saved.appealStatus?.name)
        )
        return saved
    }

    @Transactional(readOnly = true)
    fun listPendingAppeals(): List<Penalty> =
        penaltyRepository.findByAppealStatusOrderByAppealedAtAsc(PenaltyAppealStatus.PENDING)

    fun decideAppeal(
        penaltyId: UUID,
        adminUserId: UUID,
        decision: PenaltyAppealDecision,
        notes: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Penalty {
        val normalizedNotes = normalizeText(notes, "Appeal review notes")
        val penalty = penaltyRepository.findByIdForUpdate(penaltyId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE,
                message = "Penalty appeal was not found"
            )

        if (penalty.appealStatus != PenaltyAppealStatus.PENDING) {
            throw DomainConflictException(
                code = DomainErrorCode.PENALTY_APPEAL_NOT_PENDING,
                message = "Penalty appeal is not pending"
            )
        }

        penalty.appealStatus = when (decision) {
            PenaltyAppealDecision.APPROVE -> PenaltyAppealStatus.APPROVED
            PenaltyAppealDecision.REJECT -> PenaltyAppealStatus.REJECTED
        }
        penalty.appealReviewedAt = now
        penalty.appealReviewedByUserId = adminUserId
        penalty.appealReviewNotes = normalizedNotes
        if (decision == PenaltyAppealDecision.APPROVE) {
            penalty.active = false
        }

        val saved = penaltyRepository.save(penalty)
        auditEventService.record(
            eventType = AuditEventType.PENALTY_APPEAL_REVIEWED,
            aggregateType = AuditAggregateType.PENALTY,
            aggregateId = saved.id,
            actorUserId = adminUserId,
            targetUserId = saved.userId,
            metadata = mapOf(
                "decision" to decision.name,
                "status" to saved.appealStatus?.name
            )
        )
        if (decision == PenaltyAppealDecision.APPROVE) {
            homeStateInvalidationService.bump(
                userId = saved.userId,
                reason = "penalty_appeal_approved"
            )
        }
        return saved
    }

    private fun normalizeText(
        value: String,
        fieldName: String
    ): String {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.length > 1000) {
            throw DomainConflictException(
                code = DomainErrorCode.PENALTY_APPEAL_NOT_AVAILABLE,
                message = "$fieldName is invalid"
            )
        }
        PlainText.requireValid(fieldName, normalized)
        return normalized
    }
}
