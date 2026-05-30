package com.reals.backend.integration.service

import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

class UserServiceIntegrationTest : BaseIT() {

    @Test
    fun `find or create creates user from firebase uid and email`() {
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        val user = userService.findOrCreate(
            firebaseUid = firebaseUid,
            email = "Firebase.User@Example.com"
        )

        assertEquals(firebaseUid, user.firebaseUid)
        assertEquals("firebase.user@example.com", user.email)
        assertNotNull(user.id)
    }

    @Test
    fun `find or create links existing email user to firebase uid`() {
        val existing = userService.createUser("linked-${UUID.randomUUID()}@example.com")
        val firebaseUid = "firebase-${UUID.randomUUID()}"

        val linked = userService.findOrCreate(
            firebaseUid = firebaseUid,
            email = existing.email
        )

        assertEquals(existing.id, linked.id)
        assertEquals(firebaseUid, linked.firebaseUid)
        assertEquals(existing.email, linked.email)
    }
}
