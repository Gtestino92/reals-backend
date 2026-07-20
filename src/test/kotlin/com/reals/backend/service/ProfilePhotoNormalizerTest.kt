package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoNormalizationProperties
import com.reals.backend.config.s3.ProfilePhotoValidationProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ProfilePhotoNormalizerTest {

    @Test
    fun `JPEG output is canonical image jpeg and decodable`() {
        val result = normalizer().normalize("image/jpeg", imageBytes("jpg", 10, 8))

        assertEquals("image/jpeg", result.contentType)
        assertNotNull(ImageIO.read(ByteArrayInputStream(result.bytes)))
        assertTrue(result.bytes[0] == 0xFF.toByte() && result.bytes[1] == 0xD8.toByte())
    }

    @Test
    fun `PNG input becomes JPEG output`() {
        val source = imageBytes("png", 10, 8)
        val result = normalizer().normalize("image/png", source)

        assertEquals("image/jpeg", result.contentType)
        assertNotEquals(source.toList(), result.bytes.toList())
    }

    @Test
    fun `aspect ratio is preserved within maximum output dimension`() {
        val result = normalizer(
            normalizationProperties = ProfilePhotoNormalizationProperties(maxOutputDimension = 50)
        ).normalize("image/jpeg", imageBytes("jpg", 200, 100))

        assertEquals(50, result.width)
        assertEquals(25, result.height)
    }

    @Test
    fun `transparent PNG uses deterministic neutral background`() {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)

        val result = normalizer().normalize("image/png", output.toByteArray())
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        val pixel = Color(decoded.getRGB(0, 0))

        assertTrue(pixel.red in 235..250)
        assertTrue(pixel.green in 235..250)
        assertTrue(pixel.blue in 235..250)
    }

    @Test
    fun `known injected marker is absent from output`() {
        val marker = "GPSSECRET-XMP-IPTC".toByteArray()
        val source = imageBytes("jpg", 10, 8) + marker

        val result = normalizer().normalize("image/jpeg", source)

        assertFalse(String(result.bytes).contains("GPSSECRET-XMP-IPTC"))
    }

    private fun normalizer(
        validationProperties: ProfilePhotoValidationProperties = ProfilePhotoValidationProperties(),
        normalizationProperties: ProfilePhotoNormalizationProperties = ProfilePhotoNormalizationProperties()
    ): ProfilePhotoNormalizer =
        ProfilePhotoNormalizer(
            validationService = ProfilePhotoValidationService(validationProperties),
            properties = normalizationProperties
        )

    private fun imageBytes(
        format: String,
        width: Int,
        height: Int
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()

        val output = ByteArrayOutputStream()
        ImageIO.write(image, format, output)
        return output.toByteArray()
    }
}
