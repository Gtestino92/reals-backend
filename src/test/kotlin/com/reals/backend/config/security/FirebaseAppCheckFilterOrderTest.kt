package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.appcheck.FirebaseAppCheckFilter
import com.reals.backend.config.security.appcheck.FirebaseAppCheckMode
import com.reals.backend.config.security.appcheck.FirebaseAppCheckProperties
import com.reals.backend.config.security.appcheck.FirebaseAppCheckVerificationResult
import com.reals.backend.config.security.appcheck.FirebaseAppCheckVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenAuthenticationVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.security.web.FilterChainProxy
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertTrue

@WebMvcTest(
    controllers = [FirebaseAppCheckFilterOrderTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                RateLimitFilter::class,
                PostAuthenticationRateLimitFilter::class
            ]
        )
    ]
)
@Import(
    SecurityConfig::class,
    EnvironmentExposurePolicy::class,
    FirebaseAppCheckFilterOrderTest.FilterOrderTestConfig::class,
    FirebaseAppCheckFilterOrderTest.ProbeController::class
)
@ActiveProfiles("prod")
class FirebaseAppCheckFilterOrderTest {

    @Autowired
    private lateinit var filterChainProxy: FilterChainProxy

    @Test
    fun `protected paths run app check before firebase authentication`() {
        val request = MockHttpServletRequest("GET", "/api/me")
        val filters = filterChainProxy.filterChains
            .first { it.matches(request) }
            .filters
        val appCheckIndex = filters.indexOfFirst { it is FirebaseAppCheckFilter }
        val firebaseIndex = filters.indexOfFirst { it is FirebaseTokenFilter }

        assertTrue(appCheckIndex >= 0, "App Check filter must be present")
        assertTrue(firebaseIndex >= 0, "Firebase authentication filter must be present")
        assertTrue(appCheckIndex < firebaseIndex, "App Check must run before Firebase authentication")
    }

    @RestController
    class ProbeController {

        @GetMapping("/api/me")
        fun me(): String = "ok"
    }

    @Configuration
    class FilterOrderTestConfig {

        @Bean
        fun userService(): UserService = mock(UserService::class.java)

        @Bean
        fun firebaseTokenFilter(
            environmentExposurePolicy: EnvironmentExposurePolicy,
            userService: UserService,
            penaltyService: PenaltyService,
            firebaseTokenAuthenticationVerifier: FirebaseTokenAuthenticationVerifier
        ): FirebaseTokenFilter =
            FirebaseTokenFilter(
                environmentExposurePolicy,
                userService,
                penaltyService,
                firebaseTokenAuthenticationVerifier
            )

        @Bean
        fun firebaseTokenAuthenticationVerifier(): FirebaseTokenAuthenticationVerifier =
            mock(FirebaseTokenAuthenticationVerifier::class.java)

        @Bean
        fun penaltyService(): PenaltyService = mock(PenaltyService::class.java)

        @Bean
        fun firebaseAppCheckFilter(): FirebaseAppCheckFilter =
            FirebaseAppCheckFilter(
                FirebaseAppCheckProperties(
                    mode = FirebaseAppCheckMode.ENFORCED,
                    projectNumber = "123456789",
                    allowedAppIds = listOf("1:123456789:android:app")
                ),
                FirebaseAppCheckVerifier {
                    FirebaseAppCheckVerificationResult.Valid("1:123456789:android:app")
                },
                null
            )
    }
}
