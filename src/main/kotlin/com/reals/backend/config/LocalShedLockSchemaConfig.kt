package com.reals.backend.config

import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
@Profile("local", "local-nodb", "dev")
class LocalShedLockSchemaConfig {

    @Bean
    fun localShedLockSchemaInitializer(
        jdbcTemplate: JdbcTemplate
    ): ApplicationRunner =
        ApplicationRunner {
            jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS shedlock (
                    name        VARCHAR(64)  NOT NULL,
                    lock_until  TIMESTAMP    NOT NULL,
                    locked_at   TIMESTAMP    NOT NULL,
                    locked_by   VARCHAR(255) NOT NULL,
                    PRIMARY KEY (name)
                )
                """.trimIndent()
            )
        }
}
