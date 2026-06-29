package com.reals.backend.scheduler

import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.SecondChatReminderNotificationService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

@Component
class SecondChatReminderNotificationJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val reminderNotificationService: SecondChatReminderNotificationService,

    @param:Value("\${scheduler.second-chat-reminder-job.fixed-delay:60000}")
    private val fixedDelayMs: Long = 60000,

    @param:Value("#{'\${notifications.second-chat-reminder.minutes-before:10}'.split(',')}")
    private val reminderLeadMinutes: List<String> = listOf("10")
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.second-chat-reminder-job.fixed-delay}")
    @SchedulerLock(
        name = "SecondChatReminderNotificationJob",
        lockAtLeastFor = "PT5S",
        lockAtMostFor = "PT3M"
    )
    fun run() {
        processSecondChatReminders()
    }

    fun runNowForDev() {
        processSecondChatReminders()
    }

    private fun processSecondChatReminders() {
        require(fixedDelayMs > 0) {
            "scheduler.second-chat-reminder-job.fixed-delay must be positive"
        }

        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()
        val dueWindowEnd = now.plus(Duration.ofMillis(fixedDelayMs))
        val dueReminderCandidates =
            configuredReminderLeadMinutes().flatMap { minutesBefore ->
                negotiationRepository.findConfirmedSecondChatReminderDueForWindow(
                    windowStart = now.plusMinutes(minutesBefore),
                    windowEnd = dueWindowEnd.plusMinutes(minutesBefore)
                ).map { negotiation ->
                    DueSecondChatReminder(
                        negotiation = negotiation,
                        minutesBefore = minutesBefore
                    )
                }
            }

        var succeeded = 0
        var skipped = 0
        var failed = 0

        dueReminderCandidates.forEach { candidate ->
            try {
                val negotiation = candidate.negotiation
                val confirmedDateTime = negotiation.confirmedDateTime

                if (
                    confirmedDateTime != null &&
                    reminderNotificationService.notifySecondChatReminder(
                        connectionId = negotiation.connectionId,
                        confirmedDateTime = confirmedDateTime,
                        minutesBefore = candidate.minutesBefore
                    )
                ) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatReminderNotificationJob - failed to process reminder connection={} minutesBefore={}",
                    candidate.negotiation.connectionId,
                    candidate.minutesBefore,
                    ex
                )
            }
        }

        log.logJobSummary(
            jobName = "SecondChatReminderNotificationJob",
            summary = JobRunSummary(
                processed = dueReminderCandidates.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }

    private fun configuredReminderLeadMinutes(): List<Long> {
        val configuredValues =
            reminderLeadMinutes.map { rawValue ->
                val trimmedValue = rawValue.trim()
                require(trimmedValue.isNotBlank()) {
                    "notifications.second-chat-reminder.minutes-before must contain comma-separated positive whole minutes"
                }
                val minutesBefore = trimmedValue.toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "notifications.second-chat-reminder.minutes-before must contain comma-separated positive whole minutes"
                    )
                require(minutesBefore > 0) {
                    "notifications.second-chat-reminder.minutes-before values must be positive"
                }
                minutesBefore
            }

        require(configuredValues.isNotEmpty()) {
            "notifications.second-chat-reminder.minutes-before must contain at least one value"
        }

        return configuredValues
    }

    private data class DueSecondChatReminder(
        val negotiation: ScheduleNegotiation,
        val minutesBefore: Long
    )
}
