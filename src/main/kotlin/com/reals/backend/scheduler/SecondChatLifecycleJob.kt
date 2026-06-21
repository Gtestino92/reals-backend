package com.reals.backend.scheduler

import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val secondChatDurationMinutes: Long = 120
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.second-chat-lifecycle-job.fixed-delay}")
    @SchedulerLock(
        name = "SecondChatLifecycleJob",
        lockAtLeastFor = "PT30S",
        lockAtMostFor = "PT2M"
    )
    fun run() {
        processSecondChatLifecycle()
    }

    fun runNowForDev() {
        processSecondChatLifecycle()
    }

    private fun processSecondChatLifecycle() {
        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()

        val expiredScheduledWithoutChat =
            negotiationRepository.findExpiredConfirmedScheduledNegotiationsWithoutSecondChat(
                expiresBefore = now.minusMinutes(secondChatDurationMinutes)
            )
        val timedOutAvailable = chatService.findTimedOutAvailableSecondChats()
        val timedOutActive = chatService.findTimedOutActiveSecondChats()
        val expiredReadOnly = chatService.findExpiredReadOnlySecondChats()

        var succeeded = 0
        var skipped = 0
        var failed = 0

        expiredScheduledWithoutChat.forEach { negotiation ->
            try {
                val confirmedDateTime = negotiation.confirmedDateTime
                if (
                    confirmedDateTime != null &&
                    chatService.closeExpiredScheduledSecondChatWindow(
                        connectionId = negotiation.connectionId,
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
                    negotiation.connectionId,
                    ex
                )
            }
        }

        timedOutAvailable.forEach { chat ->
            try {
                if (chatService.closeExpiredUnactivatedSecondChat(chat.id)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to close expired unactivated second chat={}",
                    chat.id,
                    ex
                )
            }
        }

        timedOutActive.forEach { chat ->
            try {
                if (chatService.expireSecondChatToReadOnly(chat.id)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to expire second chat to read-only chat={}",
                    chat.id,
                    ex
                )
            }
        }

        expiredReadOnly.forEach { chat ->
            try {
                if (chatService.closeExpiredReadOnlySecondChat(chat.id)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "SecondChatLifecycleJob - failed to close read-only second chat={}",
                    chat.id,
                    ex
                )
            }
        }

        log.logJobSummary(
            jobName = "SecondChatLifecycleJob",
            summary = JobRunSummary(
                processed =
                    expiredScheduledWithoutChat.size +
                        timedOutAvailable.size +
                        timedOutActive.size +
                        expiredReadOnly.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
