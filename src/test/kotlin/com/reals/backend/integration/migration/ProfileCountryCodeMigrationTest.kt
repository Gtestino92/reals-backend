package com.reals.backend.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class ProfileCountryCodeMigrationTest {

    @Test
    fun `migration normalizes valid alpha two country values and renames column`() {
        val jdbc = createIsolatedJdbcTemplate()
        val profileId = UUID.randomUUID()
        createLegacyProfilesTable(jdbc)
        jdbc.update(
            "INSERT INTO profiles (id, country) VALUES (?, ?)",
            profileId,
            " ar "
        )

        migrateCountryCode(jdbc)

        val columns = profileColumns(jdbc)
        assertTrue(columns.contains("country_code"))
        assertFalse(columns.contains("country"))
        assertEquals(
            "AR",
            jdbc.queryForObject(
                "SELECT country_code FROM profiles WHERE id = ?",
                String::class.java,
                profileId
            )
        )
    }

    @Test
    fun `migration country code column enforces uppercase alpha two format`() {
        val jdbc = createIsolatedJdbcTemplate()
        createLegacyProfilesTable(jdbc)
        jdbc.update(
            "INSERT INTO profiles (id, country) VALUES (?, ?)",
            UUID.randomUUID(),
            "UY"
        )

        migrateCountryCode(jdbc)

        assertThrows<DataAccessException> {
            jdbc.update(
                "INSERT INTO profiles (id, country_code) VALUES (?, ?)",
                UUID.randomUUID(),
                "ar"
            )
        }
        assertThrows<DataAccessException> {
            jdbc.update(
                "INSERT INTO profiles (id, country_code) VALUES (?, ?)",
                UUID.randomUUID(),
                "ARG"
            )
        }
    }

    @Test
    fun `migration fails explicitly when legacy free text country values exist`() {
        val jdbc = createIsolatedJdbcTemplate()
        createLegacyProfilesTable(jdbc)
        jdbc.update(
            "INSERT INTO profiles (id, country) VALUES (?, ?)",
            UUID.randomUUID(),
            "Argentina"
        )

        val exception = assertThrows<Exception> {
            migrateCountryCode(jdbc)
        }

        assertTrue(
            exception.message.orEmpty().contains(
                "profiles.country contains legacy non-alpha-2 country values"
            ),
            "Expected migration failure message to explain legacy country values, got: ${exception.message}"
        )
    }

    private fun createLegacyProfilesTable(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            CREATE TABLE profiles (
                id UUID PRIMARY KEY,
                country VARCHAR(100) NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateCountryCode(jdbc: JdbcTemplate) {
        Flyway.configure()
            .dataSource(jdbc.dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("24")
            .load()
            .migrate()
    }

    private fun profileColumns(jdbc: JdbcTemplate): Set<String> =
        jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'profiles'
            """.trimIndent(),
            String::class.java
        ).map { it!!.lowercase() }.toSet()

    private fun createIsolatedJdbcTemplate(): JdbcTemplate {
        val schema = "country_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = DriverManagerDataSource(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password
        )
        JdbcTemplate(adminDataSource).execute("CREATE SCHEMA $schema")

        val dataSource = DriverManagerDataSource(
            "${postgres.jdbcUrl}?currentSchema=$schema",
            postgres.username,
            postgres.password
        )
        return JdbcTemplate(dataSource)
    }

    private class KPostgreSQLContainer(imageName: String) :
        PostgreSQLContainer<KPostgreSQLContainer>(imageName)

    companion object {
        @Container
        @JvmStatic
        private val postgres = KPostgreSQLContainer("postgres:16-alpine")
    }
}
