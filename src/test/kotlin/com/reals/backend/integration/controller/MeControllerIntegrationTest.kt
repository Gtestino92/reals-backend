package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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

    @Test
    fun `delete me soft deletes authenticated user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "delete-me-${UUID.randomUUID()}@example.com"
        )

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)

        val deletedUser = userService.findByIdOrThrow(user.id)
        kotlin.test.assertEquals(com.reals.backend.domain.UserStatus.DELETED, deletedUser.status)
        kotlin.test.assertNotNull(deletedUser.deletedAt)
        kotlin.test.assertNotNull(deletedUser.deletionFinalizesAt)
        kotlin.test.assertEquals(user.email, deletedUser.email)
    }

    @Test
    fun `delete me returns conflict when account is already deleted`() {
        val user = userService.createUser("already-deleted-${UUID.randomUUID()}@example.com")
        userService.deleteUser(user.id)

        mockMvc.perform(
            delete("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("DOMAIN_CONFLICT")))
    }

    @Test
    fun `reactivate me restores deleted authenticated user`() {
        val user = userService.provisionFromFirebase(
            firebaseUid = "firebase-${UUID.randomUUID()}",
            email = "reactivate-me-${UUID.randomUUID()}@example.com"
        )
        userService.deleteUser(user.id)

        mockMvc.perform(
            post("/api/me/reactivation")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.deletedAt").doesNotExist())
            .andExpect(jsonPath("$.deletionFinalizesAt").doesNotExist())
    }

    @Test
    fun `get me returns pending deletion account state`() {
        val user = userService.createUser("deleted-me-${UUID.randomUUID()}@example.com")
        userService.deleteUser(user.id)

        mockMvc.perform(
            get("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
            .andExpect(jsonPath("$.status", equalTo("DELETED")))
            .andExpect(jsonPath("$.deletedAt", notNullValue()))
            .andExpect(jsonPath("$.deletionFinalizesAt", notNullValue()))
    }
}
