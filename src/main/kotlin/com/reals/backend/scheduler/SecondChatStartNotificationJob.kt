package com.reals.backend.scheduler

import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.ScheduleNegotiation
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.notification.SecondChatStartNotificationProcessingResult
import com.reals.backend.service.notification.SecondChatStartNotificationService
import com.reals.backend.service.notification.secondChatStartedAggregateId
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Component
class SecondChatStartNotificationJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val deliveryRepository: PushNotificationDeliveryRepository,
    private val secondChatStartNotificationService: SecondChatStartNotificationService,

    @param:Value("\${scheduler.second-chat-start-notification-job.fixed-delay:240000}")
    private val fixedDelayMs: Long = 240000,

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
        require(fixedDelayMs < Duration.ofMinutes(latestSendAfterStartMinutes).toMillis()) {
            "scheduler.second-chat-start-notification-job.fixed-delay must be less than notifications.second-chat-start.latest-send-after-start-minutes"
        }

        val startedAt = System.nanoTime()
        val windowStart = now.minusMinutes(latestSendAfterStartMinutes)
        val candidates = collectUnhandledCandidates(
            windowStart = windowStart,
            now = now
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
                when (connectionOutcome(result)) {
                    ConnectionNotificationOutcome.SUCCEEDED -> succeeded += 1
                    ConnectionNotificationOutcome.SKIPPED -> skipped += 1
                    ConnectionNotificationOutcome.FAILED -> failed += 1
                }
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
            fetched = candidates.scanned,
            backlogRemaining = candidates.backlogRemaining
        )
        log.logJobSummary(
            jobName = "SecondChatStartNotificationJob",
            summary = summary,
            startedAt = startedAt
        )
        return summary
    }

    private fun collectUnhandledCandidates(
        windowStart: OffsetDateTime,
        now: OffsetDateTime
    ): SecondChatStartCandidateBatch {
        val selected = mutableListOf<UUID>()
        var scanned = 0
        var backlogRemaining = false
        var sourceExhausted = false
        var cursor: ScheduleNegotiation? = null
        val pageSize = batchSize + 1

        while (selected.size < batchSize && !sourceExhausted) {
            val page =
                if (cursor == null) {
                    negotiationRepository.findConfirmedSecondChatStartNotificationDueCandidates(
                        windowStartInclusive = windowStart,
                        now = now,
                        pageable = PageRequest.of(0, pageSize)
                    )
                } else {
                    val cursorConfirmedDateTime = cursor.confirmedDateTime
                        ?: error("Second chat start cursor must have confirmedDateTime")
                    negotiationRepository.findConfirmedSecondChatStartNotificationDueCandidatesAfter(
                        windowStartInclusive = windowStart,
                        now = now,
                        cursorConfirmedDateTime = cursorConfirmedDateTime,
                        cursorId = cursor.id,
                        pageable = PageRequest.of(0, pageSize)
                    )
                }

            scanned += page.size
            sourceExhausted = page.size < pageSize
            cursor = page.lastOrNull()

            for (negotiation in page) {
                if (secondChatStartAlreadyFullyHandled(negotiation.connectionId)) {
                    continue
                }

                if (selected.size < batchSize) {
                    selected += negotiation.connectionId
                } else {
                    backlogRemaining = true
                    break
                }
            }
        }

        if (!sourceExhausted && selected.size >= batchSize) {
            backlogRemaining = true
        }

        return SecondChatStartCandidateBatch(
            items = selected,
            scanned = scanned,
            backlogRemaining = backlogRemaining
        )
    }

    private fun connectionOutcome(
        result: SecondChatStartNotificationProcessingResult
    ): ConnectionNotificationOutcome =
        when {
            result.failed > 0 -> ConnectionNotificationOutcome.FAILED
            result.succeeded > 0 -> ConnectionNotificationOutcome.SUCCEEDED
            else -> ConnectionNotificationOutcome.SKIPPED
        }

    private fun secondChatStartAlreadyFullyHandled(connectionId: UUID): Boolean =
        deliveryRepository.findByNotificationTypeAndAggregateId(
            notificationType = PushNotificationType.SECOND_CHAT_STARTED,
            aggregateId = secondChatStartedAggregateId(connectionId)
        ).size >= SECOND_CHAT_START_RECIPIENT_COUNT

    private companion object {
        const val SECOND_CHAT_START_RECIPIENT_COUNT = 2
    }

    private data class SecondChatStartCandidateBatch(
        val items: List<UUID>,
        val scanned: Int,
        val backlogRemaining: Boolean
    )

    private enum class ConnectionNotificationOutcome {
        SUCCEEDED,
        SKIPPED,
        FAILED
    }
}
