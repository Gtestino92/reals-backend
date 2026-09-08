package com.reals.backend.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class MatchmakingCandidateQueryIndexesMigrationTest {

    @Test
    fun `matchmaking candidate query indexes are created with expected columns and predicates`() {
        val jdbc = createIsolatedJdbcTemplate()

        Flyway.configure()
            .dataSource(jdbc.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        assertIndex(
            jdbc = jdbc,
            indexName = "idx_matchmaking_queue_waiting_order",
            tableName = "matchmaking_queue",
            columns = listOf("entered_at", "id"),
            predicateTokens = listOf("status", "WAITING")
        )
        assertIndex(
            jdbc = jdbc,
            indexName = "idx_matches_active_pair",
            tableName = "matches",
            columns = listOf("user_a_id", "user_b_id"),
            predicateTokens = listOf("state", "CHAT_ACTIVE", "VISUAL_PHASE", "VISUAL_APPROVED")
        )
        assertIndex(
            jdbc = jdbc,
            indexName = "idx_matches_terminal_pair_state_updated",
            tableName = "matches",
            columns = listOf("user_a_id", "user_b_id", "state", "updated_at"),
            predicateTokens = listOf("state", "CHAT_REJECTED", "VISUAL_REJECTED", "EXPIRED")
        )
        assertIndex(
            jdbc = jdbc,
            indexName = "idx_connections_active_pair",
            tableName = "connections",
            columns = listOf("user_a_id", "user_b_id"),
            predicateTokens = listOf("state", "CLOSED")
        )
        assertIndex(
            jdbc = jdbc,
            indexName = "idx_connections_closed_pair_updated",
            tableName = "connections",
            columns = listOf("user_a_id", "user_b_id", "updated_at"),
            predicateTokens = listOf("state", "CLOSED")
        )
    }

    private fun assertIndex(
        jdbc: JdbcTemplate,
        indexName: String,
        tableName: String,
        columns: List<String>,
        predicateTokens: List<String>
    ) {
        assertEquals(tableName, tableForIndex(jdbc, indexName))
        assertEquals(columns, columnsForIndex(jdbc, indexName))

        val predicate = predicateForIndex(jdbc, indexName)
        predicateTokens.forEach { token ->
            assertTrue(
                predicate.contains(token, ignoreCase = true),
                "Expected predicate for $indexName to contain $token, got: $predicate"
            )
        }
    }

    private fun tableForIndex(
        jdbc: JdbcTemplate,
        indexName: String
    ): String =
        jdbc.queryForObject(
            """
            SELECT table_class.relname
            FROM pg_class index_class
            JOIN pg_index index_info
                ON index_info.indexrelid = index_class.oid
            JOIN pg_class table_class
                ON table_class.oid = index_info.indrelid
            WHERE index_class.relname = ?
            """.trimIndent(),
            String::class.java,
            indexName
        ) ?: error("Index not found: $indexName")

    private fun columnsForIndex(
        jdbc: JdbcTemplate,
        indexName: String
    ): List<String> =
        jdbc.queryForList(
            """
            SELECT attribute.attname
            FROM pg_class index_class
            JOIN pg_index index_info
                ON index_info.indexrelid = index_class.oid
            JOIN unnest(index_info.indkey) WITH ORDINALITY AS indexed_column(attribute_number, column_order)
                ON true
            JOIN pg_attribute attribute
                ON attribute.attrelid = index_info.indrelid
                AND attribute.attnum = indexed_column.attribute_number
            WHERE index_class.relname = ?
            ORDER BY indexed_column.column_order
            """.trimIndent(),
            String::class.java,
            indexName
        ).map { it!! }

    private fun predicateForIndex(
        jdbc: JdbcTemplate,
        indexName: String
    ): String =
        jdbc.queryForObject(
            """
            SELECT pg_get_expr(index_info.indpred, index_info.indrelid)
            FROM pg_class index_class
            JOIN pg_index index_info
                ON index_info.indexrelid = index_class.oid
            WHERE index_class.relname = ?
            """.trimIndent(),
            String::class.java,
            indexName
        ) ?: error("Index predicate not found: $indexName")

    private fun createIsolatedJdbcTemplate(): JdbcTemplate {
        val schema = "matchmaking_indexes_${UUID.randomUUID().toString().replace("-", "")}"
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
