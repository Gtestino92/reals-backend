package com.reals.backend.adminauth

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.service.AuditEventService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

enum class AdminGrantRevocationReason { ACCOUNT_BAN_APPLIED, ACCOUNT_DELETION_REQUESTED }

@Service
class AdminGrantRevocationService(
    private val grants: AdminAccessGrantRepository,
    private val audit: AuditEventService,
    private val clock: Clock
) {
    /** Caller holds the user lock. Join the restriction transaction: user -> grants, never reverse. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun revokeOutstandingUnderUserLock(userId: UUID, reason: AdminGrantRevocationReason, actorUserId: UUID? = null) {
        val now = clock.instant()
        val changed = grants.revokeOutstanding(userId, now)
        if (changed > 0) audit.record(
            eventType = AuditEventType.ADMIN_GRANT_REVOKED,
            aggregateType = AuditAggregateType.USER,
            aggregateId = userId,
            actorUserId = actorUserId,
            targetUserId = userId,
            metadata = mapOf("reason" to reason.name)
        )
    }
}
