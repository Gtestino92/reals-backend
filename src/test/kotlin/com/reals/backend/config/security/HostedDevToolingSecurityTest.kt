package com.reals.backend.config.security

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import com.reals.backend.adminauth.*
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.appcheck.*
import com.reals.backend.config.security.authentication.FirebaseTokenAuthenticationVerifier
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.controller.dev.DevJobController
import com.reals.backend.controller.dev.DevMatchmakingController
import com.reals.backend.controller.dev.DevPairHistoryResetController
import com.reals.backend.domain.MatchmakingProcessResult
import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.repository.UserRepository
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.service.AuditEventService
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.UserService
import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
import com.reals.backend.service.matching.MatchmakingProcessorService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import javax.crypto.spec.SecretKeySpec

@WebMvcTest(
    controllers = [
        AdminAuthController::class, DevMatchmakingController::class,
        DevJobController::class, DevPairHistoryResetController::class
    ],
    excludeFilters = [ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = [RateLimitFilter::class, PostAuthenticationRateLimitFilter::class]
    )],
    properties = [
        "DEV_REMOTE_TOOLING_ENABLED=true",
        "DEV_REMOTE_TOOLING_PASSWORD_AUTH_ENABLED=true",
        "FIREBASE_APP_CHECK_MODE=MONITOR",
        "FIREBASE_PROJECT_NUMBER=123",
        "FIREBASE_APP_CHECK_ALLOWED_APP_IDS=1:123:web:admin,1:123:web:other",
        "BACKOFFICE_ADMIN_UIDS=admin",
        "BACKOFFICE_ADMIN_EMAILS=admin@example.invalid",
        "BACKOFFICE_ALLOWED_WEB_APP_IDS=1:123:web:admin",
        "ADMIN_MFA_TOTP_SECRET=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        "ADMIN_MFA_SECRET_VERSION=test-v1"
    ]
)
@Import(
    SecurityConfig::class, EnvironmentExposurePolicy::class, FirebaseTokenFilter::class,
    FirebaseAppCheckConfig::class, AdminAccessService::class, AdminMfaConfiguration::class
)
@ActiveProfiles("dev")
class HostedDevToolingSecurityTest {
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var mapper: ObjectMapper
    @MockitoBean private lateinit var users: UserRepository
    @MockitoBean private lateinit var userService: UserService
    @MockitoBean private lateinit var penalties: PenaltyService
    @MockitoBean private lateinit var audit: AuditEventService
    @MockitoBean private lateinit var states: AdminMfaStateRepository
    @MockitoBean private lateinit var grants: AdminAccessGrantRepository
    @MockitoBean private lateinit var adminMfaClock: Clock
    @MockitoBean private lateinit var firebase: FirebaseTokenAuthenticationVerifier
    @MockitoBean private lateinit var appCheck: FirebaseAppCheckVerifier
    @MockitoBean private lateinit var matchmaking: MatchmakingProcessorService
    @MockitoBean private lateinit var scheduling: SchedulingActivationJob
    @MockitoBean private lateinit var pairHistory: LocalDevPairHistoryResetService

    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val admin = User(firebaseUid = "admin", email = "admin@example.invalid", authOrigin = UserAuthOrigin.GOOGLE)
    private val normalUser = User(firebaseUid = "regular", email = "regular@example.invalid", authOrigin = UserAuthOrigin.GOOGLE)
    private val savedGrants = mutableMapOf<String, AdminAccessGrant>()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
        `when`(adminMfaClock.instant()).thenReturn(now)
        for (user in listOf(admin, normalUser)) {
            `when`(userService.findByFirebaseUid(user.firebaseUid!!)).thenReturn(user)
            `when`(users.findById(user.id)).thenReturn(Optional.of(user))
        }
        `when`(users.findAllByIdForUpdate(listOf(admin.id))).thenReturn(listOf(admin))
        `when`(states.findById(admin.id)).thenReturn(Optional.empty())
        `when`(states.save(any(AdminMfaState::class.java))).thenAnswer { it.getArgument(0) }
        `when`(grants.save(any(AdminAccessGrant::class.java))).thenAnswer {
            val grant = it.getArgument<AdminAccessGrant>(0)
            savedGrants[grant.tokenHash] = grant
            grant
        }
        `when`(grants.findById(anyString())).thenAnswer { Optional.ofNullable(savedGrants[it.getArgument<String>(0)]) }
        `when`(appCheck.verify("web")).thenReturn(FirebaseAppCheckVerificationResult.Valid("1:123:web:admin"))
        `when`(appCheck.verify("other-web")).thenReturn(FirebaseAppCheckVerificationResult.Valid("1:123:web:other"))
        `when`(appCheck.verify("invalid")).thenReturn(FirebaseAppCheckVerificationResult.Invalid)
        `when`(firebase.verifyAdministrative("invalid")).thenThrow(mock(FirebaseAuthException::class.java))
        stubToken("google", admin)
        stubToken("unverified", admin, emailVerified = false)
        stubToken("password", admin, provider = "password")
        stubToken("regular", normalUser)
    }

    @Test
    fun `issued TOTP grant authorizes both jobs exactly once`() {
        val grant = issueGrant()
        `when`(matchmaking.process(5)).thenReturn(MatchmakingProcessResult(0, 0, 0, emptyList()))

        mvc.perform(post(MATCHMAKING).credentials(grant = grant))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchesCreated").value(0))
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(post(SCHEDULING).credentials(grant = grant))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.job").value("SchedulingActivationJob"))
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get("/actuator/metrics").credentials(grant = grant))
            .andExpect(status().isForbidden)

        verify(matchmaking).process(5)
        verify(scheduling).run()
        verifyNoInteractions(pairHistory)
    }

    @Test
    fun `missing disallowed or invalid App Check cannot issue a grant in monitor mode`() {
        for (app in listOf(null, "other-web", "invalid")) {
            verifyTotp(app).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ADMIN_ACCESS_DENIED"))
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(states, grants, matchmaking, scheduling, pairHistory)
    }

    @Test
    fun `unverified requests cannot invoke jobs or destructive tooling`() {
        val grant = issueGrant()
        for (path in listOf(MATCHMAKING, SCHEDULING, PAIR_RESET)) {
            denied(path, 401, "MISSING_AUTHORIZATION_HEADER", bearer = null, grant = grant)
            denied(path, 401, "INVALID_TOKEN", bearer = "invalid", grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", bearer = "password", grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", bearer = "regular", grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", bearer = "unverified", grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", app = null, grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", app = "invalid", grant = grant)
            denied(path, 403, "ADMIN_ACCESS_DENIED", app = "other-web", grant = grant)
            denied(path, 403, "ADMIN_MFA_REQUIRED")
            denied(path, 403, "ADMIN_MFA_REQUIRED", grant = "invalid")
        }

        `when`(adminMfaClock.instant()).thenReturn(now.plusSeconds(900))
        denied(MATCHMAKING, 403, "ADMIN_GRANT_EXPIRED", grant = grant)
        `when`(adminMfaClock.instant()).thenReturn(now)
        mvc.perform(post("/api/admin-auth/revoke").credentials(grant = grant))
            .andExpect(status().isNoContent)
        denied(SCHEDULING, 403, "ADMIN_GRANT_REVOKED", grant = grant)
        denied(PAIR_RESET, 403, "ADMIN_GRANT_REVOKED", grant = grant)
        verifyNoInteractions(matchmaking, scheduling, pairHistory)
    }

    @Test
    fun `password grant is restricted to hosted tooling with the verified password context`() {
        denied(SCHEDULING, 403, "ADMIN_MFA_REQUIRED", bearer = "password")
        verifyTotp("invalid", "password").andExpect(status().isForbidden)
        stubToken("other-password", normalUser, provider = "password")
        verifyTotp("web", "other-password").andExpect(status().isForbidden)
        `when`(adminMfaClock.instant()).thenReturn(now.plusSeconds(300))
        verifyTotp("web", "password").andExpect(status().isForbidden)
        `when`(adminMfaClock.instant()).thenReturn(now)
        val response = verifyTotp("web", "password").andExpect(status().isOk).andReturn().response
        val grant = mapper.readTree(response.contentAsString).get("grant").stringValue()
        mvc.perform(post(SCHEDULING).credentials("password", grant = grant)).andExpect(status().isOk)
        verify(scheduling).run()
        denied(SCHEDULING, 403, "ADMIN_ACCESS_DENIED", grant = grant)
        mvc.perform(get("/api/admin/profile-photos/review").credentials("password", grant = grant))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/profile-photos/review").credentials("google", grant = grant))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/admin-auth/revoke").credentials("password", grant = grant))
            .andExpect(status().isNoContent)
        denied(SCHEDULING, 403, "ADMIN_GRANT_REVOKED", bearer = "password", grant = grant)
    }

    @Test
    fun `DEV with remote tooling enabled and password option absent rejects password issuance`() {
        val env = org.springframework.mock.env.MockEnvironment().withProperty("local-dev.remote-tooling-enabled", "true")
        env.setActiveProfiles("dev")
        val service = context.getBean(AdminAccessService::class.java)
        val filter = FirebaseTokenFilter(EnvironmentExposurePolicy(env), userService, penalties, firebase, service)
        admin.authOrigin = UserAuthOrigin.EMAIL_PASSWORD
        MockMvcBuilders.standaloneSetup(AdminAuthController(service)).addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(filter)
            .build().perform(post("/api/admin-auth/verify").credentials("password")
                .requestAttr(FirebaseAppCheckFilter.VALID_APP_ID_ATTRIBUTE, "1:123:web:admin")
                .contentType("application/json").content("""{"code":"123456"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ADMIN_ACCESS_DENIED"))
        verifyNoInteractions(states, grants)
    }

    private fun stubToken(raw: String, user: User, provider: String = "google.com", emailVerified: Boolean = true) {
        val decoded = mock(FirebaseToken::class.java)
        `when`(decoded.uid).thenReturn(user.firebaseUid)
        `when`(decoded.email).thenReturn(user.email)
        `when`(decoded.isEmailVerified).thenReturn(emailVerified)
        `when`(decoded.claims).thenReturn(mapOf("auth_time" to now.epochSecond, "firebase" to mapOf("sign_in_provider" to provider)))
        `when`(firebase.verifyAdministrative(raw)).thenReturn(decoded)
    }

    private fun issueGrant(): String {
        val response = verifyTotp("web").andExpect(status().isOk).andReturn().response
        return mapper.readTree(response.contentAsString).get("grant").stringValue()
    }

    private fun verifyTotp(app: String?, bearer: String = "google"): org.springframework.test.web.servlet.ResultActions {
        val key = SecretKeySpec("12345678901234567890".toByteArray(), "HmacSHA1")
        val code = TimeBasedOneTimePasswordGenerator().generateOneTimePasswordString(key, now)
        return mvc.perform(post("/api/admin-auth/verify").credentials(bearer = bearer, app = app)
            .contentType("application/json").content("""{"code":"$code"}"""))
    }

    private fun denied(
        path: String, httpStatus: Int, code: String,
        bearer: String? = "google", app: String? = "web", grant: String? = null
    ) {
        mvc.perform(post(path).credentials(bearer, app, grant))
            .andExpect(status().`is`(httpStatus))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    private fun MockHttpServletRequestBuilder.credentials(
        bearer: String? = "google", app: String? = "web", grant: String? = null
    ) = apply {
        if (bearer != null) header("Authorization", "Bearer $bearer")
        if (app != null) header("X-Firebase-AppCheck", app)
        if (grant != null) header("X-Reals-Admin-Grant", grant)
    }

    private companion object {
        const val MATCHMAKING = "/api/local-dev/matchmaking/process"
        const val SCHEDULING = "/api/local-dev/jobs/scheduling-activation/run"
        const val PAIR_RESET = "/api/local-dev/pair-history/reset"
    }
}
