package com.reals.backend.scheduler

import com.reals.backend.config.MatchmakingJobProperties
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.service.matching.MatchmakingProcessorService
import com.reals.backend.service.matching.MicrometerMatchmakingRunMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class MatchmakingJobMetricsTest {

    @Test
    fun `matchmaking job records latest run as not saturated when processor returns before limit exhaustion`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerMatchmakingRunMetrics(registry)
        val processorService = Mockito.mock(MatchmakingProcessorService::class.java)
        Mockito.`when`(processorService.process(maxPairsPerRun = 10))
            .thenReturn(
                MatchmakingProcessResult(
                    candidatePairs = 0,
                    matchesCreated = 0,
                    failedPairs = 0,
                    matches = emptyList(),
                    limitExhausted = false
                )
            )

        MatchmakingJob(
            matchmakingProcessorService = processorService,
            properties = MatchmakingJobProperties(fixedDelay = 15_000, maxPairsPerRun = 10),
            matchmakingRunMetrics = metrics
        ).run()

        assertEquals(
            0.0,
            registry.get(MicrometerMatchmakingRunMetrics.LIMIT_EXHAUSTED)
                .gauge()
                .value()
        )
    }

    @Test
    fun `matchmaking job records latest run as saturated when processor exhausts the configured limit`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerMatchmakingRunMetrics(registry)
        val processorService = Mockito.mock(MatchmakingProcessorService::class.java)
        Mockito.`when`(processorService.process(maxPairsPerRun = 10))
            .thenReturn(
                MatchmakingProcessResult(
                    candidatePairs = 10,
                    matchesCreated = 6,
                    failedPairs = 1,
                    matches = emptyList(),
                    limitExhausted = true
                )
            )

        MatchmakingJob(
            matchmakingProcessorService = processorService,
            properties = MatchmakingJobProperties(fixedDelay = 15_000, maxPairsPerRun = 10),
            matchmakingRunMetrics = metrics
        ).run()

        assertEquals(
            1.0,
            registry.get(MicrometerMatchmakingRunMetrics.LIMIT_EXHAUSTED)
                .gauge()
                .value()
        )
    }
}
