package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoUploadProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class ProfilePhotoUploadGuardTest {

    @Test
    fun `two simultaneous pipelines enter and third is rejected when maximum is two`() {
        val guard = ProfilePhotoUploadGuard(
            ProfilePhotoUploadProperties(maxConcurrent = 2, permitWaitDuration = Duration.ZERO)
        )

        guard.withPermit {
            guard.withPermit {
                val exception = assertThrows(ProfilePhotoUploadBusyException::class.java) {
                    guard.withPermit { "not reached" }
                }
                assertEquals(1, exception.retryAfterSeconds)
            }
        }
    }

    @Test
    fun `permit is released after success`() {
        val guard = ProfilePhotoUploadGuard(
            ProfilePhotoUploadProperties(maxConcurrent = 1, permitWaitDuration = Duration.ZERO)
        )

        assertEquals("first", guard.withPermit { "first" })
        assertEquals("second", guard.withPermit { "second" })
    }

    @Test
    fun `permit is released after failure`() {
        val guard = ProfilePhotoUploadGuard(
            ProfilePhotoUploadProperties(maxConcurrent = 1, permitWaitDuration = Duration.ZERO)
        )

        assertThrows(IllegalStateException::class.java) {
            guard.withPermit { error("validation failed") }
        }

        assertEquals("recovered", guard.withPermit { "recovered" })
    }
}
