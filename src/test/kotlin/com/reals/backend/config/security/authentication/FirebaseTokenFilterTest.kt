package com.reals.backend.config.security.authentication

import com.github.benmanes.caffeine.cache.Ticker
import com.google.firebase.ErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseToken
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.domain.UserStatus
import com.reals.backend.service.UserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirebaseTokenFilterTest {

    private val userService = mock(UserService::class.java)
    private val firebaseTokenAuthenticationVerifier = mock(FirebaseTokenAuthenticationVerifier::class.java)
    private val localFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("local-firebase"),
        userService,
        firebaseTokenAuthenticationVerifier
    )
    private val prodFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("prod"),
        userService,
        firebaseTokenAuthenticationVerifier
    )
    private val adminFilter = FirebaseTokenFilter(
        EnvironmentExposurePolicy.forActiveProfiles("prod"),
        userService,
        firebaseTokenAuthenticationVerifier,
        "admin@example.com"
    )

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

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
    fun `email password origin accepts password token`() {
        val decodedToken = decodedToken(
            uid = "firebase-password-origin-password",
            email = "password@example.com",
            providerValue = "password"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-password-origin-password"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-password-origin-password",
                    email = "password@example.com",
                    authOrigin = UserAuthOrigin.EMAIL_PASSWORD
                )
            )

        val response = MockHttpServletResponse()

        localFilter.doFilter(
            authorizedRequest("GET", "/api/me", "valid-token"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
        assertTrue(SecurityContextHolder.getContext().authentication!!.principal is CurrentUserAuthContext)
    }

    @Test
    fun `email password origin accepts google token`() {
        val decodedToken = decodedToken(
            uid = "firebase-password-origin-google",
            email = "password-google@example.com",
            providerValue = "google.com"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-password-origin-google"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-password-origin-google",
                    email = "password-google@example.com",
                    authOrigin = UserAuthOrigin.EMAIL_PASSWORD
                )
            )

        val response = MockHttpServletResponse()

        localFilter.doFilter(
            authorizedRequest("GET", "/api/me", "valid-token"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
    }

    @Test
    fun `google origin accepts google token`() {
        val decodedToken = decodedToken(
            uid = "firebase-google-origin-google",
            email = "google@example.com",
            providerValue = "google.com"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-google-origin-google"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-google-origin-google",
                    email = "google@example.com",
                    authOrigin = UserAuthOrigin.GOOGLE
                )
            )

        val response = MockHttpServletResponse()

        localFilter.doFilter(
            authorizedRequest("GET", "/api/me", "valid-token"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
    }

    @Test
    fun `google origin rejects password token`() {
        val decodedToken = decodedToken(
            uid = "firebase-google-origin-password",
            email = "google-password@example.com",
            providerValue = "password"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-google-origin-password"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-google-origin-password",
                    email = "google-password@example.com",
                    authOrigin = UserAuthOrigin.GOOGLE
                )
            )

        val response = MockHttpServletResponse()

        localFilter.doFilter(
            authorizedRequest("GET", "/api/me", "valid-token"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("AUTH_METHOD_NOT_ALLOWED"))
    }

    @Test
    fun `deleted google origin rejects password token before recovery routes`() {
        val decodedToken = decodedToken(
            uid = "firebase-deleted-google-origin-password",
            email = "deleted-google@example.com",
            providerValue = "password"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-deleted-google-origin-password"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-deleted-google-origin-password",
                    email = "deleted-google@example.com",
                    status = UserStatus.DELETED,
                    authOrigin = UserAuthOrigin.GOOGLE
                )
            )

        val response = MockHttpServletResponse()

        localFilter.doFilter(
            authorizedRequest("POST", "/api/me/reactivation", "valid-token"),
            response,
            MockFilterChain()
        )

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("AUTH_METHOD_NOT_ALLOWED"))
    }

    @Test
    fun `missing or unsupported firebase provider fails closed`() {
        listOf(null, "anonymous").forEach { providerValue ->
            val decodedToken = decodedToken(
                uid = "firebase-unsupported-${providerValue ?: "missing"}",
                email = "unsupported@example.com",
                providerValue = providerValue
            )
            `when`(firebaseTokenAuthenticationVerifier.verify("valid-token-${providerValue ?: "missing"}"))
                .thenReturn(decodedToken)

            val response = MockHttpServletResponse()

            localFilter.doFilter(
                authorizedRequest("POST", "/api/me/provision", "valid-token-${providerValue ?: "missing"}"),
                response,
                MockFilterChain()
            )

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("UNSUPPORTED_AUTH_PROVIDER"))
        }
    }

    @Test
    fun `password reset public endpoint skips bearer token authentication`() {
        val response = MockHttpServletResponse()

        localFilter.doFilter(
            MockHttpServletRequest("POST", "/api/auth/password-reset"),
            response,
            MockFilterChain()
        )

        assertEquals(200, response.status)
        Mockito.verifyNoInteractions(firebaseTokenAuthenticationVerifier)
    }

    @Test
    fun `allowed google token preserves admin role behavior`() {
        val decodedToken = decodedToken(
            uid = "firebase-admin-google",
            email = "admin@example.com",
            providerValue = "google.com"
        )
        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token")).thenReturn(decodedToken)
        `when`(userService.findByFirebaseUid("firebase-admin-google"))
            .thenReturn(
                user(
                    firebaseUid = "firebase-admin-google",
                    email = "admin@example.com",
                    authOrigin = UserAuthOrigin.EMAIL_PASSWORD
                )
            )

        val response = MockHttpServletResponse()

        adminFilter.doFilter(
            authorizedRequest("GET", "/api/me", "valid-token"),
            response,
            MockFilterChain()
        )

        val authorities = SecurityContextHolder.getContext().authentication!!.authorities.map { it.authority }.toSet()
        assertEquals(200, response.status)
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
        `when`(firebaseTokenAuthenticationVerifier.verify("invalid-token"))
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

    @Test
    fun `deleted backend user cannot call local firebase email verification endpoint`() {
        val decodedToken = mock(FirebaseToken::class.java)

        `when`(firebaseTokenAuthenticationVerifier.verify("valid-token"))
            .thenReturn(decodedToken)
        `when`(decodedToken.uid).thenReturn("firebase-deleted")
        `when`(decodedToken.email).thenReturn("deleted@example.com")
        `when`(decodedToken.isEmailVerified).thenReturn(true)
        `when`(decodedToken.claims).thenReturn(firebaseClaims("password"))
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

    @Test
    fun `deleted backend user remains rejected while revocation result is cached`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val decodedToken = mock(FirebaseToken::class.java)
        val cachedVerifier = FirebaseTokenAuthenticationVerifier(
            firebaseAuth = firebaseAuth,
            properties = FirebaseTokenAuthenticationProperties(),
            ticker = FakeTicker()
        )
        val filter = FirebaseTokenFilter(
            EnvironmentExposurePolicy.forActiveProfiles("prod"),
            userService,
            cachedVerifier
        )
        val activeUser = user(
            firebaseUid = "firebase-cached",
            email = "cached@example.com"
        )
        val deletedUser = user(
            firebaseUid = "firebase-cached",
            email = "cached@example.com",
            status = UserStatus.DELETED
        )

        `when`(firebaseAuth.verifyIdToken("cached-token", false))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("cached-token", true))
            .thenReturn(decodedToken)
        `when`(decodedToken.uid).thenReturn("firebase-cached")
        `when`(decodedToken.email).thenReturn("cached@example.com")
        `when`(decodedToken.isEmailVerified).thenReturn(true)
        `when`(decodedToken.claims).thenReturn(firebaseClaims("password"))
        `when`(userService.findByFirebaseUid("firebase-cached"))
            .thenReturn(activeUser, deletedUser)

        val firstResponse = MockHttpServletResponse()
        filter.doFilter(
            authorizedRequest("POST", "/api/chats/${UUID.randomUUID()}/messages", "cached-token"),
            firstResponse,
            MockFilterChain()
        )

        val secondResponse = MockHttpServletResponse()
        filter.doFilter(
            authorizedRequest("POST", "/api/chats/${UUID.randomUUID()}/messages", "cached-token"),
            secondResponse,
            MockFilterChain()
        )

        assertEquals(200, firstResponse.status)
        assertEquals(401, secondResponse.status)
        assertTrue(secondResponse.contentAsString.contains("ACCOUNT_DELETED"))
        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("cached-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("cached-token", true)
    }

    private fun user(
        firebaseUid: String,
        email: String?,
        status: UserStatus = UserStatus.ACTIVE,
        authOrigin: UserAuthOrigin? = UserAuthOrigin.EMAIL_PASSWORD
    ): User =
        User(
            id = UUID.randomUUID(),
            firebaseUid = firebaseUid,
            email = email,
            status = status,
            authOrigin = authOrigin
        )

    private fun decodedToken(
        uid: String,
        email: String?,
        emailVerified: Boolean = true,
        providerValue: String?
    ): FirebaseToken {
        val decodedToken = mock(FirebaseToken::class.java)
        `when`(decodedToken.uid).thenReturn(uid)
        `when`(decodedToken.email).thenReturn(email)
        `when`(decodedToken.isEmailVerified).thenReturn(emailVerified)
        `when`(decodedToken.claims).thenReturn(firebaseClaims(providerValue))
        return decodedToken
    }

    private fun firebaseClaims(providerValue: String?): Map<String, Any> =
        if (providerValue == null) {
            mapOf("firebase" to emptyMap<String, Any>())
        } else {
            mapOf("firebase" to mapOf("sign_in_provider" to providerValue))
        }

    private fun authorizedRequest(
        method: String,
        path: String,
        token: String
    ): MockHttpServletRequest =
        MockHttpServletRequest(method, path).apply {
            addHeader("Authorization", "Bearer $token")
        }

    private class FakeTicker : Ticker {
        override fun read(): Long = 0
    }
}
