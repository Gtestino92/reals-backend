package com.reals.backend.service.photo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class NoopProfilePhotoAnalysisProviderTest {

    @Test
    fun `none provider returns explicit not-configured outcome`() {
        val provider = NoopProfilePhotoAnalysisProvider()

        val result = provider.analyze(request())

        assertInstanceOf(ProfilePhotoAnalysisProviderResult.NotConfigured::class.java, result)
        assertEquals("none", result.provider)
    }

    private fun request(): ProfilePhotoAnalysisRequest =
        ProfilePhotoAnalysisRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = "image/jpeg",
            bytes = byteArrayOf(1)
        )
}
