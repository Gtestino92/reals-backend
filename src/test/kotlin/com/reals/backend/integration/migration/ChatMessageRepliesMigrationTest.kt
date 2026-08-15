package com.reals.backend.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
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
class ChatMessageRepliesMigrationTest {

    @Test
    fun `chat message replies migration allows text ids and enforces reply invariants`() {
        val jdbc = createIsolatedJdbcTemplate()
        Flyway.configure()
            .dataSource(jdbc.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val userA = insertUser(jdbc)
        val userB = insertUser(jdbc)
        val matchId = insertMatch(jdbc, userA, userB)
        val chatId = insertChat(jdbc, matchId)
        val snapshotId = insertPromptSnapshot(jdbc, chatId)
        val clientMessageId = UUID.randomUUID()
        val targetMessageId = insertTextMessage(jdbc, chatId, userB, UUID.randomUUID(), "target")

        val textWithClientId = insertTextMessage(jdbc, chatId, userA, clientMessageId, "reply")
        jdbc.update(
            "UPDATE chat_messages SET reply_to_message_id = ? WHERE id = ?",
            targetMessageId,
            textWithClientId
        )

        insertTextMessage(jdbc, chatId, userA, null, "legacy")
        insertAudioMessage(jdbc, chatId, userA, UUID.randomUUID())

        assertRejected(jdbc) {
            jdbc.update(
                "UPDATE chat_messages SET reply_to_prompt_snapshot_id = ? WHERE id = ?",
                snapshotId,
                textWithClientId
            )
        }

        assertRejected(jdbc) {
            insertTextMessage(jdbc, chatId, userA, clientMessageId, "duplicate")
        }

        assertRejected(jdbc) {
            jdbc.update(
                "UPDATE chat_messages SET reply_to_message_id = ? WHERE id = ?",
                UUID.randomUUID(),
                textWithClientId
            )
        }

        val predicate = indexPredicate(jdbc, "idx_chat_messages_reply_to_message")
        assertTrue(predicate.contains("reply_to_message_id IS NOT NULL", ignoreCase = true))
        assertEquals(listOf("chat_session_id", "sender_id", "client_message_id"), indexColumns(jdbc, "uq_chat_messages_client_message"))
    }

    private fun assertRejected(
        jdbc: JdbcTemplate,
        block: () -> Unit
    ) {
        assertThrows<DataAccessException> {
            block()
            jdbc.execute("SELECT 1")
        }
    }

    private fun insertUser(jdbc: JdbcTemplate): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO users (id, version, email, created_at, updated_at, status)
            VALUES (?, 0, ?, now(), now(), 'ACTIVE')
            """.trimIndent(),
            id,
            "migration-${UUID.randomUUID()}@example.com"
        )
        return id
    }

    private fun insertMatch(
        jdbc: JdbcTemplate,
        userA: UUID,
        userB: UUID
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO matches (id, user_a_id, user_b_id, state, created_at, updated_at)
            VALUES (?, ?, ?, 'CHAT_ACTIVE', now(), now())
            """.trimIndent(),
            id,
            userA,
            userB
        )
        return id
    }

    private fun insertChat(
        jdbc: JdbcTemplate,
        matchId: UUID
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO chats (id, match_id, chat_type, status, started_at, timeout_at)
            VALUES (?, ?, 'FIRST_CHAT', 'ACTIVE', now(), now() + interval '15 minutes')
            """.trimIndent(),
            id,
            matchId
        )
        return id
    }

    private fun insertPromptSnapshot(
        jdbc: JdbcTemplate,
        chatId: UUID
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO conversation_prompt_snapshots (
                id, chat_id, ordinal, source_type, source_question_id, prompt_text, created_at
            )
            VALUES (?, ?, 1, 'GENERIC', ?, 'Pregunta', now())
            """.trimIndent(),
            id,
            chatId,
            "Q-${UUID.randomUUID()}"
        )
        return id
    }

    private fun insertTextMessage(
        jdbc: JdbcTemplate,
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID?,
        content: String
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO chat_messages (
                id, chat_session_id, sender_id, message_type, client_message_id, content, sent_at
            )
            VALUES (?, ?, ?, 'TEXT', ?, ?, now())
            """.trimIndent(),
            id,
            chatId,
            senderId,
            clientMessageId,
            content
        )
        return id
    }

    private fun insertAudioMessage(
        jdbc: JdbcTemplate,
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID
    ) {
        jdbc.update(
            """
            INSERT INTO chat_messages (
                id, chat_session_id, sender_id, message_type, client_message_id, content,
                audio_bucket, audio_object_key, audio_content_type, audio_size_bytes,
                audio_duration_millis, audio_sha256, sent_at
            )
            VALUES (?, ?, ?, 'AUDIO', ?, null, 'bucket', 'key', 'audio/mp4', 3, 1000,
                '039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81', now())
            """.trimIndent(),
            UUID.randomUUID(),
            chatId,
            senderId,
            clientMessageId
        )
    }

    private fun indexPredicate(
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

    private fun indexColumns(
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

    private fun createIsolatedJdbcTemplate(): JdbcTemplate {
        val schema = "chat_replies_${UUID.randomUUID().toString().replace("-", "")}"
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
