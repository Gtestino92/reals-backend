package com.reals.backend.config

import javax.sql.DataSource
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("!test")
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
class ShedLockConfig {

    @Bean
    @ConditionalOnBean(DataSource::class)
    fun lockProvider(
        dataSource: DataSource
    ): LockProvider =
        JdbcTemplateLockProvider(
            dataSource,
            "shedlock"
        )
}
