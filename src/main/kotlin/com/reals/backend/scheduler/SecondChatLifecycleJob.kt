package com.reals.backend.scheduler

import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Owns second-chat lifecycle transitions after the chat has been activated:
 * ACTIVE -> EXPIRED read-only, then EXPIRED -> CLOSED after read-only retention.
 */
@Component
class SecondChatLifecycleJob(
    private val chatService: ChatService
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

        val timedOutActive = chatService.findTimedOutActiveSecondChats()
        val expiredReadOnly = chatService.findExpiredReadOnlySecondChats()

        var succeeded = 0
        var skipped = 0
        var failed = 0

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
                processed = timedOutActive.size + expiredReadOnly.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
