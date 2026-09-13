package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class MatchmakingProfileConfigurationTest {

    @Test
    fun `dev defaults to probabilistic ranking shadow affinity and enabled reliability`() {
        val environment = environmentFromYaml("application-dev.yml")

        assertEquals(
            "PROBABILISTIC_WEIGHTED",
            environment.getProperty("matchmaking.ranking.mode")
        )
        assertEquals(
            "SHADOW",
            environment.getProperty("matchmaking.ranking.affinity.mode")
        )
        assertEquals(
            true,
            environment.getProperty("user-reliability.enabled", Boolean::class.java)
        )
    }

    @Test
    fun `dev ranking and affinity defaults can be overridden`() {
        val environment = environmentFromYaml("application-dev.yml").apply {
            setProperty("MATCHMAKING_RANKING_MODE", "LEGACY_EARLY_ACCEPT")
            setProperty("MATCHMAKING_RANKING_AFFINITY_MODE", "OFF")
        }

        assertEquals(
            "LEGACY_EARLY_ACCEPT",
            environment.getProperty("matchmaking.ranking.mode")
        )
        assertEquals(
            "OFF",
            environment.getProperty("matchmaking.ranking.affinity.mode")
        )
    }

    @Test
    fun `dev affinity can be overridden to active for manual validation`() {
        val environment = environmentFromYaml("application-dev.yml").apply {
            setProperty("MATCHMAKING_RANKING_AFFINITY_MODE", "ACTIVE")
        }

        assertEquals(
            "ACTIVE",
            environment.getProperty("matchmaking.ranking.affinity.mode")
        )
    }

    @Test
    fun `prod defaults remain legacy ranking and affinity off`() {
        val environment = environmentFromYaml("application-prod.yml")

        assertEquals(
            "LEGACY_EARLY_ACCEPT",
            environment.getProperty("matchmaking.ranking.mode")
        )
        assertEquals(
            "OFF",
            environment.getProperty("matchmaking.ranking.affinity.mode")
        )
    }

    @Test
    fun `prod ranking and affinity defaults can be explicitly overridden`() {
        val environment = environmentFromYaml("application-prod.yml").apply {
            setProperty("MATCHMAKING_RANKING_MODE", "PROBABILISTIC_WEIGHTED")
            setProperty("MATCHMAKING_RANKING_AFFINITY_MODE", "SHADOW")
        }

        assertEquals(
            "PROBABILISTIC_WEIGHTED",
            environment.getProperty("matchmaking.ranking.mode")
        )
        assertEquals(
            "SHADOW",
            environment.getProperty("matchmaking.ranking.affinity.mode")
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
