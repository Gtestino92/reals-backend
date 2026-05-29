package com.reals.backend.controller

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus

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
        assertEquals("Internal Server Error", response.body?.error)
        assertEquals("Unexpected server error", response.body?.message)
    }
}
