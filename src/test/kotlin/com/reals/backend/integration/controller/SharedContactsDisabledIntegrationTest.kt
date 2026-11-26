package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import com.reals.backend.repository.SharedContactRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import kotlin.test.*

class SharedContactsDisabledIntegrationTest : ControllerIT() {
    @Autowired lateinit var contacts: SharedContactRepository

    @Test fun `owner can erase stale selections and withdraw with disabled feature and no secret`() {
        val owner = userService.createUser("disabled@example.invalid").id
        userRepository.flush()
        val now = OffsetDateTime.now()
        contacts.accept(owner,99,now)
        contacts.synchronize(owner,setOf(ByteBuffer.wrap(ByteArray(32))),99,now,false)
        val another = userService.createUser("another@example.invalid").id
        val base = "/api/me/shared-contacts"
        mockMvc.perform(delete("$base/consent").with(authenticatedAs(another))).andExpect(status().isNoContent)
        assertEquals(1,contacts.identifiers(owner).size)
        mockMvc.perform(delete("$base/selection").with(authenticatedAs(owner))).andExpect(status().isNoContent)
        assertNull(contacts.participation(owner)?.keyVersion)
        assertTrue(contacts.identifiers(owner).isEmpty())
        mockMvc.perform(delete("$base/consent").with(authenticatedAs(owner))).andExpect(status().isNoContent)
        assertNull(contacts.participation(owner))
    }
}
