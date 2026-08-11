package com.reals.backend.integration.controller

import com.reals.backend.config.security.authentication.FirebaseSignInProvider
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.PasswordResetEmailDeliveryService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class AuthControllerIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var passwordResetEmailDeliveryService: PasswordResetEmailDeliveryService

    @Test
    fun `syntactically valid unknown email returns generic accepted without bearer token`() {
        mockMvc.perform(
            post("/api/auth/password-reset")
                .contentType(jsonContentType)
                .content("""{"email":"unknown-${UUID.randomUUID()}@example.com"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(content().string(""))

        verify(passwordResetEmailDeliveryService, never()).sendPasswordResetEmail(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `syntactically valid google email returns generic accepted without delivery`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-reset-google-${UUID.randomUUID()}",
            email = "reset-google-${UUID.randomUUID()}@example.com",
            signInProvider = FirebaseSignInProvider.GOOGLE
        )

        mockMvc.perform(
            post("/api/auth/password-reset")
                .contentType(jsonContentType)
                .content("""{"email":"${user.email}"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(content().string(""))

        verify(passwordResetEmailDeliveryService, never()).sendPasswordResetEmail(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `syntactically valid password email returns generic accepted with delivery`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-reset-password-${UUID.randomUUID()}",
            email = "reset-password-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            post("/api/auth/password-reset")
                .contentType(jsonContentType)
                .content("""{"email":"${user.email!!.uppercase()}"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(content().string(""))

        verify(passwordResetEmailDeliveryService).sendPasswordResetEmail(user.email!!)
    }

    @Test
    fun `invalid email format returns validation error`() {
        mockMvc.perform(
            post("/api/auth/password-reset")
                .contentType(jsonContentType)
                .content("""{"email":"not-an-email"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `firebase delivery failure still returns generic accepted`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-reset-failure-${UUID.randomUUID()}",
            email = "reset-failure-${UUID.randomUUID()}@example.com"
        )
        doThrow(RuntimeException("delivery failed"))
            .`when`(passwordResetEmailDeliveryService).sendPasswordResetEmail(user.email!!)

        mockMvc.perform(
            post("/api/auth/password-reset")
                .contentType(jsonContentType)
                .content("""{"email":"${user.email}"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(content().string(""))
    }
}
