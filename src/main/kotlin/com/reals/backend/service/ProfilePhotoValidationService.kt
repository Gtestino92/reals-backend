package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoValidationProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader

data class ProfilePhotoInspection(
    val format: ProfilePhotoFormat,
    val contentType: String,
    val width: Int,
    val height: Int
) {
    val pixelCount: Long = width.toLong() * height.toLong()
}

enum class ProfilePhotoFormat(
    val contentType: String
) {
    JPEG("image/jpeg"),
    PNG("image/png")
}

@Service
class ProfilePhotoValidationService(
    private val properties: ProfilePhotoValidationProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun inspectUploadedPhoto(
        contentType: String,
        bytes: ByteArray
    ): ProfilePhotoInspection {
        val declaredContentType = contentType.lowercase()
        if (declaredContentType !in properties.allowedContentTypes) {
            throw invalidProfilePhoto("Unsupported photo content type")
        }

        val signatureFormat = signatureFormat(bytes)
            ?: throw invalidProfilePhoto("Photo file signature is not supported")

        if (signatureFormat.contentType != declaredContentType) {
            throw invalidProfilePhoto("Photo content does not match declared content type")
        }

        val inspection = inspectWithImageReader(bytes, signatureFormat)
        validateDimensions(inspection)
        return inspection
    }

    private fun inspectWithImageReader(
        bytes: ByteArray,
        expectedFormat: ProfilePhotoFormat
    ): ProfilePhotoInspection {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw invalidProfilePhoto("Photo file could not be decoded as an image")

        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) {
                throw invalidProfilePhoto("No compatible image reader is available")
            }

            val reader = readers.next()
            try {
                reader.input = stream
                val actualFormat = reader.safeFormat()
                if (actualFormat != expectedFormat) {
                    throw invalidProfilePhoto("Photo format is not supported")
                }

                rejectMultipleImagesIfKnown(reader)

                return ProfilePhotoInspection(
                    format = actualFormat,
                    contentType = actualFormat.contentType,
                    width = reader.getWidth(0),
                    height = reader.getHeight(0)
                )
            } catch (ex: DomainBadRequestException) {
                throw ex
            } catch (ex: Exception) {
                log.debug("Profile photo metadata inspection failed: {}", ex.javaClass.simpleName)
                throw invalidProfilePhoto("Photo file could not be decoded as an image")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun rejectMultipleImagesIfKnown(reader: ImageReader) {
        val imageCount = try {
            reader.getNumImages(true)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: Exception) {
            return
        }

        if (imageCount > 1) {
            throw invalidProfilePhoto("Profile photos must contain a single image")
        }
    }

    private fun validateDimensions(inspection: ProfilePhotoInspection) {
        val width = inspection.width
        val height = inspection.height
        val pixels = width.toLong() * height.toLong()

        if (
            width <= 0 ||
            height <= 0 ||
            width > properties.maxInputWidth ||
            height > properties.maxInputHeight ||
            pixels > properties.maxInputPixels
        ) {
            throw invalidProfilePhoto("Photo dimensions are invalid")
        }
    }

    private fun ImageReader.safeFormat(): ProfilePhotoFormat {
        val formatName = formatName.lowercase()
        return when (formatName) {
            "jpeg", "jpg" -> ProfilePhotoFormat.JPEG
            "png" -> ProfilePhotoFormat.PNG
            else -> throw invalidProfilePhoto("Photo format is not supported")
        }
    }

    private fun signatureFormat(bytes: ByteArray): ProfilePhotoFormat? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> ProfilePhotoFormat.JPEG

            bytes.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> ProfilePhotoFormat.PNG

            else -> null
        }

    private fun invalidProfilePhoto(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.INVALID_PROFILE_PHOTO,
            message = message
        )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A
        )
    }
}
