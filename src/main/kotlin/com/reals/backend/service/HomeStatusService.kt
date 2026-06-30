package com.reals.backend.service

import com.reals.backend.domain.UserHomeStatus
import com.reals.backend.repository.UserHomeStatusRepository
import org.springframework.dao.DataIntegrityViolationException
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

        val now = OffsetDateTime.now()
        return try {
            homeStatusRepository.saveAndFlush(
                UserHomeStatus(
                    userId = userId,
                    version = 0,
                    dirty = false,
                    updatedAt = now
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            homeStatusRepository.findById(userId).orElseThrow()
        }
    }

    @Transactional
    fun bump(
        userId: UUID,
        reason: String
    ): UserHomeStatus {
        val now = OffsetDateTime.now()
        val updated = homeStatusRepository.bumpVersion(
            userId = userId,
            updatedAt = now
        )

        if (updated == 0) {
            createDefaultStatusIfMissing(userId = userId, now = now)
            homeStatusRepository.bumpVersion(
                userId = userId,
                updatedAt = now
            )
        }

        return homeStatusRepository.findById(userId).orElseThrow {
            IllegalStateException("Home status missing after bump for user $userId ($reason)")
        }
    }

    @Transactional
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

    @Transactional
    fun markClean(userId: UUID): Boolean =
        homeStatusRepository.markClean(
            userId = userId,
            updatedAt = OffsetDateTime.now()
        ) == 1

    private fun createDefaultStatusIfMissing(
        userId: UUID,
        now: OffsetDateTime
    ) {
        try {
            homeStatusRepository.saveAndFlush(
                UserHomeStatus(
                    userId = userId,
                    version = 0,
                    dirty = false,
                    updatedAt = now
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            if (!homeStatusRepository.existsById(userId)) {
                throw ex
            }
        }
    }
}
