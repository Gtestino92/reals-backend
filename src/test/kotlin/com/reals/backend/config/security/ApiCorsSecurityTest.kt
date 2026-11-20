package com.reals.backend.config.security

import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.appcheck.FirebaseAppCheckConfig
import com.reals.backend.config.security.appcheck.FirebaseAppCheckFilter
import com.reals.backend.config.security.appcheck.FirebaseAppCheckVerificationResult
import com.reals.backend.config.security.appcheck.FirebaseAppCheckVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenAuthenticationVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitConfig
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitMetrics
import com.reals.backend.config.security.ratelimit.RateLimitRuleResolver
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.UserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.web.FilterChainProxy
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.CorsFilter

@WebMvcTest(
    controllers = [ApiCorsSecurityTest.ProbeController::class],
    properties = [
        "REALS_CORS_ALLOWED_ORIGINS=https://reals-web.pages.dev, https://other.example:8443",
        "FIREBASE_PROJECT_NUMBER=123456789",
        "FIREBASE_APP_CHECK_ALLOWED_APP_IDS=test-app",
        "security.rate-limit.admin-mfa-capacity=1",
        "security.rate-limit.admin-mfa-refill-tokens=1",
        "security.rate-limit.admin-mfa-refill-period-seconds=3600"
    ]
)
@Import(
    SecurityConfig::class,
    EnvironmentExposurePolicy::class,
    FirebaseAppCheckConfig::class,
    RateLimitConfig::class,
    RateLimitRuleResolver::class,
    ApiCorsSecurityTest.ProbeController::class
)
@ActiveProfiles("prod")
class ApiCorsSecurityTest {

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var security: FilterChainProxy

    @MockitoBean
    private lateinit var appCheckVerifier: FirebaseAppCheckVerifier

    @MockitoBean
    private lateinit var tokenVerifier: FirebaseTokenAuthenticationVerifier

    @MockitoBean
    private lateinit var rateLimitMetrics: RateLimitMetrics

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var penaltyService: PenaltyService

    @Test
    fun `allowed preflight is handled before security filters and never invokes the controller`() {
        val filters = security.filterChains.first { it.matches(MockHttpServletRequest("GET", "/api/me")) }.filters
        val corsIndex = filters.indexOfFirst { it is CorsFilter }
        assertThat(corsIndex).isNotNegative()
        listOf(
            RateLimitFilter::class.java, FirebaseAppCheckFilter::class.java,
            FirebaseTokenFilter::class.java, PostAuthenticationRateLimitFilter::class.java
        ).forEach { type ->
            assertThat(filters.indexOfFirst(type::isInstance)).isGreaterThan(corsIndex)
        }

        mvc.perform(preflight("/api/me", "GET"))
            .andExpect(status().isOk)
            .andExpect(content().string(""))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization, x-firebase-appcheck"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
            .andExpect(header().stringValues(HttpHeaders.VARY,
                HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS))
            .andExpect(header().doesNotExist("X-RateLimit-Limit"))

        verifyNoInteractions(appCheckVerifier, tokenVerifier, rateLimitMetrics)
    }

