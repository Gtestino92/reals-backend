package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class ObservabilityConfigurationTest {

    @Test
    fun `shared metrics configuration enables only selected standard families and custom meters`() {
        val environment = environmentFromYaml("application.yml")

        assertEquals(false, environment.getProperty("management.metrics.enable.all", Boolean::class.java))
        assertEquals(true, environment.getProperty("management.metrics.enable.reals", Boolean::class.java))
        assertEquals(true, environment.getProperty("management.metrics.enable.http.server.requests", Boolean::class.java))
        assertEquals(true, environment.getProperty("management.metrics.enable.jvm", Boolean::class.java))
        assertEquals(true, environment.getProperty("management.metrics.enable.process", Boolean::class.java))
        assertEquals(true, environment.getProperty("management.metrics.enable.hikaricp", Boolean::class.java))

        assertNull(environment.getProperty("management.metrics.enable.jdbc"))
        assertNull(environment.getProperty("management.metrics.enable.logback"))
        assertNull(environment.getProperty("management.metrics.enable.tomcat"))
        assertNull(environment.getProperty("management.metrics.enable.spring.data.repository.invocations"))
    }

    @Test
    fun `shared actuator web exposure remains limited to existing endpoints`() {
        val environment = environmentFromYaml("application.yml")

        assertEquals(
            "health,info,metrics",
            environment.getRequiredProperty("management.endpoints.web.exposure.include")
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
