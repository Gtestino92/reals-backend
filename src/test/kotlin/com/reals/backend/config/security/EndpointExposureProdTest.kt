package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(
    controllers = [EndpointExposureProdTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                FirebaseTokenFilter::class,
                RateLimitFilter::class,
                PostAuthenticationRateLimitFilter::class
            ]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class, EndpointExposureProdTest.ProbeController::class)
@ActiveProfiles("prod")
class EndpointExposureProdTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `local-dev endpoints cannot execute in prod even for admin if a handler is registered`() {
        mockMvc.perform(get("/api/local-dev/probe"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/local-dev/probe").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/api/local-dev/probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `h2 console cannot execute in prod even if a handler is registered`() {
        mockMvc.perform(get("/h2-console/probe"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/h2-console/probe").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `actuator health remains public in prod`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
    }

    @Test
    fun `actuator info requires admin in prod`() {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/actuator/info").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/actuator/info").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
    }

    @Test
    fun `actuator metrics require admin in prod`() {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/actuator/metrics").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/actuator/metrics").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
    }

    @Test
    fun `admin endpoints require admin in prod`() {
        mockMvc.perform(get("/api/admin/probe"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/admin/probe").with(user("firebase").roles("FIREBASE_AUTHENTICATED")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/api/admin/probe").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/api/admin/probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
    }

    @RestController
    class ProbeController {

        @GetMapping("/api/local-dev/probe")
        fun localDevProbe(): String = "executed"

        @GetMapping("/h2-console/probe")
        fun h2ConsoleProbe(): String = "executed"

        @GetMapping("/actuator/health")
        fun actuatorHealth(): String = "executed"

        @GetMapping("/actuator/health/readiness")
        fun actuatorReadiness(): String = "executed"

        @GetMapping("/actuator/info")
        fun actuatorInfo(): String = "executed"

        @GetMapping("/actuator/metrics")
        fun actuatorMetrics(): String = "executed"

        @GetMapping("/api/admin/probe")
        fun adminProbe(): String = "executed"
    }
}
