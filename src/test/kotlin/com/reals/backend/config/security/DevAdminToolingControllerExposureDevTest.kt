package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.controller.dev.DevJobController
import com.reals.backend.controller.dev.DevMatchmakingController
import com.reals.backend.controller.dev.DevTimeoutController
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.matching.MatchmakingProcessorService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [
        DevMatchmakingController::class,
        DevTimeoutController::class,
        DevJobController::class
    ],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class, PostAuthenticationRateLimitFilter::class]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class)
@ActiveProfiles("dev")
class DevAdminToolingControllerExposureDevTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var matchmakingProcessorService: MatchmakingProcessorService

    @MockitoBean
    private lateinit var chatRepository: ChatRepository

    @MockitoBean
    private lateinit var connectionRepository: ConnectionRepository

    @MockitoBean
    private lateinit var penaltyRepository: PenaltyRepository

    @MockitoBean
    private lateinit var scheduleNegotiationRepository: ScheduleNegotiationRepository

    @MockitoBean
    private lateinit var visualReviewRepository: VisualReviewRepository

    @MockitoBean
    private lateinit var schedulingActivationJob: SchedulingActivationJob

    @Test
    fun `dev admin can execute mapped matchmaking tooling and normal user cannot`() {
        `when`(matchmakingProcessorService.process(maxPairsPerRun = 7))
            .thenReturn(
                MatchmakingProcessResult(
                    candidatePairs = 0,
                    matchesCreated = 0,
                    failedPairs = 0,
                    matches = emptyList()
                )
            )

        mockMvc.perform(
            post("/api/local-dev/matchmaking/process")
                .param("maxPairsPerRun", "7")
                .with(user("admin").roles("ADMIN"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchesCreated").value(0))

        verify(matchmakingProcessorService).process(maxPairsPerRun = 7)

        mockMvc.perform(
            post("/api/local-dev/matchmaking/process")
                .param("maxPairsPerRun", "7")
                .with(user("normal").roles("USER"))
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `dev admin can execute timeout tooling mutation and normal user cannot`() {
        val connectionId = UUID.randomUUID()
        `when`(
            connectionRepository.updateSchedulingAvailableAt(
                eqUuid(connectionId),
                anyOffsetDateTime()
            )
        ).thenReturn(1)

        mockMvc.perform(
            post("/api/local-dev/timeouts/connections/$connectionId/scheduling-available-now")
                .with(user("admin").roles("ADMIN"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("connection-scheduling-availability"))
            .andExpect(jsonPath("$.id").value(connectionId.toString()))

        verify(connectionRepository).updateSchedulingAvailableAt(
            eqUuid(connectionId),
            anyOffsetDateTime()
        )

        mockMvc.perform(
            post("/api/local-dev/timeouts/connections/$connectionId/scheduling-available-now")
                .with(user("normal").roles("USER"))
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `dev admin can execute job tooling and normal user cannot`() {
        mockMvc.perform(
            post("/api/local-dev/jobs/scheduling-activation/run")
                .with(user("admin").roles("ADMIN"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.job").value("SchedulingActivationJob"))

        verify(schedulingActivationJob).run()

        mockMvc.perform(
            post("/api/local-dev/jobs/scheduling-activation/run")
                .with(user("normal").roles("USER"))
        )
            .andExpect(status().isForbidden)
    }

    private fun anyOffsetDateTime(): OffsetDateTime {
        Mockito.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }

    private fun eqUuid(value: UUID): UUID {
        Mockito.eq(value)
        return value
    }
}
