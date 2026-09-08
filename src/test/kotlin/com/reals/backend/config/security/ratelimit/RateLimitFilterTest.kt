package com.reals.backend.config.security.ratelimit

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class RateLimitFilterTest {

    private val properties = RateLimitProperties(
        preAuthCapacity = 1,
        preAuthRefillTokens = 1,
        preAuthRefillPeriodSeconds = 60,
        defaultCapacity = 1,
        defaultRefillTokens = 1,
        defaultRefillPeriodSeconds = 60,
        provisionCapacity = 1,
        provisionRefillTokens = 1,
        provisionRefillPeriodSeconds = 60,
        messageCapacity = 1,
        messageRefillTokens = 1,
        messageRefillPeriodSeconds = 60,
        safetyReportCapacity = 1,
        safetyReportRefillTokens = 1,
        safetyReportRefillPeriodSeconds = 60
    )
    private val resolver = RateLimitRuleResolver(properties)
    private val exposurePolicy = EnvironmentExposurePolicy.forActiveProfiles("prod")

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `pre auth uses same bucket for different invalid bearer values from same ip and group`() {
        val filter = preAuthFilter()

        assertEquals(200, runPreAuth(filter, provisionRequest("10.0.0.1", "invalid-token-1")))
        assertEquals(429, runPreAuth(filter, provisionRequest("10.0.0.1", "invalid-token-2")))
    }

    @Test
    fun `pre auth uses different buckets for different ips`() {
        val filter = preAuthFilter()

        assertEquals(200, runPreAuth(filter, provisionRequest("10.0.0.1", "invalid-token")))
        assertEquals(200, runPreAuth(filter, provisionRequest("10.0.0.2", "invalid-token")))
    }

    @Test
    fun `pre auth key does not contain or hash authorization value`() {
        val filter = preAuthFilter()
        val token = "invalid-token-secret"
        val request = provisionRequest("10.0.0.1", token)
        val key = filter.rateLimitKey(request)

        assertEquals("pre-auth:provision:ip:10.0.0.1", key)
        assertFalse(key.contains(token))
        assertFalse(key.contains(sha256Prefix(token)))
    }

    @Test
    fun `password reset uses its own pre auth bucket group`() {
        val filter = preAuthFilter()
        val request = passwordResetRequest("10.0.0.1")

        assertEquals(RateLimitGroup.PASSWORD_RESET, resolver.resolveGroup(request))
        assertEquals("pre-auth:password-reset:ip:10.0.0.1", filter.rateLimitKey(request))
    }

    @Test
    fun `pre auth uses broad capacity instead of safety report capacity`() {
        val broadPreAuthProperties = RateLimitProperties(
            preAuthCapacity = 2,
            preAuthRefillTokens = 2,
            preAuthRefillPeriodSeconds = 60,
            safetyReportCapacity = 1,
            safetyReportRefillTokens = 1,
            safetyReportRefillPeriodSeconds = 86_400
        )
        val filter = RateLimitFilter(
            broadPreAuthProperties,
            RateLimitRuleResolver(broadPreAuthProperties),
            exposurePolicy
        )

        assertEquals(200, runPreAuth(filter, safetyReportRequest("10.0.0.1", "invalid-token-1")))
        assertEquals(200, runPreAuth(filter, safetyReportRequest("10.0.0.1", "invalid-token-2")))
        assertEquals(429, runPreAuth(filter, safetyReportRequest("10.0.0.1", "invalid-token-3")))
    }

    @Test
    fun `pre auth excludes actuator health`() {
        val filter = preAuthFilter()

        assertEquals(200, runPreAuth(filter, apiRequest("10.0.0.1", "token-1", "/actuator/health")))
        assertEquals(200, runPreAuth(filter, apiRequest("10.0.0.1", "token-2", "/actuator/health/readiness")))
    }

    @Test
    fun `pre auth limits actuator info using same ip bucket across bearer values`() {
        val filter = preAuthFilter()

        assertEquals(200, runPreAuth(filter, apiRequest("10.0.0.1", "invalid-token-1", "/actuator/info")))
        assertEquals(429, runPreAuth(filter, apiRequest("10.0.0.1", "invalid-token-2", "/actuator/info")))
    }

    @Test
    fun `pre auth limits actuator metrics`() {
        val filter = preAuthFilter()

        assertEquals(200, runPreAuth(filter, apiRequest("10.0.0.1", "invalid-token-1", "/actuator/metrics")))
        assertEquals(429, runPreAuth(filter, apiRequest("10.0.0.1", "invalid-token-2", "/actuator/metrics/jvm.memory.used")))
    }

    @Test
    fun `post auth uses backend user id for provisioned principals across token rotations`() {
        val filter = postAuthFilter()
        val userId = UUID.randomUUID()
        setPrincipal(
            CurrentUserAuthContext(
                userId = userId,
                firebaseUid = "firebase-user",
                email = "user@example.com",
                emailVerified = false
            )
        )

        assertEquals(200, runPostAuth(filter, apiRequest("10.0.0.1", "token-1", "/api/me")))
        assertEquals(429, runPostAuth(filter, apiRequest("10.0.0.1", "token-2", "/api/me")))
        assertEquals("post-auth:default:user:$userId", filter.rateLimitKey(resolver.resolve(apiRequest()), filter.principalIdentity()!!))
    }

    @Test
    fun `post auth uses firebase uid for unprovisioned principals`() {
        val filter = postAuthFilter()
        setPrincipal(FirebasePrincipal(uid = "firebase-unprovisioned", email = "user@example.com", emailVerified = true))

        val identity = filter.principalIdentity()

        assertEquals(PrincipalRateLimitIdentity("firebase", "firebase-unprovisioned"), identity)
    }

    @Test
    fun `post auth gives different users different buckets`() {
        val filter = postAuthFilter()

        setPrincipal(CurrentUserAuthContext(UUID.randomUUID(), "firebase-a", null, false))
        assertEquals(200, runPostAuth(filter, apiRequest("10.0.0.1", "token-a", "/api/me")))

        setPrincipal(CurrentUserAuthContext(UUID.randomUUID(), "firebase-b", null, false))
        assertEquals(200, runPostAuth(filter, apiRequest("10.0.0.1", "token-b", "/api/me")))
    }

    @Test
    fun `post auth safety report quota is user specific behind same ip`() {
        val filter = postAuthFilter()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()

        setPrincipal(CurrentUserAuthContext(userA, "firebase-a", null, false))
        assertEquals(200, runPostAuth(filter, safetyReportRequest("10.0.0.1", "token-a-1")))
        assertEquals(429, runPostAuth(filter, safetyReportRequest("10.0.0.1", "token-a-2")))

        setPrincipal(CurrentUserAuthContext(userB, "firebase-b", null, false))
        assertEquals(200, runPostAuth(filter, safetyReportRequest("10.0.0.1", "token-b-1")))
    }

    @Test
    fun `post auth keeps endpoint groups distinct`() {
        val filter = postAuthFilter()
        setPrincipal(CurrentUserAuthContext(UUID.randomUUID(), "firebase-user", null, false))

        assertEquals(200, runPostAuth(filter, apiRequest("10.0.0.1", "token", "/api/me")))
        assertEquals(
            200,
            runPostAuth(
                filter,
                apiRequest("10.0.0.1", "token", "/api/chats/${UUID.randomUUID()}/messages").apply {
                    method = "POST"
                }
            )
        )
    }

    @Test
    fun `post auth upload and replacement share profile photo upload bucket`() {
        val properties = RateLimitProperties(
            profilePhotoCapacity = 1,
            profilePhotoRefillTokens = 1,
            profilePhotoRefillPeriodSeconds = 60
        )
        val filter = PostAuthenticationRateLimitFilter(
            properties,
            RateLimitRuleResolver(properties),
            exposurePolicy
        )
        setPrincipal(CurrentUserAuthContext(UUID.randomUUID(), "firebase-user", null, true))

        assertEquals(200, runPostAuth(filter, profilePhotoUploadRequest()))
        assertEquals(429, runPostAuth(filter, profilePhotoReplacementRequest()))
    }

    @Test
    fun `post auth token refresh for same backend user does not reset upload bucket`() {
        val properties = RateLimitProperties(
            profilePhotoCapacity = 1,
            profilePhotoRefillTokens = 1,
            profilePhotoRefillPeriodSeconds = 60
        )
        val resolver = RateLimitRuleResolver(properties)
        val filter = PostAuthenticationRateLimitFilter(properties, resolver, exposurePolicy)
        val userId = UUID.randomUUID()

        setPrincipal(CurrentUserAuthContext(userId, "firebase-old-token", null, true))
        assertEquals(200, runPostAuth(filter, profilePhotoUploadRequest(bearerToken = "token-1")))

        setPrincipal(CurrentUserAuthContext(userId, "firebase-new-token", null, true))
        assertEquals(429, runPostAuth(filter, profilePhotoReplacementRequest(bearerToken = "token-2")))
        assertEquals(
            "post-auth:profile-photo-uploads:user:$userId",
            filter.rateLimitKey(resolver.resolve(profilePhotoUploadRequest()), filter.principalIdentity()!!)
        )
    }

    @Test
    fun `post auth delete and reorder do not consume profile photo upload bucket`() {
        val properties = RateLimitProperties(
            profilePhotoCapacity = 1,
            profilePhotoRefillTokens = 1,
            profilePhotoRefillPeriodSeconds = 60,
            defaultCapacity = 10,
            defaultRefillTokens = 10
        )
        val filter = PostAuthenticationRateLimitFilter(
            properties,
            RateLimitRuleResolver(properties),
            exposurePolicy
        )
        setPrincipal(CurrentUserAuthContext(UUID.randomUUID(), "firebase-user", null, true))

        assertEquals(200, runPostAuth(filter, profilePhotoDeleteRequest()))
        assertEquals(200, runPostAuth(filter, profilePhotoReorderRequest()))
        assertEquals(200, runPostAuth(filter, profilePhotoUploadRequest()))
        assertEquals(429, runPostAuth(filter, profilePhotoReplacementRequest()))
    }

    @Test
    fun `post auth supports local dev string principal`() {
        val filter = postAuthFilter()
        setPrincipal("00000000-0000-0000-0000-000000000001")

        assertEquals(
            PrincipalRateLimitIdentity("local-dev", "00000000-0000-0000-0000-000000000001"),
            filter.principalIdentity()
        )
    }

    private fun preAuthFilter(): RateLimitFilter =
        RateLimitFilter(properties, resolver, exposurePolicy)

    private fun postAuthFilter(): PostAuthenticationRateLimitFilter =
        PostAuthenticationRateLimitFilter(properties, resolver, exposurePolicy)

    private fun runPreAuth(filter: RateLimitFilter, request: MockHttpServletRequest): Int {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response.status
    }

    private fun runPostAuth(filter: PostAuthenticationRateLimitFilter, request: MockHttpServletRequest): Int {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response.status
    }

    private fun provisionRequest(remoteAddr: String, bearerToken: String): MockHttpServletRequest =
        apiRequest(remoteAddr, bearerToken, "/api/me/provision").apply {
            method = "POST"
        }

    private fun safetyReportRequest(remoteAddr: String, bearerToken: String): MockHttpServletRequest =
        apiRequest(remoteAddr, bearerToken, "/api/safety/reports").apply {
            method = "POST"
        }

    private fun passwordResetRequest(remoteAddr: String): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/auth/password-reset").apply {
            setRemoteAddr(remoteAddr)
        }

    private fun profilePhotoUploadRequest(
        remoteAddr: String = "10.0.0.1",
        bearerToken: String = "token"
    ): MockHttpServletRequest =
        apiRequest(remoteAddr, bearerToken, "/api/me/profile/photos").apply {
            method = "POST"
        }

    private fun profilePhotoReplacementRequest(
        remoteAddr: String = "10.0.0.1",
        bearerToken: String = "token"
    ): MockHttpServletRequest =
        apiRequest(remoteAddr, bearerToken, "/api/me/profile/photos/${UUID.randomUUID()}/file").apply {
            method = "PUT"
        }

    private fun profilePhotoDeleteRequest(): MockHttpServletRequest =
        apiRequest(path = "/api/me/profile/photos/${UUID.randomUUID()}").apply {
            method = "DELETE"
        }

    private fun profilePhotoReorderRequest(): MockHttpServletRequest =
        apiRequest(path = "/api/me/profile/photos/reorder").apply {
            method = "PUT"
        }

    private fun apiRequest(
        remoteAddr: String = "10.0.0.1",
        bearerToken: String = "token",
        path: String = "/api/me"
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", path).apply {
            setRemoteAddr(remoteAddr)
            addHeader("Authorization", "Bearer $bearerToken")
        }

    private fun setPrincipal(principal: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER"))
            )
    }

    private fun sha256Prefix(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
        ).take(32)
}
