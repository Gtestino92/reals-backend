package com.reals.backend.scheduler

import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.notification.SecondChatReminderNotificationService
import com.reals.backend.service.notification.secondChatReminderAggregateId
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

@Component
class SecondChatReminderNotificationJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val deliveryRepository: PushNotificationDeliveryRepository,
    private val reminderNotificationService: SecondChatReminderNotificationService,

    @param:Value("\${scheduler.second-chat-reminder-job.fixed-delay:60000}")
    private val fixedDelayMs: Long = 60000,

    @param:Value("#{'\${notifications.second-chat-reminder.minutes-before:10}'.split(',')}")
    private val reminderLeadMinutes: List<String> = listOf("10"),

    @param:Value("\${scheduler.second-chat-reminder-job.batch-size:100}")
    private val batchSize: Int = 100
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

    internal fun processSecondChatReminders(now: OffsetDateTime = OffsetDateTime.now()): JobRunSummary {
        require(fixedDelayMs > 0) {
            "scheduler.second-chat-reminder-job.fixed-delay must be positive"
        }
        require(batchSize > 0) {
            "scheduler.second-chat-reminder-job.batch-size must be positive"
        }

        val startedAt = System.nanoTime()
        val dueWindowEnd = now.plus(Duration.ofMillis(fixedDelayMs))
        val leadMinutes = configuredReminderLeadMinutes()
        require(batchSize >= leadMinutes.size) {
            "scheduler.second-chat-reminder-job.batch-size must be at least the number of distinct configured reminder lead times"
        }
        val fetchLimits = globalFetchLimits(
            leadTimeCount = leadMinutes.size,
            fetchBudget = batchSize + 1
        )
        val reminderWindows = reminderWindows(
            leadMinutes = leadMinutes,
            now = now,
            dueWindowEnd = dueWindowEnd
        )

        val fetchedCandidatesByLead =
            reminderWindows.mapIndexed { index, reminderWindow ->
                DueSecondChatReminderCandidates(
                    minutesBefore = reminderWindow.minutesBefore,
                    negotiations =
                        negotiationRepository.findConfirmedSecondChatReminderRecoverableForWindow(
                            windowStartExclusive = reminderWindow.windowStartExclusive,
                            windowEndInclusive = reminderWindow.windowEndInclusive,
                            pageable = PageRequest.of(0, fetchLimits[index])
                        )
                )
            }
        val candidatesByLead =
            fetchedCandidatesByLead.map { candidates ->
                candidates.copy(
                    negotiations =
                        candidates.negotiations.filterNot { negotiation ->
                            reminderAlreadyFullyHandled(
                                connectionId = negotiation.connectionId,
                                minutesBefore = candidates.minutesBefore
                            )
                        }
                )
            }

        val dueReminderCandidates =
            selectReminderBatch(
                candidatesByLead = candidatesByLead,
                batchSize = batchSize
            )
        val fetched = fetchedCandidatesByLead.sumOf { it.negotiations.size }
        val selectedByLead =
            dueReminderCandidates
                .groupingBy { it.minutesBefore }
                .eachCount()
        val backlogRemaining =
            candidatesByLead.any { candidates ->
                candidates.negotiations.size > (selectedByLead[candidates.minutesBefore] ?: 0)
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

        val summary =
            JobRunSummary(
                processed = dueReminderCandidates.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "SecondChatReminderNotificationJob",
            batchSize = batchSize,
            fetched = fetched,
            backlogRemaining = backlogRemaining
        )
        log.logJobSummary(
            jobName = "SecondChatReminderNotificationJob",
            summary = summary,
            startedAt = startedAt
        )
        return summary
    }

    private fun selectReminderBatch(
        candidatesByLead: List<DueSecondChatReminderCandidates>,
        batchSize: Int
    ): List<DueSecondChatReminder> {
        val selected = mutableListOf<DueSecondChatReminder>()
        var candidateIndex = 0

        while (
            selected.size < batchSize &&
            candidatesByLead.any { candidateIndex < it.negotiations.size }
        ) {
            candidatesByLead.forEach { candidates ->
                if (selected.size >= batchSize) {
                    return@forEach
                }

                val negotiation = candidates.negotiations.getOrNull(candidateIndex)
                    ?: return@forEach
                selected += DueSecondChatReminder(
                    negotiation = negotiation,
                    minutesBefore = candidates.minutesBefore
                )
            }
            candidateIndex += 1
        }

        return selected
    }

    private fun reminderAlreadyFullyHandled(
        connectionId: java.util.UUID,
        minutesBefore: Long
    ): Boolean =
        deliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SECOND_CHAT_REMINDER,
            aggregateId = secondChatReminderAggregateId(
                connectionId = connectionId,
                minutesBefore = minutesBefore
            )
        ).size >= SECOND_CHAT_REMINDER_RECIPIENT_COUNT

    private fun globalFetchLimits(
        leadTimeCount: Int,
        fetchBudget: Int
    ): List<Int> {
        val fetchLimits = MutableList(leadTimeCount) { 0 }
        repeat(fetchBudget) { index ->
            fetchLimits[index % leadTimeCount] += 1
        }
        return fetchLimits
    }

    private fun reminderWindows(
        leadMinutes: List<Long>,
        now: OffsetDateTime,
        dueWindowEnd: OffsetDateTime
    ): List<SecondChatReminderWindow> =
        leadMinutes.mapIndexed { index, minutesBefore ->
            val nextCloserLeadMinutes = leadMinutes.getOrNull(index + 1)
            SecondChatReminderWindow(
                minutesBefore = minutesBefore,
                windowStartExclusive =
                    if (nextCloserLeadMinutes == null) {
                        now
                    } else {
                        dueWindowEnd.plusMinutes(nextCloserLeadMinutes)
                    },
                windowEndInclusive = dueWindowEnd.plusMinutes(minutesBefore)
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
            }.distinct()
                .sortedDescending()

        require(configuredValues.isNotEmpty()) {
            "notifications.second-chat-reminder.minutes-before must contain at least one value"
        }

        return configuredValues
    }

    private data class DueSecondChatReminderCandidates(
        val minutesBefore: Long,
        val negotiations: List<ScheduleNegotiation>
    )

    private data class DueSecondChatReminder(
        val negotiation: ScheduleNegotiation,
        val minutesBefore: Long
    )

    private data class SecondChatReminderWindow(
        val minutesBefore: Long,
        val windowStartExclusive: OffsetDateTime,
        val windowEndInclusive: OffsetDateTime
    )

    private companion object {
        const val SECOND_CHAT_REMINDER_RECIPIENT_COUNT = 2
    }
}
