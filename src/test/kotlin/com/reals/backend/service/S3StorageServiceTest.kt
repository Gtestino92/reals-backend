package com.reals.backend.service

import com.reals.backend.config.s3.S3CompatibleStorageConfig
import com.reals.backend.config.s3.S3ReadUrlMode
import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.service.exception.ChatAudioStorageException
import com.reals.backend.service.exception.ObjectStorageException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.util.UUID

class S3StorageServiceTest {

    @Test
    fun `upload profile photo returns storage metadata without generating read url`() {
        val s3Client = Mockito.mock(S3Client::class.java)
        val presigner = Mockito.mock(S3Presigner::class.java)
        val service = S3StorageService(
            s3Client = s3Client,
            s3Presigner = presigner,
            properties = storageProperties(readUrlMode = S3ReadUrlMode.PRESIGNED)
        )

        val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val photoId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val storedObject = service.uploadProfilePhoto(
            userId = userId,
            photoId = photoId,
            contentType = "IMAGE/JPEG",
            bytes = byteArrayOf(1, 2, 3)
        )

        assertEquals("reals-profile-photos", storedObject.bucket)
        assertEquals("users/$userId/profile-photos/$photoId.jpg", storedObject.key)
        assertEquals("image/jpeg", storedObject.contentType)
        assertEquals(3, storedObject.sizeBytes)
        Mockito.verify(s3Client).putObject(
            any(PutObjectRequest::class.java),
            any(RequestBody::class.java)
        )
        Mockito.verifyNoInteractions(presigner)
    }

    @Test
    fun `presigned read url uses browser-facing endpoint`() {
        val properties = storageProperties(
            endpoint = "http://minio:9000",
            presignedUrlEndpoint = "http://localhost:9000",
            readUrlMode = S3ReadUrlMode.PRESIGNED
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            val url = service.getReadUrl("users/user-id/profile-photos/photo.png")

            assertTrue(url.startsWith("http://localhost:9000/reals-profile-photos/"))
            assertTrue(url.contains("users/user-id/profile-photos/photo.png"))
            assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
            assertTrue(url.contains("X-Amz-Signature="))
        } finally {
            presigner.close()
        }
    }

    @Test
    fun `presigned audio read url uses persisted bucket`() {
        val properties = storageProperties(
            endpoint = "http://minio:9000",
            presignedUrlEndpoint = "http://localhost:9000",
            readUrlMode = S3ReadUrlMode.PRESIGNED
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            val url = service.getReadUrl(
                bucket = "archived-chat-media",
                key = "chats/chat-id/messages/message-id.m4a"
            )

            assertTrue(url.startsWith("http://localhost:9000/archived-chat-media/"))
            assertTrue(url.contains("chats/chat-id/messages/message-id.m4a"))
        } finally {
            presigner.close()
        }
    }

    @Test
    fun `chat audio upload storage failures use audio-specific exception`() {
        val s3Client = Mockito.mock(S3Client::class.java)
        val service = S3StorageService(
            s3Client = s3Client,
            s3Presigner = Mockito.mock(S3Presigner::class.java),
            properties = storageProperties(readUrlMode = S3ReadUrlMode.PRESIGNED)
        )
        Mockito.doThrow(RuntimeException("storage down"))
            .`when`(s3Client)
            .putObject(
                any(PutObjectRequest::class.java),
                any(RequestBody::class.java)
            )

        assertThrows<ChatAudioStorageException> {
            service.uploadChatAudio(
                chatId = UUID.fromString("00000000-0000-0000-0000-000000000011"),
                messageId = UUID.fromString("00000000-0000-0000-0000-000000000012"),
                contentType = "audio/mp4",
                bytes = byteArrayOf(1, 2, 3)
            )
        }
    }

    @Test
    fun `delete treats missing object as successful cleanup`() {
        val s3Client = Mockito.mock(S3Client::class.java)
        val service = S3StorageService(
            s3Client = s3Client,
            s3Presigner = Mockito.mock(S3Presigner::class.java),
            properties = storageProperties(readUrlMode = S3ReadUrlMode.PRESIGNED)
        )
        Mockito.doThrow(
            S3Exception.builder()
                .statusCode(404)
                .message("object not found")
                .build()
        ).`when`(s3Client).deleteObject(any(DeleteObjectRequest::class.java))

        service.deleteObject(
            bucket = "reals-profile-photos",
            key = "missing.jpg"
        )
    }

    @Test
    fun `public read url uses configured public base url`() {
        val properties = storageProperties(
            publicBaseUrl = "http://localhost:9000/reals-profile-photos/",
            readUrlMode = S3ReadUrlMode.PUBLIC
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            assertEquals(
                "http://localhost:9000/reals-profile-photos/users/user-id/profile-photos/photo.png",
                service.getReadUrl("users/user-id/profile-photos/photo.png")
            )
        } finally {
            presigner.close()
        }
    }

    @Test
    fun `public read url requires public base url`() {
        val properties = storageProperties(
            publicBaseUrl = null,
            readUrlMode = S3ReadUrlMode.PUBLIC
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            assertThrows<ObjectStorageException> {
                service.getReadUrl("users/user-id/profile-photos/photo.png")
            }
        } finally {
            presigner.close()
        }
    }

    @Test
    fun `presigned read url does not require public base url`() {
        val properties = storageProperties(
            publicBaseUrl = null,
            readUrlMode = S3ReadUrlMode.PRESIGNED
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            val url = service.getReadUrl("users/user-id/profile-photos/photo.png")

            assertTrue(url.startsWith("http://localhost:9000/reals-profile-photos/"))
            assertTrue(url.contains("X-Amz-Signature="))
        } finally {
            presigner.close()
        }
    }

    private fun storageProperties(
        endpoint: String = "http://localhost:9000",
        presignedUrlEndpoint: String? = "http://localhost:9000",
        publicBaseUrl: String? = "http://localhost:9000/reals-profile-photos",
        readUrlMode: S3ReadUrlMode
    ): S3StorageProperties =
        S3StorageProperties(
            endpoint = endpoint,
            presignedUrlEndpoint = presignedUrlEndpoint,
            region = "us-east-1",
            bucket = "reals-profile-photos",
            accessKeyId = "test-access-key",
            secretAccessKey = "test-secret-key",
            publicBaseUrl = publicBaseUrl,
            pathStyleAccessEnabled = true,
            signedUrlDurationMinutes = 15,
            readUrlMode = readUrlMode
        )
}
