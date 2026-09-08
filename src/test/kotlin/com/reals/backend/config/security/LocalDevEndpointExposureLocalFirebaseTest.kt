package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.controller.dev.DevJobController
import com.reals.backend.controller.dev.DevMatchmakingController
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.matching.MatchmakingProcessorService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [DevMatchmakingController::class, DevJobController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class, PostAuthenticationRateLimitFilter::class]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class)
@ActiveProfiles("local-firebase")
class LocalDevEndpointExposureLocalFirebaseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var matchmakingProcessorService: MatchmakingProcessorService

    @MockitoBean
    private lateinit var schedulingActivationJob: SchedulingActivationJob

    @Test
    fun `local-dev matchmaking endpoint is mapped and allowed without authentication in local-firebase`() {
        `when`(matchmakingProcessorService.process(maxPairsPerRun = 5))
            .thenReturn(
                MatchmakingProcessResult(
                    candidatePairs = 0,
                    matchesCreated = 0,
                    failedPairs = 0,
                    matches = emptyList()
                )
            )

        mockMvc.perform(post("/api/local-dev/matchmaking/process"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchesCreated").value(0))
    }

    @Test
    fun `local-dev scheduling activation manual endpoint runs scheduling activation job`() {
        mockMvc.perform(post("/api/local-dev/jobs/scheduling-activation/run"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.job").value("SchedulingActivationJob"))

        verify(schedulingActivationJob).run()
    }
}
