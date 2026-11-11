package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.controller.dev.DevJobController
import com.reals.backend.controller.dev.DevMatchmakingController
import com.reals.backend.controller.dev.DevPairHistoryResetController
import com.reals.backend.controller.dev.DevTimeoutController
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.localdev.LocalDevPairHistoryResetResult
import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
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
        DevPairHistoryResetController::class,
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
    private lateinit var pairHistoryResetService: LocalDevPairHistoryResetService

    @MockitoBean
    private lateinit var chatRepository: ChatRepository

    @MockitoBean
    private lateinit var chatMessageRepository: ChatMessageRepository

    @MockitoBean
    private lateinit var connectionRepository: ConnectionRepository

    @MockitoBean
    private lateinit var penaltyRepository: PenaltyRepository

    @MockitoBean
    private lateinit var scheduleNegotiationRepository: ScheduleNegotiationRepository

    @MockitoBean
    private lateinit var secondChatResolutionRequestRepository: SecondChatResolutionRequestRepository

    @MockitoBean
    private lateinit var visualReviewRepository: VisualReviewRepository

    @MockitoBean
    private lateinit var schedulingActivationJob: SchedulingActivationJob

    @Test
    fun `dev tooling denies users and injected admin roles without verified App Check support`() {
        val paths = listOf(
            "/api/local-dev/matchmaking/process",
            "/api/local-dev/pair-history/reset",
            "/api/local-dev/timeouts/connections/${UUID.randomUUID()}/scheduling-available-now",
            "/api/local-dev/jobs/scheduling-activation/run"
        )
        for (role in listOf("USER", "ADMIN")) for (path in paths) {
            mockMvc.perform(post(path).with(user("caller").roles(role)))
                .andExpect(status().isForbidden)
        }
        Mockito.verifyNoInteractions(matchmakingProcessorService, pairHistoryResetService,
            connectionRepository, schedulingActivationJob)
    }
}
