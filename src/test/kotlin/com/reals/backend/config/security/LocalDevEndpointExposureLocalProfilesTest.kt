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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(
    controllers = [LocalDevEndpointExposureLocalNodbTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class, LocalDevEndpointExposureLocalNodbTest.ProbeController::class)
@ActiveProfiles("local-nodb")
class LocalDevEndpointExposureLocalNodbTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `local-dev endpoints are allowed without authentication in local-nodb`() {
        mockMvc.perform(get("/api/local-dev/probe"))
            .andExpect(status().isOk)
            .andExpect(content().string("executed"))
    }

    @RestController
    class ProbeController {
        @GetMapping("/api/local-dev/probe")
        fun localDevProbe(): String = "executed"
    }
}

@WebMvcTest(
    controllers = [LocalDevEndpointExposureLocalPostgresTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class]
        )
    ]
)
@Import(SecurityConfig::class, EnvironmentExposurePolicy::class, LocalDevEndpointExposureLocalPostgresTest.ProbeController::class)
@ActiveProfiles("local-postgres")
class LocalDevEndpointExposureLocalPostgresTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `local-dev endpoints are allowed without authentication in local-postgres`() {
        mockMvc.perform(get("/api/local-dev/probe"))
            .andExpect(status().isOk)
            .andExpect(content().string("executed"))
    }

    @RestController
    class ProbeController {
        @GetMapping("/api/local-dev/probe")
        fun localDevProbe(): String = "executed"
    }
}
