package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.domain.UserStatus
import com.reals.backend.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.concurrent.RejectedExecutionException

@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val passwordResetEmailDeliveryService: PasswordResetEmailDeliveryService,
    @param:Qualifier("passwordResetExecutor")
    private val passwordResetExecutor: TaskExecutor
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

        try {
            // Capture only the address, not a managed entity or request/security context.
            passwordResetExecutor.execute {
                try {
                    passwordResetEmailDeliveryService.sendPasswordResetEmail(normalizedEmail)
                } catch (ex: Exception) {
                    log.warn("Password reset delivery failed with exception type {}", ex.javaClass.simpleName)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Saturation and shutdown must keep the same generic response without caller-runs fallback.
            log.warn("Password reset delivery submission rejected (capacity or shutdown)")
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

