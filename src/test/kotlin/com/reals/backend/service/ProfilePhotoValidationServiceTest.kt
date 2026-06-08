package com.reals.backend.service

import com.reals.backend.domain.PhotoValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class ProfilePhotoValidationServiceTest {

    @Test
    fun `uploaded photo is validated in local firebase profile`() {
        val service = ProfilePhotoValidationService(
            MockEnvironment().apply {
                setActiveProfiles("local-firebase")
            }
        )

        val result = service.validateUploadedPhoto(
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )

        assertEquals(PhotoValidationStatus.VALIDATED, result.status)
    }

    @Test
    fun `uploaded photo remains pending outside local and test profiles`() {
        val service = ProfilePhotoValidationService(
            MockEnvironment().apply {
                setActiveProfiles("prod")
            }
        )

        val result = service.validateUploadedPhoto(
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )

        assertEquals(PhotoValidationStatus.PENDING, result.status)
    }
}
