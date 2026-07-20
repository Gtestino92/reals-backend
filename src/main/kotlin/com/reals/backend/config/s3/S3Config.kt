package com.reals.backend.config.s3

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
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

@ConfigurationProperties(prefix = "profile.photos.validation")
data class ProfilePhotoValidationProperties(
    val maxFileSizeBytes: Long = 5 * 1024 * 1024,
    val allowedContentTypes: List<String> = listOf(
        "image/jpeg",
        "image/png"
    ),
    val maxInputWidth: Int = 6_000,
    val maxInputHeight: Int = 6_000,
    val maxInputPixels: Long = 20_000_000
) {
    init {
        require(maxFileSizeBytes > 0) { "profile.photos.validation.max-file-size-bytes must be positive" }
        require(maxInputWidth > 0) { "profile.photos.validation.max-input-width must be positive" }
        require(maxInputHeight > 0) { "profile.photos.validation.max-input-height must be positive" }
        require(maxInputPixels > 0) { "profile.photos.validation.max-input-pixels must be positive" }
        require(maxInputWidth.toLong() * maxInputHeight.toLong() <= Long.MAX_VALUE) {
            "profile.photos.validation input dimension limits overflow"
        }
        require(allowedContentTypes.toSet() == setOf("image/jpeg", "image/png")) {
            "profile.photos.validation.allowed-content-types must be exactly image/jpeg and image/png"
        }
    }
}

@ConfigurationProperties(prefix = "profile.photos.normalization")
data class ProfilePhotoNormalizationProperties(
    val maxOutputDimension: Int = 2_048,
    val jpegQuality: Float = 0.88f
) {
    init {
        require(maxOutputDimension > 0) { "profile.photos.normalization.max-output-dimension must be positive" }
        require(jpegQuality > 0.0f && jpegQuality <= 1.0f) {
            "profile.photos.normalization.jpeg-quality must be greater than 0 and at most 1"
        }
    }
}

@ConfigurationProperties(prefix = "profile.photos.upload")
data class ProfilePhotoUploadProperties(
    val maxConcurrent: Int = 2,
    val permitWaitDuration: Duration = Duration.ZERO,
    val retryAfterSeconds: Long = 1
) {
    init {
        require(maxConcurrent > 0) { "profile.photos.upload.max-concurrent must be positive" }
        require(!permitWaitDuration.isNegative) { "profile.photos.upload.permit-wait-duration must be non-negative" }
        require(retryAfterSeconds > 0) { "profile.photos.upload.retry-after-seconds must be positive" }
    }
}

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
    ProfilePhotoValidationProperties::class,
    ProfilePhotoNormalizationProperties::class,
    ProfilePhotoUploadProperties::class,
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

@Component
class S3ReadUrlModeStartupValidator(
    private val properties: S3StorageProperties,
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (environmentExposurePolicy.isProduction() && properties.readUrlMode == S3ReadUrlMode.PUBLIC) {
            throw IllegalStateException(
                "storage.s3.read-url-mode=PUBLIC is not allowed in prod; use PRESIGNED"
            )
        }
    }
}

@Component
class ProfilePhotoProcessingPropertiesValidator(
    private val validationProperties: ProfilePhotoValidationProperties,
    private val normalizationProperties: ProfilePhotoNormalizationProperties
) : InitializingBean {
    override fun afterPropertiesSet() {
        require(normalizationProperties.maxOutputDimension <= validationProperties.maxInputWidth) {
            "profile.photos.normalization.max-output-dimension must not exceed profile.photos.validation.max-input-width"
        }
        require(normalizationProperties.maxOutputDimension <= validationProperties.maxInputHeight) {
            "profile.photos.normalization.max-output-dimension must not exceed profile.photos.validation.max-input-height"
        }
    }
}
