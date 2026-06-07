package com.reals.backend.service

import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.domain.StoredObject
import com.reals.backend.service.exception.ObjectStorageException
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.*

@Service
@Profile("dev","prod")
class R2StorageService(
    private val s3Client: S3Client,
    private val properties: S3StorageProperties
) {
    fun uploadProfilePhoto(
        userId: UUID,
        profileId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): StoredObject {
        return try {
            val normalizedContentType = contentType.lowercase()
            val extension = extensionFor(normalizedContentType)
            val key = "profile-photos/$userId/$profileId/$photoId.$extension"

            val request = PutObjectRequest.builder()
                .bucket(properties.bucket)
                .key(key)
                .contentType(normalizedContentType)
                .contentLength(bytes.size.toLong())
                .build()

            s3Client.putObject(request, RequestBody.fromBytes(bytes))

            StoredObject(
                bucket = properties.bucket,
                key = key,
                url = getReadUrl(key),
                contentType = normalizedContentType,
                sizeBytes = bytes.size.toLong()
            )
        } catch (ex: Exception) {
            throw ObjectStorageException("Could not upload profile photo", ex)
        }
    }

    fun delete(key: String) {
        try {
            val request = DeleteObjectRequest.builder()
                .bucket(properties.bucket)
                .key(key)
                .build()

            s3Client.deleteObject(request)
        } catch (ex: Exception) {
            throw ObjectStorageException("Could not delete object from storage: $key", ex)
        }
    }

    fun getReadUrl(key: String): String {
        val publicBaseUrl = properties.publicBaseUrl?.trim().orEmpty()

        return if (publicBaseUrl.isNotBlank()) {
            "${publicBaseUrl.removeSuffix("/")}/$key"
        } else {
            "r2://${properties.bucket}/$key"
        }
    }

    private fun extensionFor(contentType: String): String {
        return when (contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("Unsupported content type: $contentType")
        }
    }
}