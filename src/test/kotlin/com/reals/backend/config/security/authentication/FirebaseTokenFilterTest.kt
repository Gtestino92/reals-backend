package com.reals.backend.config.security.authentication

import com.reals.backend.service.UserService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class FirebaseTokenFilterTest {

    private val filter = FirebaseTokenFilter(mock(UserService::class.java))

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

            filter.doFilter(
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

        filter.doFilter(
            MockHttpServletRequest("GET", "/api/me"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `public legal document catalog passes without authorization header`() {
        val response = MockHttpServletResponse()

        filter.doFilter(
            MockHttpServletRequest("GET", "/api/legal/documents/current"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
    }
}
