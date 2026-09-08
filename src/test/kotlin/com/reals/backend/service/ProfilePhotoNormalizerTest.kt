package com.reals.backend.service

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
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
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

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

    @Test
    fun `EXIF orientation 6 rotates output ninety degrees clockwise and strips orientation metadata`() {
        val result = normalizer().normalize(
            "image/jpeg",
            jpegWithExifOrientation(orientation = 6)
        )
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))

        assertEquals(20, result.width)
        assertEquals(30, result.height)
        assertColorNear(BLUE, decoded.getRGB(4, 4))
        assertColorNear(RED, decoded.getRGB(15, 4))
        assertColorNear(YELLOW, decoded.getRGB(4, 25))
        assertColorNear(GREEN, decoded.getRGB(15, 25))
        assertNoOrientationMetadata(result.bytes)
    }

    @Test
    fun `EXIF orientation 8 rotates output two hundred seventy degrees clockwise and strips orientation metadata`() {
        val result = normalizer().normalize(
            "image/jpeg",
            jpegWithExifOrientation(orientation = 8)
        )
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))

        assertEquals(20, result.width)
        assertEquals(30, result.height)
        assertColorNear(GREEN, decoded.getRGB(4, 4))
        assertColorNear(YELLOW, decoded.getRGB(15, 4))
        assertColorNear(RED, decoded.getRGB(4, 25))
        assertColorNear(BLUE, decoded.getRGB(15, 25))
        assertNoOrientationMetadata(result.bytes)
    }

    @Test
    fun `EXIF orientation 5 mirrors diagonally and strips orientation metadata`() {
        val result = normalizer().normalize(
            "image/jpeg",
            jpegWithExifOrientation(orientation = 5)
        )
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))

        assertEquals(20, result.width)
        assertEquals(30, result.height)
        assertColorNear(RED, decoded.getRGB(4, 4))
        assertColorNear(BLUE, decoded.getRGB(15, 4))
        assertColorNear(GREEN, decoded.getRGB(4, 25))
        assertColorNear(YELLOW, decoded.getRGB(15, 25))
        assertNoOrientationMetadata(result.bytes)
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

    private fun jpegWithExifOrientation(orientation: Int): ByteArray =
        insertExifOrientation(
            jpegBytes(asymmetricImage()),
            orientation = orientation
        )

    private fun asymmetricImage(): BufferedImage {
        val image = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = RED
        graphics.fillRect(0, 0, 15, 10)
        graphics.color = GREEN
        graphics.fillRect(15, 0, 15, 10)
        graphics.color = BLUE
        graphics.fillRect(0, 10, 15, 10)
        graphics.color = YELLOW
        graphics.fillRect(15, 10, 15, 10)
        graphics.dispose()
        return image
    }

    private fun jpegBytes(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            val params = writer.defaultWriteParam
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = 1.0f
            writer.write(null, IIOImage(image, null, null), params)
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun insertExifOrientation(
        jpeg: ByteArray,
        orientation: Int
    ): ByteArray {
        require(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val app1Payload = exifOrientationPayload(orientation)
        val app1Segment = ByteArrayOutputStream()
        app1Segment.write(0xFF)
        app1Segment.write(0xE1)
        val segmentLength = app1Payload.size + 2
        app1Segment.write((segmentLength ushr 8) and 0xFF)
        app1Segment.write(segmentLength and 0xFF)
        app1Segment.write(app1Payload)

        val output = ByteArrayOutputStream()
        output.write(jpeg, 0, 2)
        output.write(app1Segment.toByteArray())
        output.write(jpeg, 2, jpeg.size - 2)
        return output.toByteArray()
    }

    private fun exifOrientationPayload(orientation: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00))
        output.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
        output.write(byteArrayOf(0x00, 0x00, 0x00, 0x08))
        output.write(byteArrayOf(0x00, 0x01))
        output.write(byteArrayOf(0x01, 0x12))
        output.write(byteArrayOf(0x00, 0x03))
        output.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        output.write(byteArrayOf(0x00, orientation.toByte(), 0x00, 0x00))
        output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        return output.toByteArray()
    }

    private fun assertColorNear(
        expected: Color,
        actualRgb: Int
    ) {
        val actual = Color(actualRgb)
        assertTrue(
            kotlin.math.abs(expected.red - actual.red) <= 30 &&
                kotlin.math.abs(expected.green - actual.green) <= 30 &&
                kotlin.math.abs(expected.blue - actual.blue) <= 30,
            "Expected color near $expected but was $actual"
        )
    }

    private fun assertNoOrientationMetadata(bytes: ByteArray) {
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        val orientation = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
        assertEquals(null, orientation)
    }

    private companion object {
        val RED = Color(220, 20, 20)
        val GREEN = Color(20, 180, 20)
        val BLUE = Color(20, 20, 220)
        val YELLOW = Color(230, 220, 20)
    }
}
