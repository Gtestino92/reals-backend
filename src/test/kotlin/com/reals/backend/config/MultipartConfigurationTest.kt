package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.util.unit.DataSize

class MultipartConfigurationTest {

    @Test
    fun `application multipart defaults align with profile photo product limit`() {
        val environment = StandardEnvironment()
        val propertySources = YamlPropertySourceLoader().load(
            "application.yml",
            ClassPathResource("application.yml")
        )
        propertySources.reversed().forEach {
            environment.propertySources.addFirst(it)
        }

        assertEquals(
            DataSize.ofMegabytes(5),
            DataSize.parse(environment.getRequiredProperty("spring.servlet.multipart.max-file-size"))
        )
        assertEquals(
            DataSize.ofMegabytes(6),
            DataSize.parse(environment.getRequiredProperty("spring.servlet.multipart.max-request-size"))
        )
    }
}
