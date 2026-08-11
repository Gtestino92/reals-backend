package com.reals.backend.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.util.UUID

class UserAuthOriginMigrationTest {

    @Test
    fun `migration backfills firebase users and leaves backend legacy users null`() {
        val jdbc = jdbcTemplate()
        val firebaseUserId = UUID.randomUUID()
        val legacyUserId = UUID.randomUUID()
        createUsersTableAtV37(jdbc)
        insertUser(jdbc, firebaseUserId, "firebase-${UUID.randomUUID()}", "firebase@example.com")
        insertUser(jdbc, legacyUserId, null, "legacy@example.com")

        migrateToV38(jdbc)

        assertEquals(
            "EMAIL_PASSWORD",
            jdbc.queryForObject("SELECT auth_origin FROM users WHERE id = ?", String::class.java, firebaseUserId)
        )
        assertNull(
            jdbc.queryForObject("SELECT auth_origin FROM users WHERE id = ?", String::class.java, legacyUserId)
        )
    }

    @Test
    fun `migration constraint accepts supported auth origins only`() {
        val jdbc = jdbcTemplate()
        createUsersTableAtV37(jdbc)

        migrateToV38(jdbc)

        insertUser(jdbc, UUID.randomUUID(), "firebase-${UUID.randomUUID()}", "password@example.com", "EMAIL_PASSWORD")
        insertUser(jdbc, UUID.randomUUID(), "firebase-${UUID.randomUUID()}", "google@example.com", "GOOGLE")
        insertUser(jdbc, UUID.randomUUID(), null, "legacy@example.com", null)

        assertThrows<DataAccessException> {
            insertUser(jdbc, UUID.randomUUID(), "firebase-${UUID.randomUUID()}", "apple@example.com", "APPLE")
        }
    }

    private fun createUsersTableAtV37(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            CREATE TABLE users (
                id UUID PRIMARY KEY,
                version BIGINT NOT NULL DEFAULT 0,
                email VARCHAR(255),
                firebase_uid VARCHAR(255),
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                deleted_at TIMESTAMP WITH TIME ZONE NULL,
                deletion_finalizes_at TIMESTAMP WITH TIME ZONE NULL,
                CONSTRAINT uq_users_email UNIQUE (email),
                CONSTRAINT uq_users_firebase_uid UNIQUE (firebase_uid)
            )
            """.trimIndent()
        )
    }

    private fun insertUser(
        jdbc: JdbcTemplate,
        userId: UUID,
        firebaseUid: String?,
        email: String,
        authOrigin: String? = null
    ) {
        if (authOrigin == null && !hasAuthOriginColumn(jdbc)) {
            jdbc.update(
                """
                INSERT INTO users (id, version, email, firebase_uid, created_at, updated_at)
                VALUES (?, 0, ?, ?, now(), now())
                """.trimIndent(),
                userId,
                email,
                firebaseUid
            )
        } else {
            jdbc.update(
                """
                INSERT INTO users (id, version, email, firebase_uid, auth_origin, created_at, updated_at)
                VALUES (?, 0, ?, ?, ?, now(), now())
                """.trimIndent(),
                userId,
                email,
                firebaseUid,
                authOrigin
            )
        }
    }

    private fun hasAuthOriginColumn(jdbc: JdbcTemplate): Boolean =
        jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'USERS'
            """.trimIndent(),
            String::class.java
        ).map { it!!.lowercase() }.contains("auth_origin")

    private fun migrateToV38(jdbc: JdbcTemplate) {
        Flyway.configure()
            .dataSource(jdbc.dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("37")
            .target("38")
            .load()
            .migrate()
    }

    private fun jdbcTemplate(): JdbcTemplate =
        JdbcTemplate(
            SingleConnectionDataSource(
                DriverManager.getConnection(
                    "jdbc:h2:mem:user-auth-origin-migration-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                    "sa",
                    ""
                ),
                true
            )
        )
}
