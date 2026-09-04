package com.reals.backend.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class PermanentBanAppealMigrationTest {

    @Test
    fun `V47 fails closed when duplicate active permanent bans already exist`() {
        val jdbc = createIsolatedJdbcTemplate()
        val flywayToV46 = Flyway.configure()
            .dataSource(jdbc.dataSource)
            .locations("classpath:db/migration")
            .target("46")
            .load()

        flywayToV46.migrate()

        val userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email) VALUES (?, ?)",
            userId,
            "duplicate-permanent-before-v47@example.com"
        )
        repeat(2) { index ->
            jdbc.update(
                """
                INSERT INTO penalties (id, user_id, reason, type, expires_at, active)
                VALUES (?, ?, ?, 'PERMANENT_BAN', NULL, true)
                """.trimIndent(),
                UUID.randomUUID(),
                userId,
                "Duplicate permanent $index"
            )
        }

        val exception = assertThrows<Exception> {
            Flyway.configure()
                .dataSource(jdbc.dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        assertTrue(
            exception.message.orEmpty().contains("duplicate active permanent bans", ignoreCase = true) ||
                exception.cause?.message.orEmpty().contains("duplicate active permanent bans", ignoreCase = true)
        )
    }

    private fun createIsolatedJdbcTemplate(): JdbcTemplate {
        val schema = "permanent_ban_appeal_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = DriverManagerDataSource(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password
        )
        JdbcTemplate(adminDataSource).execute("CREATE SCHEMA $schema")

        val dataSource = DriverManagerDataSource(
            postgres.jdbcUrl.withJdbcParameter("currentSchema", schema),
            postgres.username,
            postgres.password
        )
        return JdbcTemplate(dataSource)
    }

    private fun String.withJdbcParameter(
        name: String,
        value: String
    ): String {
        val separator =
            when {
                contains("?") && !endsWith("?") && !endsWith("&") -> "&"
                endsWith("?") || endsWith("&") -> ""
                else -> "?"
            }

        return "$this$separator$name=$value"
    }

    private class KPostgreSQLContainer(imageName: String) :
        PostgreSQLContainer<KPostgreSQLContainer>(imageName)

    companion object {
        @Container
        @JvmStatic
        private val postgres = KPostgreSQLContainer("postgres:16-alpine")
    }
}
