package com.reals.backend.integration.storage

import com.reals.backend.config.s3.S3CompatibleStorageConfig
import com.reals.backend.config.s3.S3StorageProperties
import com.reals.backend.service.S3StorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.MetadataDirective
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Testcontainers
class PrivateMediaMinioIT {
    companion object {
        private val username = UUID.randomUUID().toString()
        private val password = UUID.randomUUID().toString()

        @Container
        @JvmStatic
        val minio = GenericContainer<Nothing>("minio/minio:RELEASE.2025-09-07T16-13-09Z").apply {
            addEnv("MINIO_ROOT_USER", username)
            addEnv("MINIO_ROOT_PASSWORD", password)
            setCommand("server", "/data")
            addExposedPort(9000)
            setWaitStrategy(Wait.forHttp("/minio/health/ready"))
        }
    }

    private val http = HttpClient.newHttpClient()
    private val bucket = "private-media-test"
    private val bytes = byteArrayOf(1, 3, 2, 4)
    private val properties get() = S3StorageProperties(
        bucket = bucket, endpoint = "http://${minio.host}:${minio.getMappedPort(9000)}",
        accessKeyId = username, secretAccessKey = password
    )

    @Test
    fun `private audio carries stored policy through signed reads`() {
        val config = S3CompatibleStorageConfig()
        config.s3Client(properties).use { client ->
            config.s3Presigner(properties).use { signer ->
                client.createBucket { it.bucket(bucket) }
                val storage = S3StorageService(client, signer, properties)
                val audio = storage.uploadChatAudio(UUID.randomUUID(), UUID.randomUUID(), "AUDIO/MP4", bytes)
                val url = URI(storage.getReadUrl(audio.bucket, audio.key))
                assertEquals(403, get(URI(url.toString().substringBefore('?'))).statusCode())
                val head = client.headObject { it.bucket(bucket).key(audio.key) }
                assertEquals("no-store", head.cacheControl())
                val full = get(url)
                assertEquals(200, full.statusCode())
                assertEquals("no-store", full.headers().firstValue("Cache-Control").orElse(null))
                assertEquals("audio/mp4", full.headers().firstValue("Content-Type").orElse(null))
                assertTrue(bytes.contentEquals(full.body()))
                val partial = get(url, "bytes=1-2")
                assertEquals(206, partial.statusCode())
                assertEquals("no-store", partial.headers().firstValue("Cache-Control").orElse(null))
                assertTrue(bytes.copyOfRange(1, 3).contentEquals(partial.body()))

                val shortLived = signer.presignGetObject { request ->
                    request.signatureDuration(Duration.ofSeconds(3))
                        .getObjectRequest { it.bucket(bucket).key(audio.key) }
                }
                val shortUrl = shortLived.url().toURI()
                assertEquals(200, get(shortUrl).statusCode())
                // Wait through the provider's whole-second signature expiry boundary.
                Thread.sleep(Duration.between(Instant.now(), shortLived.expiration().plusSeconds(1)))
                assertEquals(403, get(shortUrl).statusCode())
                storage.deleteObject(bucket, audio.key)
                assertEquals(404, get(url).statusCode())
            }
        }
    }

    @Test
    fun `exclusive legacy metadata replacement preserves synthetic object and skips replay`() {
        val fixtureBucket = "legacy-media-test"
        val key = "users/${UUID.randomUUID()}/profile-photos/${UUID.randomUUID()}.png"
        S3CompatibleStorageConfig().s3Client(properties).use { client ->
            client.createBucket { it.bucket(fixtureBucket) }
            client.putObject({ it.bucket(fixtureBucket).key(key).contentType("image/png")
                .contentDisposition("inline").metadata(mapOf("fixture" to "legacy")) }, RequestBody.fromBytes(bytes))
            assertTrue(replaceFixtureMetadata(client, fixtureBucket, key))
            assertFalse(replaceFixtureMetadata(client, fixtureBucket, key))
            val result = client.getObjectAsBytes { it.bucket(fixtureBucket).key(key) }
            val headers = result.response()
            assertEquals("no-store", headers.cacheControl())
            assertEquals("image/png", headers.contentType())
            assertEquals("inline", headers.contentDisposition())
            assertEquals(mapOf("fixture" to "legacy"), headers.metadata())
            assertEquals(bytes.size.toLong(), headers.contentLength())
            assertTrue(bytes.contentEquals(result.asByteArray()))
        }
    }

    // Only for this isolated fixture with a single writer and a known set of metadata.
    private fun replaceFixtureMetadata(client: S3Client, bucket: String, key: String): Boolean {
        val head = client.headObject { it.bucket(bucket).key(key) }
        if (head.cacheControl() == "no-store") return false
        client.copyObject { it.sourceBucket(bucket).sourceKey(key).destinationBucket(bucket).destinationKey(key)
            .copySourceIfMatch(head.eTag()).metadataDirective(MetadataDirective.REPLACE)
            .contentType(head.contentType()).contentDisposition(head.contentDisposition())
            .metadata(head.metadata()).storageClass(head.storageClassAsString()).cacheControl("no-store") }
        return true
    }

    private fun get(uri: URI, range: String? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
        if (range != null) request.header("Range", range)
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    }
}
