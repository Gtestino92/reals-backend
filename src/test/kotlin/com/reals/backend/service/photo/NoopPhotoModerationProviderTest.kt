package com.reals.backend.service.photo

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.PhotoModerationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class NoopPhotoModerationProviderTest {

    @Test
    fun `none provider preserves approved result outside prod`() {
        val provider = NoopPhotoModerationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("test")
        )

        val result = provider.moderate(request())

        assertEquals(PhotoModerationStatus.APPROVED, result.status)
        assertEquals("none", result.provider)
    }

    @Test
    fun `none provider returns needs review in prod`() {
        val provider = NoopPhotoModerationProvider(
            EnvironmentExposurePolicy.forActiveProfiles("prod")
        )

        val result = provider.moderate(request())

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
        assertEquals("none", result.provider)
        assertNotEquals(PhotoModerationStatus.APPROVED, result.status)
    }

    private fun request(): PhotoModerationRequest =
        PhotoModerationRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )
}
