package com.reals.backend.integration.postgres

import com.reals.backend.adminauth.*
import com.reals.backend.config.security.authentication.*
import com.reals.backend.config.security.appcheck.*
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.*
import com.reals.backend.service.UserService
import com.reals.backend.service.PenaltyService
import com.google.firebase.auth.FirebaseToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import java.time.*
import java.util.concurrent.*
import kotlin.test.*
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import javax.crypto.spec.SecretKeySpec
import tools.jackson.databind.ObjectMapper

@AutoConfigureMockMvc
@Import(AdminMfaPostgresIT.Filters::class)
@TestPropertySource(properties = [
    "backoffice.admin-uids=admin-test", "backoffice.admin-emails=admin@example.invalid",
    "backoffice.allowed-web-app-ids=1:123:web:abc",
    "admin-mfa.totp-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "admin-mfa.secret-version=test-v1"
])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminMfaPostgresIT : PostgresITBase() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: AdminAccessService
    @Autowired lateinit var states: AdminMfaStateRepository
    @Autowired lateinit var grants: AdminAccessGrantRepository
    @Autowired lateinit var mapper: ObjectMapper
    @MockitoBean(name = "adminMfaClock") lateinit var clock: Clock
    @Autowired lateinit var verifier: FirebaseTokenAuthenticationVerifier
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private lateinit var identity: AdminRequestIdentity

    @BeforeEach fun setupAdmin() {
        `when`(clock.instant()).thenReturn(now)
        val user = userRepository.saveAndFlush(User(firebaseUid = "admin-test", email = "admin@example.invalid", authOrigin = UserAuthOrigin.EMAIL_PASSWORD))
        identity = AdminRequestIdentity(user.id, "admin-test", "admin@example.invalid", true, FirebaseSignInProvider.GOOGLE, now.epochSecond, "1:123:web:abc")
        token("google", "google.com")
        token("password", "password")
    }
    private fun token(raw: String, provider: String) {
        val decoded = mock(FirebaseToken::class.java)
        `when`(decoded.uid).thenReturn(identity.uid)
        `when`(decoded.email).thenReturn(identity.email)
        `when`(decoded.isEmailVerified).thenReturn(true)
        `when`(decoded.claims).thenReturn(mapOf("auth_time" to identity.authTime, "firebase" to mapOf("sign_in_provider" to provider)))
        `when`(verifier.verifyAdministrative(raw)).thenReturn(decoded)
        `when`(verifier.verify(raw)).thenReturn(decoded)
    }
    private fun code(time: Instant = now) = TimeBasedOneTimePasswordGenerator().generateOneTimePasswordString(
        SecretKeySpec("12345678901234567890".toByteArray(), "HmacSHA1"), time)
    private fun verifyHttp(code: String) = mvc.perform(post("/api/admin-auth/verify")
        .header("Authorization", "Bearer google").header("X-Firebase-AppCheck", "web")
        .contentType("application/json").content("""{"code":"$code"}"""))
    private fun issue() = access.verify(identity, code()).value!!

    @Test fun `real chain requires MFA on all admin controllers and password cannot reuse grant`() {
        val paths = listOf("/api/admin/safety-reports", "/api/admin/penalty-appeals/pending", "/api/admin/diagnostics/matchmaking", "/api/admin/profile-photos/review")
        paths.forEach { path -> mvc.perform(get(path).header("Authorization", "Bearer google").header("X-Firebase-AppCheck", "web")).andExpect(status().isForbidden) }
        val result = verifyHttp(code()).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn()
        val grant = mapper.readTree(result.response.contentAsString).get("grant").asText()
        mvc.perform(get("/api/admin/diagnostics/matchmaking").header("Authorization", "Bearer google")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant)).andExpect(status().isOk)
        paths.forEach { path -> mvc.perform(get(path).header("Authorization", "Bearer google")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant)).andExpect(status().isOk) }
        mvc.perform(get("/api/admin/diagnostics/matchmaking").header("Authorization", "Bearer password")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant)).andExpect(status().isForbidden)
        listOf("/actuator/info", "/actuator/metrics", "/api/local-dev/anything").forEach { path ->
            mvc.perform(get(path).header("Authorization", "Bearer google").header("X-Firebase-AppCheck", "web")
                .header("X-Reals-Admin-Grant", grant)).andExpect(status().isForbidden)
        }
        verifyHttp(code()).andExpect(status().isBadRequest)
        assertEquals(1, grants.count())
        mvc.perform(get("/api/me").header("Authorization", "Bearer password").header("X-Firebase-AppCheck", "web"))
            .andExpect(status().isOk)
        repeat(2) {
            mvc.perform(post("/api/admin-auth/revoke").header("Authorization", "Bearer google")
                .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant)).andExpect(status().isNoContent)
        }
        mvc.perform(get("/api/admin/diagnostics/matchmaking").header("Authorization", "Bearer google")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant)).andExpect(status().isForbidden)
    }
    @Test fun `App Check missing invalid and Android cannot authorize administration`() {
        listOf(null, "invalid", "android").forEach { app ->
            val request = post("/api/admin-auth/verify").header("Authorization", "Bearer google").contentType("application/json").content("""{"code":"${code()}"}""")
            if (app != null) request.header("X-Firebase-AppCheck", app)
            mvc.perform(request).andExpect(status().isForbidden)
        }
        assertEquals(0, grants.count())
    }
    @Test fun `failure counters commit across HTTP errors and lockout equality unlocks`() {
        repeat(4) { verifyHttp("bad").andExpect(status().isBadRequest) }
        verifyHttp("bad").andExpect(status().isTooManyRequests).andExpect(header().string("Retry-After", "900"))
        assertEquals(5, states.findById(identity.userId).get().failedAttempts)
        `when`(clock.instant()).thenReturn(now.plusSeconds(899))
        assertEquals("ADMIN_MFA_RATE_LIMITED", access.verify(identity.copy(authTime = now.plusSeconds(899).epochSecond), "bad").error)
        `when`(clock.instant()).thenReturn(now.plusSeconds(900))
        assertNotNull(access.verify(identity.copy(authTime = now.plusSeconds(900).epochSecond), code(now.plusSeconds(900))).value)
    }
    @Test fun `concurrent replay has one winner across independent transactions`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = (1..2).map { pool.submit<AdminResult> { start.await(); access.verify(identity, code()) } }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.value != null })
            assertEquals(1, grants.count())
            assertEquals(now.epochSecond / 30, states.findById(identity.userId).get().lastAcceptedStep)
        } finally { pool.shutdownNow() }
    }
    @Test fun `absolute expiration auth context identity deletion revocation and rotation`() {
        val grant = issue()
        assertEquals(now.plusSeconds(900), grant.expiresAt)
        assertNull(access.grantError(identity, grant.grant))
        assertEquals("ADMIN_ACCESS_DENIED", access.grantError(identity.copy(authTime = identity.authTime + 1), grant.grant))
        listOf(identity.copy(uid = "other"), identity.copy(email = "other@example.invalid"), identity.copy(emailVerified = false),
            identity.copy(provider = FirebaseSignInProvider.PASSWORD), identity.copy(appId = "1:123:android:abc")).forEach {
            assertEquals("ADMIN_ACCESS_DENIED", access.grantError(it, grant.grant))
            assertEquals("ADMIN_ACCESS_DENIED", access.verify(it, code()).error)
        }
        `when`(clock.instant()).thenReturn(now.plusSeconds(899))
        assertNull(access.grantError(identity, grant.grant))
        `when`(clock.instant()).thenReturn(now.plusSeconds(900))
        assertEquals("ADMIN_GRANT_EXPIRED", access.grantError(identity, grant.grant))
        access.revoke(identity, grant.grant)
        assertNull(grants.findById(sha256(grant.grant)).get().revokedAt)
        assertEquals("ADMIN_GRANT_EXPIRED", access.grantError(identity, grant.grant))
        `when`(clock.instant()).thenReturn(now.plusSeconds(901))
        assertEquals("ADMIN_GRANT_EXPIRED", access.grantError(identity, grant.grant))
        `when`(clock.instant()).thenReturn(now)
        access.revoke(identity, grant.grant); access.revoke(identity, grant.grant)
        assertEquals("ADMIN_GRANT_REVOKED", access.grantError(identity, grant.grant))
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from audit_events where event_type='ADMIN_GRANT_REVOKED'", Int::class.java))
        jdbcTemplate.update("update admin_access_grants set factor_id='old' where user_id=?", identity.userId)
        assertEquals("ADMIN_ACCESS_DENIED", access.grantError(identity, grant.grant))
        jdbcTemplate.update("update users set status='DELETED' where id=?", identity.userId)
        assertEquals("ADMIN_ACCESS_DENIED", access.verify(identity, code()).error)
    }
    @Test fun `recent auth equality stale auth malformed grants and read only status`() {
        assertEquals("ADMIN_ACCESS_DENIED", access.verify(identity.copy(authTime = now.epochSecond - 300), code()).error)
        assertEquals("ADMIN_ACCESS_DENIED", access.verify(identity.copy(authTime = now.epochSecond + 1), code()).error)
        listOf(null, "bad", "A".repeat(43)).forEach { assertEquals("ADMIN_MFA_REQUIRED", access.grantError(identity, it)) }
        mvc.perform(get("/api/admin-auth/status").header("Authorization", "Bearer google").header("X-Firebase-AppCheck", "web"))
            .andExpect(status().isOk).andExpect(jsonPath("$.grantValid").value(false))
        assertEquals(0, states.count())
    }
    @Test fun `fresh service instance observes durable replay failure and rotated factor state`() {
        val grant = issue()
        val config = AdminMfaProperties("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "test-v1")
        val ids = AdminIdentityProperties("admin-test", "admin@example.invalid", "1:123:web:abc")
        fun instance(props: AdminMfaProperties = config, allowlists: AdminIdentityProperties = ids) =
            AdminAccessService(props, allowlists, userRepository, states, grants, penaltyService, auditEventService, clock)
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        assertEquals("ADMIN_MFA_INVALID_CODE", tx.execute { instance().verify(identity, code()) }!!.error)
        assertEquals(1, states.findById(identity.userId).get().failedAttempts)
        assertNull(instance().grantError(identity, grant.grant))
        val rotated = AdminMfaProperties("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "test-v2")
        assertEquals("ADMIN_ACCESS_DENIED", instance(rotated).grantError(identity, grant.grant))
        assertEquals("ADMIN_MFA_INVALID_CODE", tx.execute { instance(rotated).verify(identity, code()) }!!.error)
        assertTrue(states.findById(identity.userId).get().lastAcceptedStep >= now.epochSecond / 30 + 1)
        listOf(AdminIdentityProperties(adminEmails = "admin@example.invalid", allowedWebAppIds = identity.appId),
            AdminIdentityProperties(adminUids = identity.uid, allowedWebAppIds = identity.appId)).forEach { allowlist ->
            assertEquals("ADMIN_ACCESS_DENIED", tx.execute { instance(allowlists = allowlist).verify(identity, code()) }!!.error)
        }
    }
    @Test fun `suspension rejects issuance and grant reuse through real chain`() {
        val grant = issue()
        penaltyService.createTemporaryPenalty(identity.userId, "test suspension", Duration.ofHours(1),
            now = OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        assertEquals("ADMIN_ACCESS_DENIED", access.grantError(identity, grant.grant))
        assertEquals("ADMIN_ACCESS_DENIED", access.verify(identity, code()).error)
        mvc.perform(get("/api/admin/diagnostics/matchmaking").header("Authorization", "Bearer google")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant.grant)).andExpect(status().isForbidden)
    }
    @Test fun `fixed failure window equality and first issuance auth age boundary`() {
        assertNotNull(access.verify(identity.copy(authTime = now.epochSecond - 299), code()).value)
        val at = now.plusSeconds(30)
        `when`(clock.instant()).thenReturn(at)
        access.verify(identity, "bad")
        `when`(clock.instant()).thenReturn(at.plusSeconds(599))
        access.verify(identity.copy(authTime = at.plusSeconds(599).epochSecond), "bad")
        assertEquals(2, states.findById(identity.userId).get().failedAttempts)
        `when`(clock.instant()).thenReturn(at.plusSeconds(600))
        access.verify(identity.copy(authTime = at.plusSeconds(600).epochSecond), "bad")
        assertEquals(1, states.findById(identity.userId).get().failedAttempts)
    }

    @Test fun `revoked or expired Firebase credentials fail before grant authorization`() {
        val grant = issue()
        `when`(verifier.verifyAdministrative("revoked")).thenThrow(mock(com.google.firebase.auth.FirebaseAuthException::class.java))
        mvc.perform(get("/api/admin/diagnostics/matchmaking").header("Authorization", "Bearer revoked")
            .header("X-Firebase-AppCheck", "web").header("X-Reals-Admin-Grant", grant.grant)).andExpect(status().isUnauthorized)
    }

    @Test fun `cleanup is bounded replay safe and preserves audit history`() {
        issue()
        val auditCount = jdbcTemplate.queryForObject("select count(*) from audit_events", Long::class.java)
        jdbcTemplate.update("""insert into admin_access_grants
            (token_hash,user_id,firebase_uid,auth_time,provider,web_app_id,factor_id,issued_at,expires_at)
            select md5(n::text)||md5(n::text),?, 'admin-test',1,'GOOGLE','1:123:web:abc','old',?,?
            from generate_series(1,101) n""", identity.userId, java.sql.Timestamp.from(now.minusSeconds(900)), java.sql.Timestamp.from(now))
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        val cleanup = AdminGrantCleanup(grants, clock)
        val staleHashes = grants.findFirst100ByExpiresAtLessThanEqualOrderByExpiresAtAscTokenHashAsc(now).map { it.tokenHash }
        tx.executeWithoutResult { cleanup.cleanup() }
        assertEquals(0, tx.execute { grants.deleteExpired(staleHashes, now) })
        assertEquals(2, grants.count())
        tx.executeWithoutResult { cleanup.cleanup() }
        tx.executeWithoutResult { cleanup.cleanup() }
        assertEquals(1, grants.count())
        assertEquals(auditCount, jdbcTemplate.queryForObject("select count(*) from audit_events", Long::class.java))
    }

    @TestConfiguration class Filters {
        @Bean fun firebaseTokenAuthenticationVerifier(): FirebaseTokenAuthenticationVerifier = mock(FirebaseTokenAuthenticationVerifier::class.java)
        @Bean fun firebaseTokenFilter(users: UserService, penalties: PenaltyService, verifier: FirebaseTokenAuthenticationVerifier, access: AdminAccessService) =
            FirebaseTokenFilter(EnvironmentExposurePolicy.forActiveProfiles("prod"), users, penalties, verifier, adminAccessService = access)
        @Bean fun firebaseAppCheckFilter() = FirebaseAppCheckFilter(FirebaseAppCheckProperties(mode = FirebaseAppCheckMode.MONITOR),
            object : FirebaseAppCheckVerifier {
                override fun verify(token: String): FirebaseAppCheckVerificationResult = when (token) {
                    "web" -> FirebaseAppCheckVerificationResult.Valid("1:123:web:abc")
                    "android" -> FirebaseAppCheckVerificationResult.Valid("1:123:android:abc")
                    else -> FirebaseAppCheckVerificationResult.Invalid
                }
            }, null)
    }
}
