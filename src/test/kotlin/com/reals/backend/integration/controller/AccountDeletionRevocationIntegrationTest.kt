package com.reals.backend.integration.controller

import com.google.firebase.ErrorCode
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.domain.UserStatus
import com.reals.backend.integration.ControllerIT
import com.reals.backend.service.identity.FirebaseExternalAccountService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class AccountDeletionRevocationIntegrationTest : ControllerIT() {

    @MockitoBean
    private lateinit var firebaseExternalAccountService: FirebaseExternalAccountService

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete me reports success when Firebase token revocation succeeds`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-revocation-success-${UUID.randomUUID()}",
            email = "revocation-success-${UUID.randomUUID()}@example.com"
        )
        val firebaseUid = user.firebaseUid!!

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)

        Mockito.verify(firebaseExternalAccountService).revokeRefreshTokens(firebaseUid)
        assertEquals(UserStatus.DELETED, userRepository.findById(user.id).orElseThrow().status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete me does not report success when Firebase token revocation fails`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-revocation-failure-${UUID.randomUUID()}",
            email = "revocation-failure-${UUID.randomUUID()}@example.com"
        )
        val firebaseUid = user.firebaseUid!!
        Mockito.doThrow(firebaseAuthException("revocation failed"))
            .`when`(firebaseExternalAccountService)
            .revokeRefreshTokens(firebaseUid)

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code", equalTo("INTERNAL_ERROR")))

        Mockito.verify(firebaseExternalAccountService).revokeRefreshTokens(firebaseUid)
        assertEquals(UserStatus.DELETED, userRepository.findById(user.id).orElseThrow().status)
    }

    private fun firebaseAuthException(message: String): FirebaseAuthException =
        FirebaseAuthException(
            ErrorCode.UNAVAILABLE,
            message,
            null,
            null,
            null
        )
}
