package com.reals.backend.service

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.reals.backend.config.s3.ProfilePhotoNormalizationProperties
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

data class NormalizedProfilePhoto(
    val bytes: ByteArray,
    val contentType: String,
    val width: Int,
    val height: Int
)

@Service
class ProfilePhotoNormalizer(
    private val validationService: ProfilePhotoValidationService,
    private val properties: ProfilePhotoNormalizationProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun normalize(
        contentType: String,
        bytes: ByteArray
    ): NormalizedProfilePhoto {
        val inspection = validationService.inspectUploadedPhoto(contentType, bytes)
        val decoded = decode(bytes, inspection)
        val oriented = applyExifOrientation(decoded, exifOrientation(bytes))
        if (oriented !== decoded) {
            decoded.flush()
        }

        val targetDimensions = targetDimensions(oriented.width, oriented.height)
        val normalizedImage = BufferedImage(
            targetDimensions.width,
            targetDimensions.height,
            BufferedImage.TYPE_INT_RGB
        )

        val graphics = normalizedImage.createGraphics()
        try {
            graphics.color = TRANSPARENCY_BACKGROUND
            graphics.fillRect(0, 0, normalizedImage.width, normalizedImage.height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(oriented, 0, 0, normalizedImage.width, normalizedImage.height, null)
        } finally {
            graphics.dispose()
            oriented.flush()
        }

        return NormalizedProfilePhoto(
            bytes = encodeJpeg(normalizedImage),
            contentType = NORMALIZED_CONTENT_TYPE,
            width = normalizedImage.width,
            height = normalizedImage.height
        )
    }

    private fun decode(
        bytes: ByteArray,
        inspection: ProfilePhotoInspection
    ): BufferedImage {
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
                val param = reader.defaultReadParam
                val subsampling = sourceSubsampling(inspection)
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0)
                }
                return reader.read(0, param)
                    ?: throw invalidProfilePhoto("Photo file could not be decoded as an image")
            } catch (ex: DomainBadRequestException) {
                throw ex
            } catch (ex: Exception) {
                log.debug("Profile photo decode failed: {}", ex.javaClass.simpleName)
                throw invalidProfilePhoto("Photo file could not be decoded as an image")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun sourceSubsampling(inspection: ProfilePhotoInspection): Int {
        val largestDimension = max(inspection.width, inspection.height)
        return max(1, largestDimension / properties.maxOutputDimension)
    }

    private fun targetDimensions(
        width: Int,
        height: Int
    ): Dimensions {
        val largestDimension = max(width, height)
        if (largestDimension <= properties.maxOutputDimension) {
            return Dimensions(width, height)
        }

        val scale = properties.maxOutputDimension.toDouble() / largestDimension.toDouble()
        return Dimensions(
            width = max(1, (width * scale).roundToInt()),
            height = max(1, (height * scale).roundToInt())
        )
    }

    private fun exifOrientation(bytes: ByteArray): Int =
        try {
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
            metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                ?: ORIENTATION_NORMAL
        } catch (ex: Exception) {
            log.debug("Profile photo EXIF orientation read failed: {}", ex.javaClass.simpleName)
            ORIENTATION_NORMAL
        }

    private fun applyExifOrientation(
        source: BufferedImage,
        orientation: Int
    ): BufferedImage {
        if (orientation == ORIENTATION_NORMAL) {
            return source
        }

        val transform = AffineTransform()
        val targetWidth: Int
        val targetHeight: Int

        when (orientation) {
            2 -> {
                targetWidth = source.width
                targetHeight = source.height
                transform.scale(-1.0, 1.0)
                transform.translate(-source.width.toDouble(), 0.0)
            }

            3 -> {
                targetWidth = source.width
                targetHeight = source.height
                transform.translate(source.width.toDouble(), source.height.toDouble())
                transform.rotate(Math.PI)
            }

            4 -> {
                targetWidth = source.width
                targetHeight = source.height
                transform.scale(1.0, -1.0)
                transform.translate(0.0, -source.height.toDouble())
            }

            5 -> {
                targetWidth = source.height
                targetHeight = source.width
                transform.rotate(Math.PI / 2)
                transform.scale(1.0, -1.0)
            }

            6 -> {
                targetWidth = source.height
                targetHeight = source.width
                transform.translate(source.height.toDouble(), 0.0)
                transform.rotate(Math.PI / 2)
            }

            7 -> {
                targetWidth = source.height
                targetHeight = source.width
                transform.scale(-1.0, 1.0)
                transform.translate(-source.height.toDouble(), 0.0)
                transform.translate(0.0, source.width.toDouble())
                transform.rotate(3 * Math.PI / 2)
            }

            8 -> {
                targetWidth = source.height
                targetHeight = source.width
                transform.translate(0.0, source.width.toDouble())
                transform.rotate(3 * Math.PI / 2)
            }

            else -> return source
        }

        val destination = BufferedImage(targetWidth, targetHeight, source.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)
        AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC).filter(source, destination)
        return destination
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: throw invalidProfilePhoto("No compatible JPEG writer is available")

        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            try {
                val params = writer.defaultWriteParam
                if (params.canWriteCompressed()) {
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = properties.jpegQuality
                }
                writer.write(null, IIOImage(image, null, null), params)
            } finally {
                writer.dispose()
                image.flush()
            }
        }
        return output.toByteArray()
    }

    private fun invalidProfilePhoto(message: String): DomainBadRequestException =
        DomainBadRequestException(
            code = DomainErrorCode.INVALID_PROFILE_PHOTO,
            message = message
        )

    private data class Dimensions(
        val width: Int,
        val height: Int
    )

    companion object {
        const val NORMALIZED_CONTENT_TYPE = "image/jpeg"
        val TRANSPARENCY_BACKGROUND: Color = Color(0xF2, 0xF2, 0xF2)
        private const val ORIENTATION_NORMAL = 1
    }
}
