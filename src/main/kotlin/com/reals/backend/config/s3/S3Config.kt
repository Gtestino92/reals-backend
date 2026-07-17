package com.reals.backend.config.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

enum class S3ReadUrlMode {
    PUBLIC,
    PRESIGNED
}

@ConfigurationProperties(prefix = "storage.s3")
data class S3StorageProperties(
    val endpoint: String,
    val presignedUrlEndpoint: String? = null,
    val region: String = "us-east-1",
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val publicBaseUrl: String? = null,
    val pathStyleAccessEnabled: Boolean = true,
    val signedUrlDurationMinutes: Long = 15,
    val readUrlMode: S3ReadUrlMode = S3ReadUrlMode.PRESIGNED
)

@ConfigurationProperties(prefix = "storage.profile-photos")
data class ProfilePhotoStorageProperties(
    val maxSizeBytes: Long = 5 * 1024 * 1024,
    val allowedContentTypes: List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    ),
    val maxWidthPixels: Int = 10_000,
    val maxHeightPixels: Int = 10_000
)

@ConfigurationProperties(prefix = "storage.media-cleanup")
data class MediaCleanupProperties(
    val batchSize: Int = 100,
    val leaseDuration: Duration = Duration.ofMinutes(5),
    val guardDelay: Duration = Duration.ofMinutes(30),
    val initialRetryDelay: Duration = Duration.ofMinutes(1),
    val maxRetryDelay: Duration = Duration.ofHours(1),
    val maxAttempts: Int = 10
) {
    init {
        require(batchSize > 0) { "storage.media-cleanup.batch-size must be positive" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "storage.media-cleanup.lease-duration must be positive"
        }
        require(!guardDelay.isZero && !guardDelay.isNegative) {
            "storage.media-cleanup.guard-delay must be positive"
        }
        require(!initialRetryDelay.isZero && !initialRetryDelay.isNegative) {
            "storage.media-cleanup.initial-retry-delay must be positive"
        }
        require(!maxRetryDelay.isZero && !maxRetryDelay.isNegative) {
            "storage.media-cleanup.max-retry-delay must be positive"
        }
        require(maxAttempts > 0) { "storage.media-cleanup.max-attempts must be positive" }
    }
}

@Configuration
@EnableConfigurationProperties(
    S3StorageProperties::class,
    ProfilePhotoStorageProperties::class,
    MediaCleanupProperties::class
)
class S3CompatibleStorageConfig {

    @Bean
    fun s3Client(
        properties: S3StorageProperties
    ): S3Client {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        properties.accessKeyId,
                        properties.secretAccessKey
                    )
                )
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
                    .build()
            )
            .build()
    }

    @Bean
    fun s3Presigner(
        properties: S3StorageProperties
    ): S3Presigner {
        val presignedUrlEndpoint = properties.presignedUrlEndpoint
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: properties.endpoint

        return S3Presigner.builder()
            .endpointOverride(URI.create(presignedUrlEndpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        properties.accessKeyId,
                        properties.secretAccessKey
                    )
                )
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
                    .build()
            )
            .build()
    }

    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
}
