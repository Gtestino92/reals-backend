package com.reals.backend.config

import com.reals.backend.scheduler.*
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevSchedulerCadenceTest {
    @Test
    fun `dev defaults resolve to supported cadences and retain environment overrides`() {
        val environment = MockEnvironment()
        YamlPropertySourceLoader().load("dev", ClassPathResource("application-dev.yml"))
            .forEach { environment.propertySources.addLast(it) }

        val jobs = listOf(
            Triple(MatchmakingJob::class.java, "MATCHMAKING", 15_000L),
            Triple(SchedulingActivationJob::class.java, "SCHEDULING_ACTIVATION", 15_000L),
            Triple(ChatTimeoutJob::class.java, "CHAT_TIMEOUT", 30_000L),
            Triple(InactivityCheckJob::class.java, "INACTIVITY_CHECK", 30_000L),
            Triple(SecondChatLifecycleJob::class.java, "SECOND_CHAT_LIFECYCLE", 30_000L),
            Triple(SecondChatStartNotificationJob::class.java, "SECOND_CHAT_START_NOTIFICATION", 60_000L),
            Triple(VisualPhaseExpirationJob::class.java, "VISUAL_PHASE_EXPIRATION", 60_000L),
            Triple(SchedulingNegotiationTimeoutJob::class.java, "SCHEDULING_TIMEOUT", 60_000L),
            Triple(PenaltyExpirationJob::class.java, "PENALTY_EXPIRATION", 60_000L)
        )
        for ((type, variableStem, expectedMs) in jobs) {
            val run = type.getMethod("run")
            val delay = run.getAnnotation(Scheduled::class.java).fixedDelayString
            val lock = run.getAnnotation(SchedulerLock::class.java)
            val lockMinimum = Duration.parse(environment.resolveRequiredPlaceholders(lock.lockAtLeastFor)).toMillis()
            assertEquals(expectedMs, environment.resolveRequiredPlaceholders(delay).toLong(), type.simpleName)
            assertTrue(lockMinimum <= expectedMs, type.simpleName)
            environment.setProperty("SCHEDULER_${variableStem}_JOB_FIXED_DELAY", "120000")
            assertEquals(120_000L, environment.resolveRequiredPlaceholders(delay).toLong(), type.simpleName)
        }

        val notificationWindowMinutes =
            environment.getRequiredProperty("notifications.second-chat-start.latest-send-after-start-minutes").toLong()
        assertTrue(Duration.ofMinutes(notificationWindowMinutes) > Duration.ofSeconds(60))

        val activationLock = SchedulingActivationJob::class.java.getMethod("run")
            .getAnnotation(SchedulerLock::class.java).lockAtLeastFor
        assertEquals("PT30S", MockEnvironment().resolveRequiredPlaceholders(activationLock))
    }
}
