package com.reals.backend.config.security.authentication

import com.google.firebase.ErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseToken
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    private val adminFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("prod"),
        userService,
        "admin@example.com"
    )

    @Test
    fun `actuator health endpoints pass without authorization header`() {
        listOf(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
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
    fun `actuator info is not an authentication bypass in prod`() {
        val response = MockHttpServletResponse()

        prodFilter.doFilter(
            MockHttpServletRequest("GET", "/actuator/info"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `active linked verified allowlisted firebase email receives user and admin authorities`() {
        val authorities = adminFilter.authoritiesForActiveUser(
            user = user(firebaseUid = "firebase-admin", email = "local@example.com"),
            firebaseUid = "firebase-admin",
            firebaseEmail = " Admin@Example.com ",
            firebaseEmailVerified = true
        ).map { it.authority }.toSet()

        assertTrue(SecurityRoles.ROLE_USER in authorities)
        assertTrue(SecurityRoles.ROLE_ADMIN in authorities)
    }

    @Test
    fun `allowlisted unverified firebase email receives user but not admin authority`() {
        val authorities = adminFilter.authoritiesForActiveUser(
            user = user(firebaseUid = "firebase-admin", email = "admin@example.com"),
            firebaseUid = "firebase-admin",
            firebaseEmail = "admin@example.com",
            firebaseEmailVerified = false
        ).map { it.authority }.toSet()

        assertTrue(SecurityRoles.ROLE_USER in authorities)
        assertFalse(SecurityRoles.ROLE_ADMIN in authorities)
    }

    @Test
    fun `allowlisted local email does not grant admin when firebase email differs`() {
        val authorities = adminFilter.authoritiesForActiveUser(
            user = user(firebaseUid = "firebase-admin", email = "admin@example.com"),
            firebaseUid = "firebase-admin",
            firebaseEmail = "different@example.com",
            firebaseEmailVerified = true
        ).map { it.authority }.toSet()

        assertTrue(SecurityRoles.ROLE_USER in authorities)
        assertFalse(SecurityRoles.ROLE_ADMIN in authorities)
    }

    @Test
    fun `deleted linked user never receives admin authority`() {
        val authorities = adminFilter.authoritiesForActiveUser(
            user = user(
                firebaseUid = "firebase-admin",
                email = "admin@example.com",
                status = UserStatus.DELETED
            ),
            firebaseUid = "firebase-admin",
            firebaseEmail = "admin@example.com",
            firebaseEmailVerified = true
        ).map { it.authority }.toSet()

        assertTrue(SecurityRoles.ROLE_USER in authorities)
        assertFalse(SecurityRoles.ROLE_ADMIN in authorities)
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

    @Test
    fun `local firebase email verification endpoint is not a local-dev authentication bypass`() {
        val response = MockHttpServletResponse()

        localFilter.doFilter(
            MockHttpServletRequest("POST", "/api/me/local-dev/email-verification"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `invalid firebase token cannot call local firebase email verification endpoint`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)

        Mockito.mockStatic(FirebaseAuth::class.java).use { mockedFirebaseAuth ->
            mockedFirebaseAuth.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }
                .thenReturn(firebaseAuth)
            `when`(firebaseAuth.verifyIdToken("invalid-token", true))
                .thenThrow(
                    FirebaseAuthException(
                        ErrorCode.UNAUTHENTICATED,
                        "invalid token",
                        null,
                        null,
                        AuthErrorCode.INVALID_ID_TOKEN
                    )
                )

            val request = MockHttpServletRequest("POST", "/api/me/local-dev/email-verification")
            request.addHeader("Authorization", "Bearer invalid-token")
            val response = MockHttpServletResponse()

            localFilter.doFilter(request, response, MockFilterChain())

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("INVALID_TOKEN"))
        }
    }

    @Test
    fun `deleted backend user cannot call local firebase email verification endpoint`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val decodedToken = mock(FirebaseToken::class.java)

        Mockito.mockStatic(FirebaseAuth::class.java).use { mockedFirebaseAuth ->
            mockedFirebaseAuth.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }
                .thenReturn(firebaseAuth)
            `when`(firebaseAuth.verifyIdToken("valid-token", true))
                .thenReturn(decodedToken)
            `when`(decodedToken.uid).thenReturn("firebase-deleted")
            `when`(decodedToken.email).thenReturn("deleted@example.com")
            `when`(decodedToken.isEmailVerified).thenReturn(true)
            `when`(userService.findByFirebaseUid("firebase-deleted"))
                .thenReturn(
                    user(
                        firebaseUid = "firebase-deleted",
                        email = "deleted@example.com",
                        status = UserStatus.DELETED
                    )
                )

            val request = MockHttpServletRequest("POST", "/api/me/local-dev/email-verification")
            request.addHeader("Authorization", "Bearer valid-token")
            val response = MockHttpServletResponse()

            localFilter.doFilter(request, response, MockFilterChain())

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("ACCOUNT_DELETED"))
        }
    }

    private fun user(
        firebaseUid: String,
        email: String?,
        status: UserStatus = UserStatus.ACTIVE
    ): User =
        User(
            id = UUID.randomUUID(),
            firebaseUid = firebaseUid,
            email = email,
            status = status
        )
}
