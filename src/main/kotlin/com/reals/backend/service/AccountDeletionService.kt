package com.reals.backend.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class AccountDeletionService(
    private val userOperationalContainmentService: UserOperationalContainmentService
) {

    fun closeActiveEngagementsForDeletedUser(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        userOperationalContainmentService.containUser(
            userId = userId,
            reason = UserOperationalContainmentReason.ACCOUNT_DELETION,
            now = now,
            actorUserId = userId
        )
    }
}
