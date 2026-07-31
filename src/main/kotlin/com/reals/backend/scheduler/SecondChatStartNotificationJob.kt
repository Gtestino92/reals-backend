package com.reals.backend.scheduler

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.notification.SecondChatStartNotificationService
import com.reals.backend.service.notification.secondChatStartedAggregateId
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
class SecondChatStartNotificationJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val deliveryRepository: PushNotificationDeliveryRepository,
    private val secondChatStartNotificationService: SecondChatStartNotificationService,

    @param:Value("\${scheduler.second-chat-start-notification-job.fixed-delay:300000}")
    private val fixedDelayMs: Long = 300000,

    @param:Value("\${scheduler.second-chat-start-notification-job.batch-size:100}")
    private val batchSize: Int = 100,

    @param:Value("\${notifications.second-chat-start.latest-send-after-start-minutes:5}")
    private val latestSendAfterStartMinutes: Long = 5
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.second-chat-start-notification-job.fixed-delay}")
    @SchedulerLock(
        name = "SecondChatStartNotificationJob",
        lockAtLeastFor = "PT5S",
        lockAtMostFor = "PT3M"
    )
    fun run() {
        processSecondChatStartNotifications()
    }

    fun runNowForDev() {
        processSecondChatStartNotifications()
    }

    internal fun processSecondChatStartNotifications(
        now: OffsetDateTime = OffsetDateTime.now()
    ): JobRunSummary {
        require(fixedDelayMs > 0) {
            "scheduler.second-chat-start-notification-job.fixed-delay must be positive"
        }
        require(batchSize > 0) {
            "scheduler.second-chat-start-notification-job.batch-size must be positive"
        }
        require(latestSendAfterStartMinutes > 0) {
            "notifications.second-chat-start.latest-send-after-start-minutes must be positive"
        }

        val startedAt = System.nanoTime()
        val windowStart = now.minusMinutes(latestSendAfterStartMinutes)
        val fetchedCandidates =
            negotiationRepository.findConfirmedSecondChatStartNotificationDueConnectionIds(
                windowStartInclusive = windowStart,
                now = now,
                pageable = PageRequest.of(0, batchSize + 1)
            )
        val candidates =
            boundedSchedulerBatch(
                fetchedCandidates = fetchedCandidates.filterNot { connectionId ->
                    secondChatStartAlreadyFullyHandled(connectionId)
                },
                batchSize = batchSize
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        candidates.items.forEach { connectionId ->
            try {
                val result =
                    secondChatStartNotificationService.processSecondChatStart(
                        connectionId = connectionId,
                        now = now,
                        latestSendAfterStartMinutes = latestSendAfterStartMinutes
                    )
                succeeded += result.succeeded
                skipped += result.skipped
                failed += result.failed
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatStartNotificationJob - failed to process start notification connection={}",
                    connectionId,
                    ex
                )
            }
        }

        val summary =
            JobRunSummary(
                processed = candidates.items.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "SecondChatStartNotificationJob",
            batchSize = batchSize,
            fetched = fetchedCandidates.size,
            backlogRemaining = candidates.backlogRemaining ||
                fetchedCandidates.any { it !in candidates.items && !secondChatStartAlreadyFullyHandled(it) }
        )
        log.logJobSummary(
            jobName = "SecondChatStartNotificationJob",
            summary = summary,
            startedAt = startedAt
        )
        return summary
    }

    private fun secondChatStartAlreadyFullyHandled(connectionId: UUID): Boolean =
        deliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SECOND_CHAT_STARTED,
            aggregateId = secondChatStartedAggregateId(connectionId)
        ).size >= SECOND_CHAT_START_RECIPIENT_COUNT

    private companion object {
        const val SECOND_CHAT_START_RECIPIENT_COUNT = 2
    }
}
