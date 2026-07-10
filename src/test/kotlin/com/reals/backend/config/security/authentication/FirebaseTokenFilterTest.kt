package com.reals.backend.config.security.authentication

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class FirebaseTokenFilterTest {

    private val userService = mock(UserService::class.java)
    private val localFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("local-firebase"),
        userService
    )
    private val prodFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("prod"),
        userService
    )

    @Test
    fun `actuator health endpoints pass without authorization header`() {
        listOf(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info"
        ).forEach { path ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            localFilter.doFilter(
                MockHttpServletRequest("GET", path),
                response,
                chain
            )

            assertEquals(200, response.status)
        }
    }

    @Test
    fun `protected api endpoints require authorization header`() {
        val response = MockHttpServletResponse()

        localFilter.doFilter(
            MockHttpServletRequest("GET", "/api/me"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `public legal document catalog passes without authorization header`() {
        val response = MockHttpServletResponse()

        localFilter.doFilter(
            MockHttpServletRequest("GET", "/api/legal/documents/current"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
    }

    @Test
    fun `local-dev endpoints pass without authorization header only in local firebase profile`() {
        val localResponse = MockHttpServletResponse()
        localFilter.doFilter(
            MockHttpServletRequest("POST", "/api/local-dev/matchmaking/process"),
            localResponse,
            MockFilterChain()
        )

        val prodResponse = MockHttpServletResponse()
        prodFilter.doFilter(
            MockHttpServletRequest("POST", "/api/local-dev/matchmaking/process"),
            prodResponse,
            MockFilterChain()
        )

        assertEquals(200, localResponse.status)
        assertEquals(401, prodResponse.status)
    }

    @Test
    fun `api auth namespace is not an authentication bypass`() {
        val response = MockHttpServletResponse()

        localFilter.doFilter(
            MockHttpServletRequest("POST", "/api/auth/login"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
    }
}
