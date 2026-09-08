package com.reals.backend.integration.postgres

import com.reals.backend.domain.Gender
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class PermanentBanUniquenessPostgresIT : PostgresITBase() {

    @Test
    fun `partial unique index rejects multiple active permanent bans for one user`() {
        val userId = createActiveProfile(
            email = "postgres-permanent-unique-${UUID.randomUUID()}@example.com",
            displayName = "Postgres Permanent Unique",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

        jdbcTemplate.update(
            """
            INSERT INTO penalties (id, user_id, reason, type, expires_at, active)
            VALUES (?, ?, 'First permanent', 'PERMANENT_BAN', NULL, true)
            """.trimIndent(),
            UUID.randomUUID(),
            userId
        )

        val exception = org.junit.jupiter.api.assertThrows<DataIntegrityViolationException> {
            jdbcTemplate.update(
                """
                INSERT INTO penalties (id, user_id, reason, type, expires_at, active)
                VALUES (?, ?, 'Second permanent', 'PERMANENT_BAN', NULL, true)
                """.trimIndent(),
                UUID.randomUUID(),
                userId
            )
        }
        assertTrue(exception.message.orEmpty().contains("uq_penalties_one_active_permanent_ban_per_user"))
    }
}
