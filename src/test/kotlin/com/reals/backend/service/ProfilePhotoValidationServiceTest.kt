package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoValidationProperties
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
    fun `valid JPEG is accepted`() {
        assertDoesNotThrow {
            serviceFor().inspectUploadedPhoto("image/jpeg", imageBytes("jpg"))
        }
    }

    @Test
    fun `valid PNG is accepted`() {
        assertDoesNotThrow {
            serviceFor().inspectUploadedPhoto("image/png", imageBytes("png"))
        }
    }

    @Test
    fun `WebP is rejected`() {
        assertInvalid("image/webp", "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray())
    }

    @Test
    fun `GIF is rejected`() {
        assertInvalid("image/gif", "GIF89a".toByteArray())
    }

    @Test
    fun `arbitrary bytes declared JPEG are rejected`() {
        assertInvalid("image/jpeg", byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `arbitrary bytes declared PNG are rejected`() {
        assertInvalid("image/png", byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `JPEG bytes declared PNG are rejected`() {
        assertInvalid("image/png", imageBytes("jpg"))
    }

    @Test
    fun `PNG bytes declared JPEG are rejected`() {
        assertInvalid("image/jpeg", imageBytes("png"))
    }

    @Test
    fun `truncated JPEG is rejected`() {
        assertInvalid("image/jpeg", imageBytes("jpg").take(8).toByteArray())
    }

    @Test
    fun `truncated PNG is rejected`() {
        assertInvalid("image/png", imageBytes("png").take(12).toByteArray())
    }

    @Test
    fun `width over limit is rejected before full decode`() {
        val ex = assertThrows<DomainBadRequestException> {
            serviceFor(ProfilePhotoValidationProperties(maxInputWidth = 1))
                .inspectUploadedPhoto("image/jpeg", imageBytes("jpg", width = 2, height = 1))
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    @Test
    fun `height over limit is rejected before full decode`() {
        val ex = assertThrows<DomainBadRequestException> {
            serviceFor(ProfilePhotoValidationProperties(maxInputHeight = 1))
                .inspectUploadedPhoto("image/jpeg", imageBytes("jpg", width = 1, height = 2))
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    @Test
    fun `pixel count over limit is rejected`() {
        val ex = assertThrows<DomainBadRequestException> {
            serviceFor(ProfilePhotoValidationProperties(maxInputPixels = 3))
                .inspectUploadedPhoto("image/jpeg", imageBytes("jpg", width = 2, height = 2))
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    private fun assertInvalid(
        contentType: String,
        bytes: ByteArray
    ) {
        val ex = assertThrows<DomainBadRequestException> {
            serviceFor().inspectUploadedPhoto(contentType, bytes)
        }

        assertEquals("INVALID_PROFILE_PHOTO", ex.code.name)
    }

    private fun serviceFor(
        properties: ProfilePhotoValidationProperties = ProfilePhotoValidationProperties()
    ): ProfilePhotoValidationService =
        ProfilePhotoValidationService(properties)

    private fun imageBytes(
        format: String,
        width: Int = 2,
        height: Int = 2
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, format, output)
        return output.toByteArray()
    }
}
