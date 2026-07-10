package com.reals.backend.integration.migration

import com.reals.backend.domain.AuditEventType
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class ProfileAuthenticityVerificationMigrationTest {

    @Test
    fun `migration renames profile verification columns and preserves status values`() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:authenticity-migration-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        )
        val jdbc = JdbcTemplate(dataSource)
        val userId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        val auditEventId = UUID.randomUUID()

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

        jdbc.update(
            """
            INSERT INTO users (id, version, email, created_at, updated_at)
            VALUES (?, 0, ?, now(), now())
            """.trimIndent(),
            userId,
            "migration-${UUID.randomUUID()}@example.com"
        )
        jdbc.update(
            """
            INSERT INTO profiles (
                id, version, user_id, display_name, birth_date, identity_verified,
                gender, intention, city, country, status, created_at, updated_at,
                preferred_min_age, preferred_max_age, max_distance_km,
                identity_verification_status
            )
            VALUES (?, 0, ?, 'Migration Profile', DATE '1995-01-01', true,
                'FEMALE', 'DATE', 'Buenos Aires', 'AR', 'DRAFT', now(), now(),
                18, 99, 50, 'VERIFIED')
            """.trimIndent(),
            profileId,
            userId
        )
        jdbc.update(
            """
            INSERT INTO audit_events (
                id, event_type, aggregate_type, aggregate_id, actor_user_id, created_at
            )
            VALUES (?, 'IDENTITY_VERIFICATION_UPDATED', 'PROFILE', ?, ?, now())
            """.trimIndent(),
            auditEventId,
            profileId,
            userId
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("23")
            .target("24")
            .load()
            .migrate()

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

        assertEquals(
            true,
            jdbc.queryForObject(
                "SELECT authenticity_verified FROM profiles WHERE id = ?",
                Boolean::class.java,
                profileId
            )
        )
        assertEquals(
            "VERIFIED",
            jdbc.queryForObject(
                "SELECT authenticity_verification_status FROM profiles WHERE id = ?",
                String::class.java,
                profileId
            )
        )

        val eventType = jdbc.queryForObject(
            "SELECT event_type FROM audit_events WHERE id = ?",
            String::class.java,
            auditEventId
        )
        assertEquals("PROFILE_AUTHENTICITY_VERIFICATION_UPDATED", eventType)
        assertEquals(AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED, AuditEventType.valueOf(eventType!!))
    }
}
