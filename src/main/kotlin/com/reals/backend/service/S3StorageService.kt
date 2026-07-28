package com.reals.backend.service

import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.config.s3.S3ReadUrlMode
import com.reals.backend.domain.StoredObject
import com.reals.backend.service.exception.ObjectStorageException
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.util.*

@Service
class S3StorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: S3StorageProperties
) {
    fun uploadProfilePhoto(
        userId: UUID,
        photoId: UUID,
        contentType: String,
        bytes: ByteArray
    ): StoredObject {
        return try {
            val normalizedContentType = contentType.lowercase()
            val key = profilePhotoObjectKey(
                userId = userId,
                objectId = photoId,
                contentType = normalizedContentType
            )
            putObject(
                key = key,
                contentType = normalizedContentType,
                bytes = bytes
            )
        } catch (ex: Exception) {
            throw ObjectStorageException("Could not upload profile photo", ex)
        }
    }

    fun uploadChatAudio(
        chatId: UUID,
        messageId: UUID,
        contentType: String,
        bytes: ByteArray
    ): StoredObject {
        return try {
            putObject(
                key = chatAudioObjectKey(chatId = chatId, messageId = messageId),
                contentType = contentType.lowercase(),
                bytes = bytes
            )
        } catch (ex: Exception) {
            throw ObjectStorageException("Could not upload chat audio", ex)
        }
    }

    fun putObject(
        key: String,
        contentType: String,
        bytes: ByteArray
    ): StoredObject {
        val normalizedContentType = contentType.lowercase()
        val request = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .contentType(normalizedContentType)
            .contentLength(bytes.size.toLong())
            .build()

        s3Client.putObject(request, RequestBody.fromBytes(bytes))

        return StoredObject(
            bucket = properties.bucket,
            key = key,
            contentType = normalizedContentType,
            sizeBytes = bytes.size.toLong()
        )
    }

    fun delete(key: String) {
        deleteObject(
            bucket = properties.bucket,
            key = key
        )
    }

    fun deleteObject(
        bucket: String,
        key: String
    ) {
        try {
            val request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()

            s3Client.deleteObject(request)
        } catch (_: NoSuchKeyException) {
            return
        } catch (ex: S3Exception) {
            if (ex.statusCode() == 404) {
                return
            }
            throw ObjectStorageException("Could not delete object from storage", ex)
        } catch (ex: Exception) {
            throw ObjectStorageException("Could not delete object from storage", ex)
        }
    }

    fun profilePhotoObjectKey(
        userId: UUID,
        objectId: UUID,
        contentType: String
    ): String {
        val extension = extensionFor(contentType.lowercase())
        return "users/$userId/profile-photos/$objectId.$extension"
    }

    fun profilePhotoBucket(): String = properties.bucket

    fun chatAudioObjectKey(
        chatId: UUID,
        messageId: UUID
    ): String = "chats/$chatId/messages/$messageId.m4a"

    fun mediaBucket(): String = properties.bucket

    fun getReadUrl(key: String): String {
        return when (properties.readUrlMode) {
            S3ReadUrlMode.PUBLIC -> publicReadUrl(key)
            S3ReadUrlMode.PRESIGNED -> presignedReadUrl(key)
        }
    }

    private fun publicReadUrl(key: String): String {
        val publicBaseUrl = properties.publicBaseUrl
            ?.trim()
            .orEmpty()

        if (publicBaseUrl.isBlank()) {
            throw ObjectStorageException("storage.s3.public-base-url is required when read-url-mode is PUBLIC")
        }

        return "${publicBaseUrl.removeSuffix("/")}/$key"
    }

    private fun presignedReadUrl(key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(properties.signedUrlDurationMinutes))
            .getObjectRequest(getObjectRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest)
            .url()
            .toString()
    }

    private fun extensionFor(contentType: String): String {
        return when (contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> throw IllegalArgumentException("Unsupported content type: $contentType")
        }
    }
}
