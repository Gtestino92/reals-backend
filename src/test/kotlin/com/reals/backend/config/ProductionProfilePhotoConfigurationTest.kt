package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class ProductionProfilePhotoConfigurationTest {

    @Test
    fun `dev default minimum full-body photos is one`() {
        val environment = environmentFromYaml("application-dev.yml")

        assertEquals(
            1,
            environment.getProperty("profile.photos.min-full-body-photos", Int::class.java)
        )
    }

    @Test
    fun `dev minimum full-body photos can be overridden to zero`() {
        val environment = environmentFromYaml("application-dev.yml").apply {
            setProperty("PROFILE_MIN_FULL_BODY_PHOTOS", "0")
        }

        assertEquals(
            0,
            environment.getProperty("profile.photos.min-full-body-photos", Int::class.java)
        )
    }

    @Test
    fun `production default minimum full-body photos is zero`() {
        val environment = environmentFromYaml("application-prod.yml")

        assertEquals(
            0,
            environment.getProperty("profile.photos.min-full-body-photos", Int::class.java)
        )
    }

    private fun environmentFromYaml(resourceName: String): MockEnvironment {
        val environment = MockEnvironment()
        val propertySources = YamlPropertySourceLoader()
            .load(resourceName, ClassPathResource(resourceName))
        propertySources.reversed().forEach { propertySource ->
            environment.propertySources.addFirst(propertySource)
        }
        return environment
    }
}
