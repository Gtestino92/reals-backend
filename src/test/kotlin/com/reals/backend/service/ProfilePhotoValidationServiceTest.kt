package com.reals.backend.service

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.service.exception.DomainBadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ProfilePhotoValidationServiceTest {

    @Test
    fun `uploaded photo is validated after technical image validation`() {
        val service = serviceFor("test")

        val result = service.validateUploadedPhoto(
            contentType = "image/jpeg",
            bytes = jpegBytes()
        )

        assertEquals(PhotoValidationStatus.VALIDATED, result.status)
        assertEquals(true, result.isPersonPhoto)
        assertEquals(true, result.isFullBody)
    }

    @Test
    fun `uploaded photo rejects invalid dimensions`() {
        val service = serviceFor(
            profile = "test",
            properties = ProfilePhotoStorageProperties(maxWidthPixels = 0)
        )

        val ex = assertThrows(DomainBadRequestException::class.java) {
            service.validateUploadedPhoto(
                contentType = "image/jpeg",
                bytes = jpegBytes()
            )
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    @Test
    fun `uploaded photo remains semantically pending in prod after technical image validation`() {
        val service = serviceFor("prod")

        val result = service.validateUploadedPhoto(
            contentType = "image/jpeg",
            bytes = jpegBytes()
        )

        assertEquals(PhotoValidationStatus.PENDING, result.status)
        assertEquals(false, result.isPersonPhoto)
        assertEquals(false, result.isFullBody)
    }

    @Test
    fun `uploaded photo still runs technical validation in prod`() {
        val service = serviceFor(
            profile = "prod",
            properties = ProfilePhotoStorageProperties(maxWidthPixels = 0)
        )

        val ex = assertThrows(DomainBadRequestException::class.java) {
            service.validateUploadedPhoto(
                contentType = "image/jpeg",
                bytes = jpegBytes()
            )
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    private fun serviceFor(
        profile: String,
        properties: ProfilePhotoStorageProperties = ProfilePhotoStorageProperties()
    ): ProfilePhotoValidationService =
        ProfilePhotoValidationService(
            properties = properties,
            environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles(profile)
        )

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }
}
