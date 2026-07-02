package com.reals.backend.service

import com.reals.backend.domain.PushDeviceToken
import com.reals.backend.domain.PushPlatform
import com.reals.backend.repository.PushDeviceTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class PushDeviceTokenService(
    private val pushDeviceTokenRepository: PushDeviceTokenRepository,
    private val userService: UserService
) {

    fun registerToken(
        userId: UUID,
        token: String,
        platform: PushPlatform
    ): PushDeviceToken {
        userService.findByIdOrThrow(userId)

        val normalizedToken = token.trim()
        require(normalizedToken.isNotBlank()) {
            "Push token is required"
        }

        val now = OffsetDateTime.now()
        val existing = pushDeviceTokenRepository.findByToken(normalizedToken)

        if (existing != null) {
            existing.userId = userId
            existing.platform = platform
            existing.enabled = true
            existing.lastSeenAt = now
            existing.updatedAt = now
            return pushDeviceTokenRepository.save(existing)
        }

        return pushDeviceTokenRepository.save(
            PushDeviceToken(
                userId = userId,
                token = normalizedToken,
                platform = platform,
                enabled = true,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = now
            )
        )
    }

    @Transactional(readOnly = true)
    fun findActiveTokens(userId: UUID): List<PushDeviceToken> =
        pushDeviceTokenRepository.findByUserIdAndEnabledTrue(userId)

    fun disableToken(token: String) {
        pushDeviceTokenRepository.disableByToken(
            token = token.trim(),
            updatedAt = OffsetDateTime.now()
        )
    }
}
