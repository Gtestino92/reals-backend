package com.reals.backend.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.reals.backend.config.filter.RequestCorrelationIdFilter
import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.domain.UserStatus
import com.reals.backend.repository.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.task.SyncTaskExecutor
import java.time.OffsetDateTime
import java.util.UUID

class PasswordResetServiceTest {

    private val userRepository = mock(UserRepository::class.java)
    private val deliveryService = mock(PasswordResetEmailDeliveryService::class.java)
    private val executor = spy(SyncTaskExecutor())
    private val service = PasswordResetService(userRepository, deliveryService, executor)
    private val now = OffsetDateTime.parse("2026-08-11T12:00:00Z")
    private val logger = LoggerFactory.getLogger(PasswordResetService::class.java) as Logger
    private val previousLevel = logger.level
    private val appender = object : ListAppender<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            event.prepareForDeferredProcessing()
            super.append(event)
        }
    }

    @BeforeEach
    fun captureDiagnostics() {
        logger.level = Level.INFO
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun restoreLogging() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }

    @Test
    fun `active email password user receives reset delivery`() {
        givenUser(user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD, firebaseUid = "private-test-uid"))

        service.requestPasswordReset(" USER@Example.com ", now)

        verify(deliveryService).sendPasswordResetEmail("user@example.com")
        assertEquals("event=password_reset outcome=executor_submitted", appender.list.single().formattedMessage)
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
        assertEquals("event=password_reset outcome=local_ineligible", appender.list.single().formattedMessage)
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
    fun `unknown email does not receive delivery and its diagnostic excludes the client request id`() {
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(null)

        val requestIdKey = RequestCorrelationIdFilter.MDC_KEY
        val clientRequestId = "private-test-token"
        MDC.put(requestIdKey, clientRequestId)
        try {
            service.requestPasswordReset("user@example.com", now)

            verifyNoDelivery()
            val event = appender.list.single()
            assertEquals("event=password_reset outcome=local_ineligible", event.formattedMessage)
            assertFalse(event.mdcPropertyMap.containsKey(requestIdKey))
            assertEquals(clientRequestId, MDC.get(requestIdKey))
        } finally {
            MDC.remove(requestIdKey)
        }
    }

    @Test
    fun `user without firebase uid does not receive reset delivery`() {
        givenUser(user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD, firebaseUid = null))

        service.requestPasswordReset("user@example.com", now)

        verifyNoDelivery()
    }

    @Test
    fun `unknown origin blank firebase uid and deleted account without deadline never submit delivery`() {
        listOf(
            user(authOrigin = null),
            user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD, firebaseUid = " "),
            user(authOrigin = UserAuthOrigin.EMAIL_PASSWORD, status = UserStatus.DELETED)
        ).forEach {
            givenUser(it)
            service.requestPasswordReset("user@example.com", now)
        }

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
        verifyNoInteractions(executor)
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

