package com.reals.backend.integration.controller

import com.reals.backend.integration.ControllerIT
import com.reals.backend.repository.SharedContactRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.reals.backend.service.contacts.ContactParticipationService
import com.reals.backend.service.contacts.ContactRequestError
import com.reals.backend.service.contacts.ContactSelection
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import kotlin.test.*

class SharedContactsDisabledIntegrationTest : ControllerIT() {
    @Autowired lateinit var contacts: SharedContactRepository

    @Autowired lateinit var participation: ContactParticipationService

    @Test fun `disabled imports reject additions and preserve the selection`() {
        val owner = userService.createUser("disabled-add@example.invalid").id
        userRepository.flush()
        val now = OffsetDateTime.now()
        contacts.accept(owner, 1, now)
        contacts.synchronize(owner, setOf(ByteBuffer.wrap(ByteArray(32))), 1, now, false)
        val before = participation.status(owner)
        assertFalse(before.importAvailable)
        assertEquals(1, before.totalDistinctCount)
        val error = assertFailsWith<ContactRequestError> {
            participation.add(owner, ContactSelection(emptyList()), before.mutationRevision)
        }
        assertEquals("CONTACT_IMPORT_DISABLED", error.code)
        assertEquals(before, participation.status(owner))
        assertEquals(1, contacts.identifiers(owner).size)
    }

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
