package com.reals.backend.scheduler

import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Owns second-chat lifecycle transitions after scheduling confirmation:
 * expired scheduled windows without a chat are closed, AVAILABLE chats that
 * nobody entered are closed, ACTIVE chats move to EXPIRED read-only, then
 * EXPIRED chats move to CLOSED after read-only retention.
 */
@Component
class SecondChatLifecycleJob(
    private val chatService: ChatService,
    private val negotiationRepository: ScheduleNegotiationRepository,

    @param:Value("\${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long = 120,

    @param:Value("\${scheduler.second-chat-lifecycle-job.batch-size:100}")
    private val batchSize: Int = 100
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.second-chat-lifecycle-job.fixed-delay}")
    @SchedulerLock(
        name = "SecondChatLifecycleJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT5M"
    )
    fun run() {
        processSecondChatLifecycle()
    }

    fun runNowForDev() {
        processSecondChatLifecycle()
    }

    internal fun processSecondChatLifecycle(): JobRunSummary {
        require(batchSize > 0) { "scheduler.second-chat-lifecycle-job.batch-size must be positive" }
        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()

        val expiredScheduledWithoutChat =
            boundedSchedulerBatch(
                fetchedCandidates =
                    negotiationRepository.findExpiredConfirmedScheduledNegotiationConnectionIdsWithoutSecondChat(
                        expiresBefore = now.minusMinutes(secondChatDurationMinutes),
                        pageable = PageRequest.of(0, batchSize + 1)
                    ),
                batchSize = batchSize
            )
        val timedOutAvailable =
            boundedSchedulerBatch(
                fetchedCandidates = chatService.findTimedOutAvailableSecondChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val timedOutActive =
            boundedSchedulerBatch(
                fetchedCandidates = chatService.findTimedOutActiveSecondChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val expiredReadOnly =
            boundedSchedulerBatch(
                fetchedCandidates = chatService.findExpiredReadOnlySecondChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val noShowConnections =
            boundedSchedulerBatch(
                fetchedCandidates = chatService.findSecondChatNoShowConnectionIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        noShowConnections.items.forEach { connectionId ->
            try {
                val recorded = chatService.evaluateSecondChatNoShow(
                    connectionId = connectionId,
                    now = now
                )
                if (recorded > 0) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to record second-chat no-show connection={}",
                    connectionId,
                    ex
                )
            }
        }

        expiredScheduledWithoutChat.items.forEach { connectionId ->
            try {
                val negotiation = negotiationRepository.findByConnectionId(connectionId)
                val confirmedDateTime = negotiation?.confirmedDateTime
                if (
                    confirmedDateTime != null &&
                    chatService.closeExpiredScheduledSecondChatWindow(
                        connectionId = connectionId,
                        confirmedDateTime = confirmedDateTime
                    )
                ) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to close expired scheduled second-chat window connection={}",
                    connectionId,
                    ex
                )
            }
        }

        timedOutAvailable.items.forEach { chatId ->
            try {
                if (chatService.closeExpiredUnactivatedSecondChat(chatId)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to close expired unactivated second chat={}",
                    chatId,
                    ex
                )
            }
        }

        timedOutActive.items.forEach { chatId ->
            try {
                if (chatService.expireSecondChatToReadOnly(chatId)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to expire second chat to read-only chat={}",
                    chatId,
                    ex
                )
            }
        }

        expiredReadOnly.items.forEach { chatId ->
            try {
                if (chatService.closeExpiredReadOnlySecondChat(chatId)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to close read-only second chat={}",
                    chatId,
                    ex
                )
            }
        }

        val processed =
            noShowConnections.items.size +
                expiredScheduledWithoutChat.items.size +
                timedOutAvailable.items.size +
                timedOutActive.items.size +
                expiredReadOnly.items.size
        val fetched =
            noShowConnections.fetched +
                expiredScheduledWithoutChat.fetched +
                timedOutAvailable.fetched +
                timedOutActive.fetched +
                expiredReadOnly.fetched
        val backlogRemaining =
            noShowConnections.backlogRemaining ||
                expiredScheduledWithoutChat.backlogRemaining ||
                timedOutAvailable.backlogRemaining ||
                timedOutActive.backlogRemaining ||
                expiredReadOnly.backlogRemaining
        val summary =
            JobRunSummary(
                processed = processed,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "SecondChatLifecycleJob",
            batchSize = batchSize,
            fetched = fetched,
            backlogRemaining = backlogRemaining
        )
        log.logJobSummary(
            jobName = "SecondChatLifecycleJob",
            summary = summary,
            startedAt = startedAt
        )
        return summary
    }
}
