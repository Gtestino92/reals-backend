package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.domain.UserStatus
import com.reals.backend.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val passwordResetEmailDeliveryService: PasswordResetEmailDeliveryService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun requestPasswordReset(
        email: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        val normalizedEmail = UserEmailNormalizer.normalizeRequired(email)
        val user = userRepository.findByEmail(normalizedEmail) ?: return

        if (!passwordResetEligible(user, now)) {
            return
        }

        runCatching {
            passwordResetEmailDeliveryService.sendPasswordResetEmail(normalizedEmail)
        }.onFailure { ex ->
            log.warn(
                "Password reset delivery failed for eligible userId={} with exception type {}",
                user.id,
                ex.javaClass.simpleName
            )
        }
    }

    fun passwordResetEligible(
        user: User,
        now: OffsetDateTime
    ): Boolean {
        if (user.authOrigin != UserAuthOrigin.EMAIL_PASSWORD || user.firebaseUid.isNullOrBlank()) {
            return false
        }

        return when (user.status) {
            UserStatus.ACTIVE -> true
            UserStatus.DELETED -> {
                val deletionFinalizesAt = user.deletionFinalizesAt ?: return false
                now.isBefore(deletionFinalizesAt)
            }
        }
    }
}

