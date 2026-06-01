package com.reals.backend.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
@Profile("local", "local-nodb", "local-postgres", "dev")
class LocalShedLockSchemaConfig {

    @Bean
    fun localShedLockSchemaInitializer(
        jdbcTemplate: JdbcTemplate
    ): InitializingBean =
        InitializingBean {
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
