package com.reals.backend.controller.localdev

import com.reals.backend.config.WebMvcConfig
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.SecurityConfig
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.currentuser.CurrentUserAuthArgumentResolver
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.config.security.currentuser.CurrentUserIdArgumentResolver
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import com.reals.backend.service.localdev.LocalFirebaseEmailVerificationFailedException
import com.reals.backend.service.localdev.LocalFirebaseEmailVerificationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [LocalFirebaseEmailVerificationController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [FirebaseTokenFilter::class, RateLimitFilter::class, PostAuthenticationRateLimitFilter::class]
        )
    ]
)
@Import(
    SecurityConfig::class,
    EnvironmentExposurePolicy::class,
    WebMvcConfig::class,
    CurrentUserAuthArgumentResolver::class,
    CurrentUserIdArgumentResolver::class
)
@ActiveProfiles("local-firebase")
@TestPropertySource(properties = ["local-dev.firebase.email-auto-verification-enabled=true"])
class LocalFirebaseEmailVerificationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var localFirebaseEmailVerificationService: LocalFirebaseEmailVerificationService

    @Test
    fun `anonymous request is rejected`() {
        mockMvc.perform(post("/api/me/local-dev/email-verification"))
            .andExpect(status().isUnauthorized)

        verifyNoInteractions(localFirebaseEmailVerificationService)
    }

    @Test
    fun `unprovisioned firebase principal cannot call endpoint`() {
        mockMvc.perform(
            post("/api/me/local-dev/email-verification")
                .with(firebaseAuthenticatedOnly())
        )
            .andExpect(status().isForbidden)

        verifyNoInteractions(localFirebaseEmailVerificationService)
    }

    @Test
    fun `provisioned firebase backed user receives no content`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        mockMvc.perform(
            post("/api/me/local-dev/email-verification")
                .with(provisionedFirebaseUser(firebaseUid = firebaseUid))
        )
            .andExpect(status().isNoContent)

        verify(localFirebaseEmailVerificationService).verifyEmail(firebaseUid)
    }

    @Test
    fun `request body uid and email are ignored in favor of authenticated firebase uid`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        mockMvc.perform(
            post("/api/me/local-dev/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"firebaseUid":"other-uid","email":"other@example.com"}""")
                .with(provisionedFirebaseUser(firebaseUid = firebaseUid))
        )
            .andExpect(status().isNoContent)

        verify(localFirebaseEmailVerificationService).verifyEmail(firebaseUid)
    }

    @Test
    fun `null firebase uid fails safely without invoking service`() {
        mockMvc.perform(
            post("/api/me/local-dev/email-verification")
                .with(provisionedFirebaseUser(firebaseUid = null))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(localFirebaseEmailVerificationService)
    }

    @Test
    fun `firebase admin failure does not return no content`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"
        doThrow(LocalFirebaseEmailVerificationFailedException(RuntimeException()))
            .`when`(localFirebaseEmailVerificationService).verifyEmail(firebaseUid)

        mockMvc.perform(
            post("/api/me/local-dev/email-verification")
                .with(provisionedFirebaseUser(firebaseUid = firebaseUid))
        )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("LOCAL_FIREBASE_EMAIL_VERIFICATION_FAILED"))
    }

    private fun provisionedFirebaseUser(
        firebaseUid: String?
    ) = authentication(
        UsernamePasswordAuthenticationToken(
            CurrentUserAuthContext(
                userId = UUID.randomUUID(),
                firebaseUid = firebaseUid,
                email = "local-user@example.com",
                emailVerified = false
            ),
            null,
            listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
        )
    )

    private fun firebaseAuthenticatedOnly() = authentication(
        UsernamePasswordAuthenticationToken(
            FirebasePrincipal(
                uid = "unprovisioned-${UUID.randomUUID()}",
                email = "unprovisioned@example.com",
                emailVerified = false
            ),
            null,
            listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_FIREBASE_AUTHENTICATED))
        )
    )
}
