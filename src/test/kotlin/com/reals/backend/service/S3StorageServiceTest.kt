package com.reals.backend.service

import com.reals.backend.config.s3.S3CompatibleStorageConfig
import com.reals.backend.config.s3.S3ReadUrlMode
import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.service.exception.ObjectStorageException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import software.amazon.awssdk.services.s3.S3Client

class S3StorageServiceTest {

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
