package com.reals.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MatchmakingJobPropertiesTest {

    @Test
    fun `accepts positive values`() {
        val properties = MatchmakingJobProperties(
            fixedDelay = 15_000,
            maxPairsPerRun = 10
        )

        assertEquals(15_000, properties.fixedDelay)
        assertEquals(10, properties.maxPairsPerRun)
    }

    @Test
    fun `rejects non positive fixed delay`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingJobProperties(
                fixedDelay = 0,
                maxPairsPerRun = 10
            )
        }
    }

    @Test
    fun `rejects non positive max pairs per run`() {
        assertThrows<IllegalArgumentException> {
            MatchmakingJobProperties(
                fixedDelay = 60_000,
                maxPairsPerRun = 0
            )
        }
    }
}
