package com.reals.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class PasswordResetExecutionConfig {
    // Not a default candidate: leave Boot's application executor auto-configuration intact.
    @Bean(defaultCandidate = false)
    fun passwordResetExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        queueCapacity = 32
        setThreadNamePrefix("password-reset-")
        // Never run rejected deliveries on the HTTP thread.
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setStrictEarlyShutdown(true)
        setAcceptTasksAfterContextClose(false)
        // After coordinated stopping, destruction cancels remaining queued work and interrupts active work.
        setWaitForTasksToCompleteOnShutdown(false)
        setAwaitTerminationSeconds(15)
    }
}
