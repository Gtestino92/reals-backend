package com.reals.backend.config.s3

import com.reals.backend.service.S3StorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client

class S3ClientConfigurationFactoryTest {
    private val factory = S3ClientConfigurationFactory()

    @Test
    fun `static mode with key and secret uses basic static credentials`() {
        val configuration = factory.clientConfiguration(properties())

        assertTrue(configuration.credentialsProvider is StaticCredentialsProvider)
        assertTrue(configuration.credentialsProvider.resolveCredentials() is AwsBasicCredentials)
    }

    @Test
    fun `static mode with session token uses session static credentials`() {
        val configuration = factory.clientConfiguration(
            properties(sessionToken = "session-token")
        )

        assertTrue(configuration.credentialsProvider is StaticCredentialsProvider)
        assertTrue(configuration.credentialsProvider.resolveCredentials() is AwsSessionCredentials)
    }

    @Test
    fun `default chain mode selects AWS default credentials provider without resolving credentials`() {
        val configuration = factory.clientConfiguration(
            properties(
                credentialsMode = S3CredentialsMode.DEFAULT_CHAIN,
                endpoint = null,
                accessKeyId = null,
                secretAccessKey = null,
                pathStyleAccessEnabled = false
            )
        )

        assertTrue(configuration.credentialsProvider is DefaultCredentialsProvider)
    }

    @Test
    fun `partial static credentials fail fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            factory.clientConfiguration(
                properties(secretAccessKey = null)
            )
        }

        assertEquals(
            "storage.s3.secret-access-key is required when storage.s3.credentials-mode=STATIC",
            exception.message
        )
    }

    @Test
    fun `session token without complete static credentials fails fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            factory.clientConfiguration(
                properties(
                    accessKeyId = null,
                    secretAccessKey = null,
                    sessionToken = "session-token"
                )
            )
        }

        assertEquals(
            "storage.s3.session-token requires storage.s3.access-key-id and storage.s3.secret-access-key",
            exception.message
        )
    }

    @Test
    fun `default chain mode rejects configured static credentials`() {
        val exception = assertThrows<IllegalArgumentException> {
            factory.clientConfiguration(
                properties(credentialsMode = S3CredentialsMode.DEFAULT_CHAIN)
            )
        }

        assertEquals(
            "storage.s3.access-key-id, storage.s3.secret-access-key, and storage.s3.session-token must not be configured when storage.s3.credentials-mode=DEFAULT_CHAIN",
            exception.message
        )
    }

    @Test
    fun `explicit main endpoint is applied to S3 client configuration`() {
        val configuration = factory.clientConfiguration(
            properties(endpoint = " http://minio:9000 ")
        )

        assertEquals("http://minio:9000", configuration.endpointOverride.toString())
    }

    @Test
    fun `explicit presigned endpoint takes precedence for S3 presigner configuration`() {
        val configuration = factory.presignerConfiguration(
            properties(
                endpoint = "http://minio:9000",
                presignedUrlEndpoint = " http://localhost:9000 "
            )
        )

        assertEquals("http://localhost:9000", configuration.endpointOverride.toString())
    }

    @Test
    fun `missing presigned endpoint falls back to main endpoint for S3 presigner configuration`() {
        val configuration = factory.presignerConfiguration(
            properties(
                endpoint = "http://minio:9000",
                presignedUrlEndpoint = " "
            )
        )

        assertEquals("http://minio:9000", configuration.endpointOverride.toString())
    }

    @Test
    fun `native S3 client and presigner configurations omit endpoint override`() {
        val properties = properties(
            endpoint = null,
            presignedUrlEndpoint = null,
            pathStyleAccessEnabled = false
        )

        assertNull(factory.clientConfiguration(properties).endpointOverride)
        assertNull(factory.presignerConfiguration(properties).endpointOverride)
    }

    @Test
    fun `native S3 presigned URL uses AWS regional hostname with local static credentials`() {
        val properties = properties(
            endpoint = null,
            presignedUrlEndpoint = null,
            region = "eu-west-1",
            pathStyleAccessEnabled = false
        )
        val presigner = S3CompatibleStorageConfig().s3Presigner(properties)

        try {
            val service = S3StorageService(
                s3Client = Mockito.mock(S3Client::class.java),
                s3Presigner = presigner,
                properties = properties
            )

            val url = service.getReadUrl("users/user-id/profile-photos/photo.png")

            assertTrue(url.startsWith("https://"))
            assertTrue(url.contains("s3.eu-west-1.amazonaws.com"))
            assertTrue(url.contains("users/user-id/profile-photos/photo.png"))
            assertTrue(url.contains("X-Amz-Signature="))
        } finally {
            presigner.close()
        }
    }

    @Test
    fun `auto region remains accepted with explicit compatible endpoint`() {
        val configuration = factory.clientConfiguration(
            properties(
                endpoint = "https://example.r2.cloudflarestorage.com",
                region = "auto"
            )
        )

        assertEquals("auto", configuration.region.id())
    }

    @Test
    fun `auto region is rejected when endpoint is absent for native S3`() {
        val exception = assertThrows<IllegalArgumentException> {
            factory.clientConfiguration(
                properties(
                    endpoint = null,
                    region = "auto"
                )
            )
        }

        assertEquals(
            "storage.s3.region must be a real AWS region when storage.s3.endpoint is not configured",
            exception.message
        )
    }

    @Test
    fun `path-style configuration is preserved`() {
        assertEquals(
            true,
            factory.clientConfiguration(properties(pathStyleAccessEnabled = true))
                .serviceConfiguration
                .pathStyleAccessEnabled()
        )
        assertEquals(
            false,
            factory.clientConfiguration(properties(pathStyleAccessEnabled = false))
                .serviceConfiguration
                .pathStyleAccessEnabled()
        )
    }

    @Test
    fun `blank static credentials fail after trimming`() {
        val exception = assertThrows<IllegalArgumentException> {
            factory.clientConfiguration(
                properties(accessKeyId = " ")
            )
        }

        assertEquals(
            "storage.s3.access-key-id is required when storage.s3.credentials-mode=STATIC",
            exception.message
        )
    }

    private fun properties(
        credentialsMode: S3CredentialsMode = S3CredentialsMode.STATIC,
        endpoint: String? = "http://localhost:9000",
        presignedUrlEndpoint: String? = null,
        region: String = "us-east-1",
        bucket: String = "reals-media",
        accessKeyId: String? = "test-access-key",
        secretAccessKey: String? = "test-secret-key",
        sessionToken: String? = null,
        publicBaseUrl: String? = null,
        pathStyleAccessEnabled: Boolean = true,
        readUrlMode: S3ReadUrlMode = S3ReadUrlMode.PRESIGNED
    ): S3StorageProperties =
        S3StorageProperties(
            credentialsMode = credentialsMode,
            endpoint = endpoint,
            presignedUrlEndpoint = presignedUrlEndpoint,
            region = region,
            bucket = bucket,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            publicBaseUrl = publicBaseUrl,
            pathStyleAccessEnabled = pathStyleAccessEnabled,
            signedUrlDurationMinutes = 15,
            readUrlMode = readUrlMode
        )
}
