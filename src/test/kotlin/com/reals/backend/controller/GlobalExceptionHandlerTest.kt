package com.reals.backend.controller

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.security.access.AccessDeniedException
import java.sql.SQLException

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `generic errors do not expose internal exception messages`() {
        val logger =
            LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val previousLevel = logger.level

        val response = try {
            logger.level = Level.OFF
            handler.handleGeneric(
                RuntimeException("database column secret_token not found")
            )
        } finally {
            logger.level = previousLevel
        }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("INTERNAL_ERROR", response.body?.code)
        assertEquals("Internal Server Error", response.body?.error)
        assertEquals("Unexpected server error", response.body?.message)
    }

    @Test
    fun `generic wrapper with database connection root cause exposes service unavailable response`() {
        val response =
            handler.handleGeneric(
                RuntimeException(
                    "JPA wrapped error",
                    SQLException("connection failure", "08006")
                )
            )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("DATABASE_UNAVAILABLE", response.body?.code)
        assertEquals("Service Unavailable", response.body?.error)
        assertEquals(
            "Database is temporarily unavailable. Please retry later.",
            response.body?.message
        )
    }

    @Test
    fun `access denied errors expose stable error code`() {
        val response =
            handler.handleAccessDenied(
                AccessDeniedException("User cannot access this resource")
            )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("ACCESS_DENIED", response.body?.code)
        assertEquals("Forbidden", response.body?.error)
        assertEquals("User cannot access this resource", response.body?.message)
    }

    @Test
    fun `database unavailable errors expose service unavailable response`() {
        val response =
            handler.handleDatabaseUnavailable(
                CannotGetJdbcConnectionException("database is down")
            )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("DATABASE_UNAVAILABLE", response.body?.code)
        assertEquals("Service Unavailable", response.body?.error)
        assertEquals(
            "Database is temporarily unavailable. Please retry later.",
            response.body?.message
        )
    }

    @Test
    fun `transient lock errors expose retryable conflict response`() {
        val response =
            handler.handleTransientConcurrencyFailure(
                CannotAcquireLockException("row is locked")
            )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("TRANSIENT_CONCURRENCY_CONFLICT", response.body?.code)
        assertEquals("Conflict", response.body?.error)
        assertEquals("Resource is temporarily busy. Please retry.", response.body?.message)
    }

    @Test
    fun `domain not found errors expose stable not found response`() {
        val response =
            handler.handleDomainException(
                DomainNotFoundException(
                    code = DomainErrorCode.PROFILE_NOT_FOUND,
                    message = "Profile not found for current user"
                )
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("PROFILE_NOT_FOUND", response.body?.code)
        assertEquals("Not Found", response.body?.error)
        assertEquals("Profile not found for current user", response.body?.message)
    }

    @Test
    fun `authenticity verification not configured exposes stable conflict response`() {
        val response =
            handler.handleDomainException(
                DomainConflictException(
                    code = DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED,
                    message = "Profile authenticity verification is not configured"
                )
            )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("AUTHENTICITY_VERIFICATION_NOT_CONFIGURED", response.body?.code)
        assertEquals("Conflict", response.body?.error)
        assertEquals("Profile authenticity verification is not configured", response.body?.message)
    }
}
