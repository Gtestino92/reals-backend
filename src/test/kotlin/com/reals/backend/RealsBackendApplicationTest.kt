package com.reals.backend

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone

class RealsBackendApplicationTest {

    private val originalUserTimezone = System.getProperty("user.timezone")
    private val originalDefaultTimeZone = TimeZone.getDefault()

    @AfterEach
    fun restoreTimeZone() {
        if (originalUserTimezone == null) {
            System.clearProperty("user.timezone")
        } else {
            System.setProperty("user.timezone", originalUserTimezone)
        }
        TimeZone.setDefault(originalDefaultTimeZone)
    }

    @Test
    fun `application startup forces UTC as default JVM timezone`() {
        System.setProperty("user.timezone", "America/Argentina/Buenos_Aires")
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))

        configureApplicationTimeZone()

        assertEquals("UTC", System.getProperty("user.timezone"))
        assertEquals("UTC", TimeZone.getDefault().id)
        assertEquals(ZoneOffset.UTC, OffsetDateTime.now().offset)
    }
}
