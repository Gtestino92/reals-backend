package com.reals.backend.config.s3

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class S3ReadUrlModeStartupValidatorTest {

    @Test
    fun `prod public read mode is rejected`() {
        assertThrows<IllegalStateException> {
            validator(
                readUrlMode = S3ReadUrlMode.PUBLIC,
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod")
            ).afterPropertiesSet()
        }
    }

    @Test
    fun `prod presigned read mode is accepted`() {
        assertDoesNotThrow {
            validator(
                readUrlMode = S3ReadUrlMode.PRESIGNED,
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod")
            ).afterPropertiesSet()
        }
    }

    @Test
    fun `prod non positive signed URL duration is rejected`() {
        val exception = assertThrows<IllegalStateException> {
            validator(
                readUrlMode = S3ReadUrlMode.PRESIGNED,
                signedUrlDurationMinutes = 0,
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod")
            ).afterPropertiesSet()
        }

        kotlin.test.assertEquals(
            "storage.s3.signed-url-duration-minutes must be positive in prod",
            exception.message
        )
    }

    @Test
    fun `local public read mode is accepted`() {
        assertDoesNotThrow {
            validator(
                readUrlMode = S3ReadUrlMode.PUBLIC,
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("local-nodb")
            ).afterPropertiesSet()
        }
    }

    @Test
    fun `local non positive signed URL duration is not rejected at startup`() {
        assertDoesNotThrow {
            validator(
                readUrlMode = S3ReadUrlMode.PRESIGNED,
                signedUrlDurationMinutes = 0,
                environmentExposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("local-nodb")
            ).afterPropertiesSet()
        }
    }

    private fun validator(
        readUrlMode: S3ReadUrlMode,
        signedUrlDurationMinutes: Long = 15,
        environmentExposurePolicy: EnvironmentExposurePolicy
    ): S3ReadUrlModeStartupValidator =
        S3ReadUrlModeStartupValidator(
            properties = S3StorageProperties(
                endpoint = "http://localhost:9000",
                region = "us-east-1",
                bucket = "profile-photos",
                accessKeyId = "access",
                secretAccessKey = "secret",
                signedUrlDurationMinutes = signedUrlDurationMinutes,
                readUrlMode = readUrlMode
            ),
            environmentExposurePolicy = environmentExposurePolicy
        )
}
