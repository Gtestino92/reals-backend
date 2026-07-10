package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class ProductionProfilePhotoConfigurationTest {

    @Test
    fun `production default minimum full-body photos is zero`() {
        val environment = MockEnvironment()
        val propertySources = YamlPropertySourceLoader()
            .load("application-prod", ClassPathResource("application-prod.yml"))
        propertySources.reversed().forEach { propertySource ->
            environment.propertySources.addFirst(propertySource)
        }

        assertEquals(
            0,
            environment.getProperty("profile.photos.min-full-body-photos", Int::class.java)
        )
    }
}
