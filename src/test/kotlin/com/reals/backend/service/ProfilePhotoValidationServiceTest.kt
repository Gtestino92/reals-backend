package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoStorageProperties
import com.reals.backend.service.exception.DomainBadRequestException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ProfilePhotoValidationServiceTest {

    @Test
    fun `uploaded photo accepts decodable image with valid dimensions`() {
        val service = serviceFor()

        assertDoesNotThrow {
            service.validateUploadedPhoto(
                contentType = "image/jpeg",
                bytes = jpegBytes()
            )
        }
    }

    @Test
    fun `uploaded photo rejects invalid dimensions`() {
        val service = serviceFor(
            properties = ProfilePhotoStorageProperties(maxWidthPixels = 0)
        )

        val ex = assertThrows<DomainBadRequestException> {
            service.validateUploadedPhoto(
                contentType = "image/jpeg",
                bytes = jpegBytes()
            )
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    @Test
    fun `uploaded photo skips JVM decode for webp compatibility`() {
        val service = serviceFor(
            properties = ProfilePhotoStorageProperties(maxWidthPixels = 0)
        )

        assertDoesNotThrow {
            service.validateUploadedPhoto(
                contentType = "image/webp",
                bytes = byteArrayOf(1, 2, 3)
            )
        }
    }

    private fun serviceFor(
        properties: ProfilePhotoStorageProperties = ProfilePhotoStorageProperties()
    ): ProfilePhotoValidationService =
        ProfilePhotoValidationService(
            properties = properties
        )

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }
}