    @Test
    fun `JSON writes multipart uploads and admin grant headers have preflight permission`() {
        val jsonHeaders = "authorization,content-type,x-firebase-appcheck"
        val uploadHeaders = "authorization,x-firebase-appcheck" // Browser supplies the multipart Content-Type.
        listOf(
            Triple("/api/me/provision", "POST", jsonHeaders),
            Triple("/api/me/profile", "PATCH", jsonHeaders),
            Triple("/api/me/profile/photos", "POST", uploadHeaders),
            Triple("/api/admin/profile-photos", "GET", "authorization,x-firebase-appcheck,x-reals-admin-grant")
        ).forEach { (path, method, headers) ->
            mvc.perform(preflight(path, method, headers))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, headers.replace(",", ", ")))
        }

        mvc.perform(preflight("/api/me", "GET", origin = "https://other.example:8443"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://other.example:8443"))
    }

    @Test
    fun `unlisted origins receive no preflight authorization`() {
        listOf(
            "https://unrelated.example", "https://reals-web.pages.dev.unrelated.example",
            "http://reals-web.pages.dev", "https://reals-web.pages.dev:8443",
            "https://other.example", "https://reals-web.pages.dev/", "null"
        ).forEach { origin ->
            mvc.perform(preflight("/api/me", "GET", origin = origin))
                .andExpect(status().isForbidden)
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        }
    }

    @Test
    fun `invalid preflights and infrastructure paths have no CORS permission`() {
        listOf(
            preflight("/api/me", "TRACE"),
            preflight("/api/me", "GET", "x-unlisted"),
            preflight("/api/me", "GET", "authorization,x-unlisted"),
            preflight("/api/me", "GET", "authorization").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-unlisted"),
            preflight("/actuator/health", "GET"),
            preflight("/h2-console/", "GET"),
            preflight("/api-other/me", "GET")
        ).forEach { request ->
            mvc.perform(request)
                .andExpect(status().isForbidden)
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        }
        mvc.perform(options("/api/me").header(HttpHeaders.ORIGIN, WEB_ORIGIN))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `allowed origin receives existing App Check Firebase and admin error responses`() {
        mvc.perform(get("/api/me").header(HttpHeaders.ORIGIN, WEB_ORIGIN))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(jsonPath("$.code").value("MISSING_APP_CHECK_TOKEN"))

        `when`(appCheckVerifier.verify("invalid-app")).thenReturn(FirebaseAppCheckVerificationResult.Invalid)
        mvc.perform(get("/api/me").header(HttpHeaders.ORIGIN, WEB_ORIGIN).header("X-Firebase-AppCheck", "invalid-app"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(jsonPath("$.code").value("INVALID_APP_CHECK_TOKEN"))

        acceptAppCheck()
        mvc.perform(get("/api/me").header(HttpHeaders.ORIGIN, WEB_ORIGIN).header("X-Firebase-AppCheck", "app"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(jsonPath("$.code").value("MISSING_AUTHORIZATION_HEADER"))

        `when`(tokenVerifier.verify("invalid")).thenThrow(
            FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "invalid token", null, null, AuthErrorCode.INVALID_ID_TOKEN)
        )
        mvc.perform(get("/api/me").header(HttpHeaders.ORIGIN, WEB_ORIGIN)
            .header("X-Firebase-AppCheck", "app").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))

        val token = firebaseToken()
        `when`(tokenVerifier.verifyAdministrative("valid")).thenReturn(token)
        mvc.perform(get("/api/admin/profile-photos").header(HttpHeaders.ORIGIN, WEB_ORIGIN)
            .header("X-Firebase-AppCheck", "app").header(HttpHeaders.AUTHORIZATION, "Bearer valid"))
            .andExpect(status().isForbidden)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("ADMIN_ACCESS_DENIED"))
    }

    @Test
    fun `preflights leave quota intact and rate limit errors expose Retry-After`() {
        repeat(2) {
            mvc.perform(preflight("/api/admin-auth/verify", "POST"))
                .andExpect(status().isOk)
        }
        verifyNoInteractions(rateLimitMetrics)

        mvc.perform(post("/api/admin-auth/verify").header(HttpHeaders.ORIGIN, WEB_ORIGIN))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("MISSING_APP_CHECK_TOKEN"))
            .andExpect(header().string("X-RateLimit-Limit", "1"))
            .andExpect(header().string("X-RateLimit-Remaining", "0"))

        val response = mvc.perform(post("/api/admin-auth/verify").header(HttpHeaders.ORIGIN, WEB_ORIGIN))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                "Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining"))
            .andReturn().response
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)!!.toLong()).isPositive()
    }

    @Test
    fun `actual requests still use normal authentication with or without web permission`() {
        acceptAppCheck()
        val token = firebaseToken()
        `when`(tokenVerifier.verify("valid")).thenReturn(token)

        listOf(null, "https://unrelated.example", "http://localhost:5173").forEach { origin ->
            val request = post("/api/me/provision")
                .header("X-Firebase-AppCheck", "app")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
            if (origin != null) request.header(HttpHeaders.ORIGIN, origin)
            mvc.perform(request)
                .andExpect(status().isOk)
                .andExpect(content().string("provisioned"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ORIGIN))
        }
        mvc.perform(get("/api/me").header(HttpHeaders.ORIGIN, "https://unrelated.example")
            .header("X-Firebase-AppCheck", "app"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("MISSING_AUTHORIZATION_HEADER"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
    }

    @Test
    fun `malformed settings fail closed`() {
        listOf(
            "*", "https://*.example", "http://example.com", "https://example.com/",
            "https://example.com/path", "https://example.com?q=1", "https://example.com#section",
            "https://user@example.com", "https://example.com:", "https://example.com:65536",
            "https://example.com,", "https://example.com, *"
        ).forEach { configuredOrigin ->
            assertThrows<IllegalArgumentException> { ApiCorsConfiguration(configuredOrigin) }
        }
    }

    private fun preflight(
        path: String,
        method: String,
        headers: String = "authorization,x-firebase-appcheck",
        origin: String = WEB_ORIGIN
    ) = options(path)
        .header(HttpHeaders.ORIGIN, origin)
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers)

    private fun acceptAppCheck() {
        `when`(appCheckVerifier.verify("app")).thenReturn(FirebaseAppCheckVerificationResult.Valid("test-app"))
    }

    private fun firebaseToken(): FirebaseToken = mock(FirebaseToken::class.java).also { token ->
        `when`(token.uid).thenReturn("test-uid")
        `when`(token.claims).thenReturn(mapOf("firebase" to mapOf("sign_in_provider" to "password")))
    }

    @RestController
    class ProbeController {
        @GetMapping("/api/me")
        fun me(): String = "me"

        @PostMapping("/api/me/provision")
        fun provision(): String = "provisioned"
    }

    private companion object {
        const val WEB_ORIGIN = "https://reals-web.pages.dev"
    }
}
