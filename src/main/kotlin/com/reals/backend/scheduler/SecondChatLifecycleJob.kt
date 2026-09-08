package com.reals.backend.scheduler

import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatLifecycleService
import com.reals.backend.service.SecondChatConversationLifecycleService
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
    private val chatLifecycleService: ChatLifecycleService,
    private val secondChatLifecycleService: SecondChatLifecycleService,
    private val secondChatConversationLifecycleService: SecondChatConversationLifecycleService,
    private val negotiationRepository: ScheduleNegotiationRepository,

    @param:Value("\${chat.second-chat.duration-minutes:120}")
    private val secondChatDurationMinutes: Long = 120,

    @param:Value("\${scheduler.second-chat-lifecycle-job.batch-size:100}")
    private val batchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
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
        val expiredMutualCompletionRequests =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatConversationLifecycleService.findExpiredMutualCompletionRequestIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val expiredPartnerInactivityClaims =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatConversationLifecycleService.findExpiredPartnerInactivityClaimIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val initialSilenceDue =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatConversationLifecycleService.findInitialSilenceDueChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val automaticInactivityDue =
            boundedSchedulerBatch(
                fetchedCandidates = secondChatConversationLifecycleService.findAutomaticInactivityDueChatIds(
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
                fetchedCandidates = chatLifecycleService.findTimedOutAvailableSecondChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val timedOutActive =
            boundedSchedulerBatch(
                fetchedCandidates = chatLifecycleService.findTimedOutActiveSecondChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        val expiredReadOnly =
            boundedSchedulerBatch(
                fetchedCandidates = chatLifecycleService.findExpiredReadOnlySecondChatIds(
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

        expiredMutualCompletionRequests.items.forEach { requestId ->
            try {
                if (secondChatConversationLifecycleService.processExpiredMutualCompletionRequest(requestId, now)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to process expired mutual completion request={}",
                    requestId,
                    ex
                )
            }
        }

        expiredPartnerInactivityClaims.items.forEach { requestId ->
            try {
                if (secondChatConversationLifecycleService.processExpiredPartnerInactivityClaim(requestId, now)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to process expired partner inactivity claim={}",
                    requestId,
                    ex
                )
            }
        }

        initialSilenceDue.items.forEach { chatId ->
            try {
                if (secondChatConversationLifecycleService.processInitialSilence(chatId, now)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to process initial silence second chat={}",
                    chatId,
                    ex
                )
            }
        }

        automaticInactivityDue.items.forEach { chatId ->
            try {
                if (secondChatConversationLifecycleService.processAutomaticInactivity(chatId, now)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to process automatic second-chat inactivity chat={}",
                    chatId,
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
                    chatLifecycleService.closeExpiredScheduledSecondChatWindow(
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
                if (chatLifecycleService.closeExpiredUnactivatedSecondChat(chatId)) {
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
                if (chatLifecycleService.expireSecondChatToReadOnly(chatId)) {
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
                if (chatLifecycleService.closeExpiredReadOnlySecondChat(chatId)) {
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
                expiredMutualCompletionRequests.items.size +
                expiredPartnerInactivityClaims.items.size +
                initialSilenceDue.items.size +
                automaticInactivityDue.items.size +
                expiredScheduledWithoutChat.items.size +
                timedOutAvailable.items.size +
                timedOutActive.items.size +
                expiredReadOnly.items.size
        val fetched =
            expiredNoShowClaims.fetched +
                hardCutoffNoShows.fetched +
                expiredMutualCompletionRequests.fetched +
                expiredPartnerInactivityClaims.fetched +
                initialSilenceDue.fetched +
                automaticInactivityDue.fetched +
                expiredScheduledWithoutChat.fetched +
                timedOutAvailable.fetched +
                timedOutActive.fetched +
                expiredReadOnly.fetched
        val backlogRemaining =
            expiredNoShowClaims.backlogRemaining ||
                hardCutoffNoShows.backlogRemaining ||
                expiredMutualCompletionRequests.backlogRemaining ||
                expiredPartnerInactivityClaims.backlogRemaining ||
                initialSilenceDue.backlogRemaining ||
                automaticInactivityDue.backlogRemaining ||
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
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = backlogRemaining
        )
        return summary
    }
}
