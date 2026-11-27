package com.reals.backend.service

import com.reals.backend.domain.UserHomeStatus
import com.reals.backend.repository.UserHomeStatusRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class HomeStatusService(
    private val homeStatusRepository: UserHomeStatusRepository
) {

    @Transactional
    fun getOrCreateStatus(userId: UUID): UserHomeStatus {
        homeStatusRepository.findById(userId).orElse(null)?.let {
            return it
        }

        homeStatusRepository.insertDefaultIfMissing(userId, OffsetDateTime.now())
        return homeStatusRepository.findById(userId).orElseThrow()
    }

    @Transactional
    fun bump(
        userId: UUID,
        reason: String
    ): UserHomeStatus {
        invalidate(userId, reason)
        return homeStatusRepository.findById(userId).orElseThrow()
    }

    @Transactional
    fun invalidate(userId: UUID, reason: String) {
        val now = OffsetDateTime.now()
        val updated = homeStatusRepository.bumpVersion(
            userId = userId,
            updatedAt = now
        )

        if (updated == 0) {
            homeStatusRepository.insertDefaultIfMissing(userId, now)
            check(homeStatusRepository.bumpVersion(userId, now) == 1) {
                "Home status missing after bump for user $userId ($reason)"
            }
        }
    }

    @Transactional
    fun bumpBoth(
        userAId: UUID,
        userBId: UUID,
        reason: String
    ) {
        invalidate(userId = userAId, reason = reason)
        if (userBId != userAId) {
            invalidate(userId = userBId, reason = reason)
        }
    }

    @Transactional
    fun markClean(userId: UUID): Boolean =
        homeStatusRepository.markClean(
            userId = userId,
            updatedAt = OffsetDateTime.now()
        ) == 1

    @Transactional
    fun markCleanIfVersionStill(
        userId: UUID,
        expectedVersion: Long
    ): Boolean =
        homeStatusRepository.markCleanIfVersionStill(
            userId = userId,
            expectedVersion = expectedVersion,
            updatedAt = OffsetDateTime.now()
        ) == 1

    @Transactional
    fun scheduleNextRefreshAt(
        userId: UUID,
        nextRefreshAt: OffsetDateTime
    ) {
        val now = OffsetDateTime.now()
        val updated = homeStatusRepository.scheduleNextRefreshAt(
            userId = userId,
            nextRefreshAt = nextRefreshAt,
            updatedAt = now
        )

        if (updated == 0) {
            homeStatusRepository.insertDefaultIfMissing(userId, now)
            homeStatusRepository.scheduleNextRefreshAt(
                userId = userId,
                nextRefreshAt = nextRefreshAt,
                updatedAt = now
            )
        }
    }

    @Transactional
    fun scheduleNextRefreshAtForBoth(
        userAId: UUID,
        userBId: UUID,
        nextRefreshAt: OffsetDateTime
    ) {
        scheduleNextRefreshAt(userId = userAId, nextRefreshAt = nextRefreshAt)
        if (userBId != userAId) {
            scheduleNextRefreshAt(userId = userBId, nextRefreshAt = nextRefreshAt)
        }
    }

    @Transactional
    fun reconcileAfterFullHomeIfVersionStill(
        userId: UUID,
        expectedVersion: Long,
        nextRefreshAt: OffsetDateTime?
    ): Boolean =
        homeStatusRepository.reconcileAfterFullHomeIfVersionStill(
            userId = userId,
            expectedVersion = expectedVersion,
            nextRefreshAt = nextRefreshAt,
            updatedAt = OffsetDateTime.now()
        ) == 1

}
