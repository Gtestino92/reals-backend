package com.reals.backend.integration.postgres

import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true",
        "scheduler.enabled=false"
    ]
)
@ActiveProfiles("test")
@Testcontainers
abstract class PostgresITBase : BaseIT() {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanPostgresDatabase() {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                active_engagement_locks,
                audit_events,
                chat_decisions,
                chat_exit_requests,
                chat_messages,
                chats,
                connection_home_dismissals,
                connections,
                first_chat_guidance,
                matchmaking_queue,
                matches,
                media_cleanup_tasks,
                matchmaking_availability_notification_episodes,
                penalties,
                profile_looking_for_genders,
                profile_photos,
                profiles,
                push_device_tokens,
                push_notification_deliveries,
                safety_report_evidence_snapshots,
                safety_reports,
                schedule_proposals,
                schedule_negotiations,
                user_blocks,
                user_home_status,
                user_legal_document_actions,
                user_reliability_events,
                users
            RESTART IDENTITY CASCADE
            """.trimIndent()
        )
    }

    companion object {
        private class KPostgreSQLContainer(imageName: String) :
            PostgreSQLContainer<KPostgreSQLContainer>(imageName)

        @Container
        @JvmStatic
        private val postgres = KPostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
