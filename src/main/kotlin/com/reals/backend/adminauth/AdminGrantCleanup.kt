package com.reals.backend.adminauth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Component
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true")
class AdminGrantCleanup(private val grants: AdminAccessGrantRepository, private val clock: Clock) {
    @Scheduled(fixedDelay = 3600000)
    @Transactional
    fun cleanup() {
        val now = clock.instant()
        val hashes = grants.findFirst100ByExpiresAtLessThanEqualOrderByExpiresAtAscTokenHashAsc(now).map { it.tokenHash }
        // Bulk deletion tolerates candidates already removed by another instance.
        if (hashes.isNotEmpty()) grants.deleteExpired(hashes, now)
    }
}
