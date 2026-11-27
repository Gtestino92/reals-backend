package com.reals.backend.integration.postgres

import com.reals.backend.repository.SharedContactRepository
import com.reals.backend.service.contacts.ContactParticipationService
import com.reals.backend.service.contacts.ContactSyncState
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestPropertySource(properties = ["shared-contacts.import-enabled=false"])
class SharedContactStatusDisabledPostgresIT : PostgresITBase() {
    @Autowired lateinit var contacts: SharedContactRepository
    @Autowired lateinit var participation: ContactParticipationService

    @Test fun `existing synchronized identifiers are counted with imports disabled`() {
        val owner = userService.createUser("disabled-status@example.invalid").id
        userRepository.flush()
        val now = OffsetDateTime.now()
        contacts.accept(owner, 1, now)
        contacts.synchronize(owner, setOf(ByteBuffer.wrap(ByteArray(32))), 1, now, invalidate = false)

        val status = participation.status(owner)
        assertFalse(status.importAvailable)
        assertEquals(ContactSyncState.SYNCED, status.state)
        assertEquals(1, status.totalDistinctCount)
    }
}
