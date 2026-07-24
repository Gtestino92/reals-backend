package com.reals.backend.scheduler

import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.SecondChatLifecycleService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Owns second-chat lifecycle transitions after scheduling confirmation:
 * expired no-show claims, hard-cutoff no-show resolution, expired scheduled
 * windows without a chat, AVAILABLE chat timeout, ACTIVE absolute timeout, then
 * read-only retention cleanup.
 */
@Component
class SecondChatLifecycleJob(
    private val chatService: ChatService,
    private val secondChatLifecycleService: SecondChatLifecycleService,
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

        val expiredNoShowClaims =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatLifecycleService.findExpiredPendingNoShowClaimIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val hardCutoffNoShows =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatLifecycleService.findHardCutoffNoShowConnectionIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
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

        var succeeded = 0
        var skipped = 0
        var failed = 0

        expiredNoShowClaims.items.forEach { requestId ->
            try {
                if (secondChatLifecycleService.processExpiredNoShowClaim(requestId, now)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to process expired no-show claim request={}",
                    requestId,
                    ex
                )
            }
        }

        hardCutoffNoShows.items.forEach { connectionId ->
            try {
                if (secondChatLifecycleService.resolveHardCutoffNoShow(
                    connectionId = connectionId,
                    now = now
                )) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to resolve hard-cutoff no-show connection={}",
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
            expiredNoShowClaims.items.size +
                hardCutoffNoShows.items.size +
                expiredScheduledWithoutChat.items.size +
                timedOutAvailable.items.size +
                timedOutActive.items.size +
                expiredReadOnly.items.size
        val fetched =
            expiredNoShowClaims.fetched +
                hardCutoffNoShows.fetched +
                expiredScheduledWithoutChat.fetched +
                timedOutAvailable.fetched +
                timedOutActive.fetched +
                expiredReadOnly.fetched
        val backlogRemaining =
            expiredNoShowClaims.backlogRemaining ||
                hardCutoffNoShows.backlogRemaining ||
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
