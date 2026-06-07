package com.reals.backend.config.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

@ConfigurationProperties(prefix = "storage.s3")
data class S3StorageProperties(
    val endpoint: String,
    val region: String = "us-east-1",
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val publicBaseUrl: String? = null,
    val pathStyleAccessEnabled: Boolean = true,
    val signedUrlDurationMinutes: Long = 15
)

@ConfigurationProperties(prefix = "storage.profile-photos")
data class ProfilePhotoStorageProperties(
    val maxSizeBytes: Long = 5 * 1024 * 1024,
    val allowedContentTypes: List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    )
)

@Configuration
@EnableConfigurationProperties(
    S3StorageProperties::class,
    ProfilePhotoStorageProperties::class
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
}