package com.reals.backend.config.s3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

class S3StorageProfileConfigurationTest {

    @Test
    fun `local firebase profile uses canonical storage bucket variable first`() {
        assertEquals(
            "canonical-media",
            resolveLocalFirebaseBucket(
                "STORAGE_S3_BUCKET" to "canonical-media",
                "S3_PROFILE_PHOTOS_BUCKET" to "legacy-profile-photos"
            )
        )
    }

    @Test
    fun `local firebase profile keeps deprecated bucket fallback`() {
        assertEquals(
            "legacy-profile-photos",
            resolveLocalFirebaseBucket(
                "S3_PROFILE_PHOTOS_BUCKET" to "legacy-profile-photos"
            )
        )
    }

    @Test
    fun `local firebase profile defaults to media bucket`() {
        assertEquals("reals-media", resolveLocalFirebaseBucket())
    }

    private fun resolveLocalFirebaseBucket(
        vararg properties: Pair<String, String>
    ): String {
        val propertySources = MutablePropertySources()
        propertySources.addFirst(
            MapPropertySource(
                "test",
                properties.associate { (key, value) -> key to (value as Any) }
            )
        )
        YamlPropertySourceLoader()
            .load("local-firebase", ClassPathResource("application-local-firebase.yml"))
            .forEach(propertySources::addLast)

        val resolver = PropertySourcesPropertyResolver(propertySources)
        val rawValue = resolver.getRequiredProperty("storage.s3.bucket")

        return resolver.resolveRequiredPlaceholders(rawValue)
    }
}
