package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class MeControllerIntegrationTest : ControllerIT() {

    @Test
    fun `provision me creates backend user from firebase principal`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"
        val email = "firebase-${UUID.randomUUID()}@example.com"

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedWithFirebase(firebaseUid, email))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email", equalTo(email)))

        assertNotNull(userService.findByFirebaseUid(firebaseUid))
    }

    @Test
    fun `provision me returns existing backend user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "existing-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            post("/api/me/provision")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
    }

    @Test
    fun `unprovisioned firebase principal cannot access user endpoints`() {
        mockMvc.perform(
            get("/api/me")
                .with(
                    authenticatedWithFirebase(
                        firebaseUid = "firebase-${UUID.randomUUID()}",
                        email = "unprovisioned-${UUID.randomUUID()}@example.com"
                    )
                )
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code", equalTo("ACCESS_DENIED")))
    }
}
