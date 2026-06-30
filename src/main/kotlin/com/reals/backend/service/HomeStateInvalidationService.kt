package com.reals.backend.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class HomeStateInvalidationService(
    private val homeStatusService: HomeStatusService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun bump(
        userId: UUID,
        reason: String
    ) {
        log.debug("Bumping Home status for user={} reason={}", userId, reason)
        homeStatusService.bump(
            userId = userId,
            reason = reason
        )
    }

    fun bumpBoth(
        userAId: UUID,
        userBId: UUID,
        reason: String
    ) {
        bump(userId = userAId, reason = reason)
        if (userBId != userAId) {
            bump(userId = userBId, reason = reason)
        }
    }

    fun bumpUsers(
        userIds: Collection<UUID>,
        reason: String
    ) {
        userIds.distinct().forEach { userId ->
            bump(userId = userId, reason = reason)
        }
    }
}
