package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.domain.UserStatus
import com.reals.backend.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.UUID

class PasswordResetServiceTest {

    private val userRepository = mock(UserRepository::class.java)
    private val deliveryService = mock(PasswordResetEmailDeliveryService::class.java)
    private val service = PasswordResetService(userRepository, deliveryService)
    private val now = OffsetDateTime.parse("2026-08-11T12:00:00Z")

    @Test
    fun `active email password user receives reset delivery`() {
        givenUser(user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD))

        service.requestPasswordReset(" USER@Example.com ", now)

        verify(deliveryService).sendPasswordResetEmail("user@example.com")
    }

    @Test
    fun `recoverably deleted email password user before deadline receives reset delivery`() {
        givenUser(
            user(
                authOrigin = UserAuthOrigin.EMAIL_PASSWORD,
                status = UserStatus.DELETED,
                deletionFinalizesAt = now.plusSeconds(1)
            )
        )

        service.requestPasswordReset("user@example.com", now)

        verify(deliveryService).sendPasswordResetEmail("user@example.com")
    }

    @Test
    fun `active google user does not receive reset delivery`() {
        givenUser(user(authOrigin = UserAuthOrigin.GOOGLE))

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `recoverably deleted google user does not receive reset delivery`() {
        givenUser(
            user(
                authOrigin = UserAuthOrigin.GOOGLE,
                status = UserStatus.DELETED,
                deletionFinalizesAt = now.plusDays(1)
            )
        )

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `unknown email does not receive reset delivery`() {
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(null)

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `user without firebase uid does not receive reset delivery`() {
        givenUser(user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD, firebaseUid = null))

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `deleted account at exact finalization deadline does not receive reset delivery`() {
        givenUser(
            user(
                authOrigin = UserAuthOrigin.EMAIL_PASSWORD,
                status = UserStatus.DELETED,
                deletionFinalizesAt = now
            )
        )

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `deleted account after finalization deadline does not receive reset delivery`() {
        givenUser(
            user(
                authOrigin = UserAuthOrigin.EMAIL_PASSWORD,
                status = UserStatus.DELETED,
                deletionFinalizesAt = now.minusSeconds(1)
            )
        )

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    private fun givenUser(user: User) {
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(user)
    }

    private fun verifyNoDelivery() {
        verify(deliveryService, never()).sendPasswordResetEmail(org.mockito.ArgumentMatchers.anyString())
    }

    private fun user(
        authOrigin: UserAuthOrigin?,
        status: UserStatus = UserStatus.ACTIVE,
        deletionFinalizesAt: OffsetDateTime? = null,
        firebaseUid: String? = "firebase-${UUID.randomUUID()}"
    ): User =
        User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            firebaseUid = firebaseUid,
            authOrigin = authOrigin,
            status = status,
            deletionFinalizesAt = deletionFinalizesAt
        )
}

