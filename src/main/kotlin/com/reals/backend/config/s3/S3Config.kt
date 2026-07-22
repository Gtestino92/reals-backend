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
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
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

enum class S3CredentialsMode {
    STATIC,
    DEFAULT_CHAIN
}

@ConfigurationProperties(prefix = "storage.s3")
data class S3StorageProperties(
    val credentialsMode: S3CredentialsMode = S3CredentialsMode.STATIC,
    val endpoint: String? = null,
    val presignedUrlEndpoint: String? = null,
    val region: String = "us-east-1",
    val bucket: String = "",
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val publicBaseUrl: String? = null,
    val pathStyleAccessEnabled: Boolean = true,
    val signedUrlDurationMinutes: Long = 15,
    val readUrlMode: S3ReadUrlMode = S3ReadUrlMode.PRESIGNED
)

data class S3ClientConfiguration(
    val region: Region,
    val endpointOverride: URI?,
    val credentialsProvider: AwsCredentialsProvider,
    val serviceConfiguration: S3Configuration
)

data class ResolvedS3StorageProperties(
    val credentialsMode: S3CredentialsMode,
    val endpoint: String?,
    val presignedUrlEndpoint: String?,
    val region: String,
    val bucket: String,
    val accessKeyId: String?,
    val secretAccessKey: String?,
    val sessionToken: String?,
    val publicBaseUrl: String?,
    val pathStyleAccessEnabled: Boolean,
    val signedUrlDurationMinutes: Long,
    val readUrlMode: S3ReadUrlMode
) {
    val nativeAmazonS3: Boolean = endpoint == null
}

class S3ClientConfigurationFactory {
    fun clientConfiguration(properties: S3StorageProperties): S3ClientConfiguration {
        val resolved = properties.resolved()
        return configuration(
            resolved = resolved,
            endpoint = resolved.endpoint
        )
    }

    fun presignerConfiguration(properties: S3StorageProperties): S3ClientConfiguration {
        val resolved = properties.resolved()
        return configuration(
            resolved = resolved,
            endpoint = resolved.presignedUrlEndpoint ?: resolved.endpoint
        )
    }

    fun validate(properties: S3StorageProperties) {
        properties.resolved()
    }

    private fun configuration(
        resolved: ResolvedS3StorageProperties,
        endpoint: String?
    ): S3ClientConfiguration =
        S3ClientConfiguration(
            region = Region.of(resolved.region),
            endpointOverride = endpoint?.let { URI.create(it) },
            credentialsProvider = credentialsProvider(resolved),
            serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(resolved.pathStyleAccessEnabled)
                .build()
        )

    private fun credentialsProvider(resolved: ResolvedS3StorageProperties): AwsCredentialsProvider =
        when (resolved.credentialsMode) {
            S3CredentialsMode.STATIC -> staticCredentialsProvider(resolved)
            S3CredentialsMode.DEFAULT_CHAIN -> DefaultCredentialsProvider.builder().build()
        }

    private fun staticCredentialsProvider(resolved: ResolvedS3StorageProperties): AwsCredentialsProvider {
        val accessKeyId = requireNotNull(resolved.accessKeyId) {
            "storage.s3.access-key-id is required when storage.s3.credentials-mode=STATIC"
        }
        val secretAccessKey = requireNotNull(resolved.secretAccessKey) {
            "storage.s3.secret-access-key is required when storage.s3.credentials-mode=STATIC"
        }
        val sessionToken = resolved.sessionToken

        val credentials = if (sessionToken == null) {
            AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        } else {
            AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
        }

        return StaticCredentialsProvider.create(credentials)
    }
}

fun S3StorageProperties.resolved(): ResolvedS3StorageProperties {
    val endpoint = endpoint.nonBlankOrNull()
    val presignedUrlEndpoint = presignedUrlEndpoint.nonBlankOrNull()
    val region = region.trim()
    val bucket = bucket.trim()
    val accessKeyId = accessKeyId.nonBlankOrNull()
    val secretAccessKey = secretAccessKey.nonBlankOrNull()
    val sessionToken = sessionToken.nonBlankOrNull()
    val publicBaseUrl = publicBaseUrl.nonBlankOrNull()

    require(bucket.isNotBlank()) { "storage.s3.bucket must be nonblank" }
    require(region.isNotBlank()) { "storage.s3.region must be nonblank" }
    if (endpoint == null) {
        require(!region.equals("auto", ignoreCase = true)) {
            "storage.s3.region must be a real AWS region when storage.s3.endpoint is not configured"
        }
    }

    require(sessionToken == null || accessKeyId != null && secretAccessKey != null) {
        "storage.s3.session-token requires storage.s3.access-key-id and storage.s3.secret-access-key"
    }

    when (credentialsMode) {
        S3CredentialsMode.STATIC -> {
            require(accessKeyId != null) {
                "storage.s3.access-key-id is required when storage.s3.credentials-mode=STATIC"
            }
            require(secretAccessKey != null) {
                "storage.s3.secret-access-key is required when storage.s3.credentials-mode=STATIC"
            }
        }
        S3CredentialsMode.DEFAULT_CHAIN -> {
            require(accessKeyId == null && secretAccessKey == null && sessionToken == null) {
                "storage.s3.access-key-id, storage.s3.secret-access-key, and storage.s3.session-token must not be configured when storage.s3.credentials-mode=DEFAULT_CHAIN"
            }
        }
    }

    return ResolvedS3StorageProperties(
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
        signedUrlDurationMinutes = signedUrlDurationMinutes,
        readUrlMode = readUrlMode
    )
}

private fun String?.nonBlankOrNull(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }

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
    private val configurationFactory = S3ClientConfigurationFactory()

    @Bean
    fun s3Client(
        properties: S3StorageProperties
    ): S3Client {
        val configuration = configurationFactory.clientConfiguration(properties)
        val builder = S3Client.builder()
            .region(configuration.region)
            .credentialsProvider(configuration.credentialsProvider)
            .serviceConfiguration(configuration.serviceConfiguration)

        configuration.endpointOverride?.let { builder.endpointOverride(it) }

        return builder
            .build()
    }

    @Bean
    fun s3Presigner(
        properties: S3StorageProperties
    ): S3Presigner {
        val configuration = configurationFactory.presignerConfiguration(properties)
        val builder = S3Presigner.builder()
            .region(configuration.region)
            .credentialsProvider(configuration.credentialsProvider)
            .serviceConfiguration(configuration.serviceConfiguration)

        configuration.endpointOverride?.let { builder.endpointOverride(it) }

        return builder
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
    private val configurationFactory = S3ClientConfigurationFactory()

    override fun afterPropertiesSet() {
        configurationFactory.validate(properties)
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
