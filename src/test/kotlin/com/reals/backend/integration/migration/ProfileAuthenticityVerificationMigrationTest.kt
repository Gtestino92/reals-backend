package com.reals.backend.integration.migration

import com.reals.backend.domain.AuditEventType
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource
import java.util.UUID

class ProfileAuthenticityVerificationMigrationTest {

    @Test
    fun `migration renames profile verification columns normalizes boolean and enforces invariant`() {
        val dataSource = dataSource()
        val jdbc = JdbcTemplate(dataSource)
        val userId = UUID.randomUUID()
        val auditEventId = UUID.randomUUID()
        val expectedProfiles = listOf(
            LegacyProfileCase(identityVerified = true, status = "VERIFIED", expectedAuthenticityVerified = true),
            LegacyProfileCase(identityVerified = false, status = "VERIFIED", expectedAuthenticityVerified = true),
            LegacyProfileCase(identityVerified = true, status = "NOT_STARTED", expectedAuthenticityVerified = false),
            LegacyProfileCase(identityVerified = true, status = "PENDING", expectedAuthenticityVerified = false),
            LegacyProfileCase(identityVerified = true, status = "REJECTED", expectedAuthenticityVerified = false),
            LegacyProfileCase(identityVerified = true, status = "NEEDS_REVIEW", expectedAuthenticityVerified = false),
            LegacyProfileCase(identityVerified = true, status = "STALE", expectedAuthenticityVerified = false)
        )

        createSchemaAtV23(jdbc)
        insertUser(jdbc, userId)
        expectedProfiles.forEach { insertLegacyProfile(jdbc, userId, it) }
        jdbc.update(
            """
            INSERT INTO audit_events (
                id, event_type, aggregate_type, aggregate_id, actor_user_id, created_at
            )
            VALUES (?, 'IDENTITY_VERIFICATION_UPDATED', 'PROFILE', ?, ?, now())
            """.trimIndent(),
            auditEventId,
            expectedProfiles.first().id,
            userId
        )

        migrateFromV23(dataSource)

        val columns = jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'PROFILES'
            """.trimIndent(),
            String::class.java
        ).map { it!!.lowercase() }.toSet()
        assertTrue(columns.contains("authenticity_verified"))
        assertTrue(columns.contains("authenticity_verification_status"))
        assertFalse(columns.contains("identity_verified"))
        assertFalse(columns.contains("identity_verification_status"))

        expectedProfiles.forEach { expected ->
            assertEquals(
                expected.expectedAuthenticityVerified,
                jdbc.queryForObject(
                    "SELECT authenticity_verified FROM profiles WHERE id = ?",
                    Boolean::class.java,
                    expected.id
                ),
                "authenticity_verified mismatch for ${expected.identityVerified} + ${expected.status}"
            )
            assertEquals(
                expected.status,
                jdbc.queryForObject(
                    "SELECT authenticity_verification_status FROM profiles WHERE id = ?",
                    String::class.java,
                    expected.id
                ),
                "status should not be changed for ${expected.identityVerified} + ${expected.status}"
            )
        }

        val eventType = jdbc.queryForObject(
            "SELECT event_type FROM audit_events WHERE id = ?",
            String::class.java,
            auditEventId
        )
        assertEquals("PROFILE_AUTHENTICITY_VERIFICATION_UPDATED", eventType)
        assertEquals(AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED, AuditEventType.valueOf(eventType!!))

        assertRejectedByInvariant(jdbc, userId, authenticityVerified = true, status = "NOT_STARTED")
        assertRejectedByInvariant(jdbc, userId, authenticityVerified = false, status = "VERIFIED")

        insertProfile(jdbc, userId, authenticityVerified = true, status = "VERIFIED")
        insertProfile(jdbc, userId, authenticityVerified = false, status = "NOT_STARTED")
        insertProfile(jdbc, userId, authenticityVerified = false, status = "PENDING")
        insertProfile(jdbc, userId, authenticityVerified = false, status = "REJECTED")
        insertProfile(jdbc, userId, authenticityVerified = false, status = "NEEDS_REVIEW")
        insertProfile(jdbc, userId, authenticityVerified = false, status = "STALE")
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(
            "jdbc:h2:mem:authenticity-migration-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        )

    private fun createSchemaAtV23(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            CREATE TABLE users (
                id UUID PRIMARY KEY,
                version BIGINT NOT NULL,
                email VARCHAR(255),
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE profiles (
                id UUID PRIMARY KEY,
                version BIGINT NOT NULL,
                user_id UUID NOT NULL,
                display_name VARCHAR(100) NOT NULL,
                birth_date DATE NOT NULL,
                identity_verified BOOLEAN NOT NULL,
                gender VARCHAR(16) NOT NULL,
                intention VARCHAR(16) NOT NULL,
                city VARCHAR(100) NOT NULL,
                country VARCHAR(100) NOT NULL,
                status VARCHAR(32) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                preferred_min_age INT NOT NULL,
                preferred_max_age INT NOT NULL,
                max_distance_km INT NOT NULL,
                identity_verification_status VARCHAR(50) NOT NULL
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE audit_events (
                id UUID PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                aggregate_type VARCHAR(64) NOT NULL,
                aggregate_id UUID NOT NULL,
                actor_user_id UUID,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateFromV23(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("23")
            .target("25")
            .load()
            .migrate()
    }

    private fun insertUser(jdbc: JdbcTemplate, userId: UUID) {
        jdbc.update(
            """
            INSERT INTO users (id, version, email, created_at, updated_at)
            VALUES (?, 0, ?, now(), now())
            """.trimIndent(),
            userId,
            "migration-${UUID.randomUUID()}@example.com"
        )
    }

    private fun insertLegacyProfile(
        jdbc: JdbcTemplate,
        userId: UUID,
        profile: LegacyProfileCase
    ) {
        jdbc.update(
            """
            INSERT INTO profiles (
                id, version, user_id, display_name, birth_date, identity_verified,
                gender, intention, city, country, status, created_at, updated_at,
                preferred_min_age, preferred_max_age, max_distance_km,
                identity_verification_status
            )
            VALUES (?, 0, ?, ?, DATE '1995-01-01', ?,
                'FEMALE', 'DATE', 'Buenos Aires', 'AR', 'DRAFT', now(), now(),
                18, 99, 50, ?)
            """.trimIndent(),
            profile.id,
            userId,
            "Migration ${profile.status}",
            profile.identityVerified,
            profile.status
        )
    }

    private fun assertRejectedByInvariant(
        jdbc: JdbcTemplate,
        userId: UUID,
        authenticityVerified: Boolean,
        status: String
    ) {
        assertThrows<DataIntegrityViolationException> {
            insertProfile(jdbc, userId, authenticityVerified, status)
        }
    }

    private fun insertProfile(
        jdbc: JdbcTemplate,
        userId: UUID,
        authenticityVerified: Boolean,
        status: String
    ) {
        jdbc.update(
            """
            INSERT INTO profiles (
                id, version, user_id, display_name, birth_date, authenticity_verified,
                gender, intention, city, country, status, created_at, updated_at,
                preferred_min_age, preferred_max_age, max_distance_km,
                authenticity_verification_status
            )
            VALUES (?, 0, ?, ?, DATE '1995-01-01', ?,
                'FEMALE', 'DATE', 'Buenos Aires', 'AR', 'DRAFT', now(), now(),
                18, 99, 50, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            "Constraint $status",
            authenticityVerified,
            status
        )
    }

    private data class LegacyProfileCase(
        val identityVerified: Boolean,
        val status: String,
        val expectedAuthenticityVerified: Boolean,
        val id: UUID = UUID.randomUUID()
    )
}
