package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
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
    controllers = [EndpointExposureDevTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class)
@ActiveProfiles("dev")
class EndpointExposureDevTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `local-dev endpoints cannot execute in dev even if a handler is registered`() {
        mockMvc.perform(get("/api/local-dev/probe"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/local-dev/probe").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `h2 console cannot execute in dev even if a handler is registered`() {
        mockMvc.perform(get("/h2-console/probe"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/h2-console/probe").with(user("user").roles("USER")))
            .andExpect(status().isForbidden)
    }

    @RestController
    class ProbeController {

        @GetMapping("/api/local-dev/probe")
        fun localDevProbe(): String = "executed"

        @GetMapping("/h2-console/probe")
        fun h2ConsoleProbe(): String = "executed"
    }
}
