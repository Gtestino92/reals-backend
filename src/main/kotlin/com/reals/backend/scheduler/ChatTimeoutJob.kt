package com.reals.backend.scheduler

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/* Expires active first-chat sessions that have exceeded their absolute timeoutAt deadline.
    Second-chat timeout/read-only cleanup is owned by SecondChatLifecycleJob.
    The client uses timeoutAt to display a countdown - this job marks first chats EXPIRED in the DB.
    Runs every minute - fast response needed so users don't wait on a dead session.
 */
@Component
class ChatTimeoutJob(
    private val chatService: ChatService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.chat-timeout-job.fixed-delay}")
    @SchedulerLock(name = "ChatTimeoutJob", lockAtLeastFor = "PT30s", lockAtMostFor = "PT5M")
    fun run() {
        processTimedOutChats()
    }

    fun runNowForDev() {
        processTimedOutChats()
    }

    private fun processTimedOutChats() {
        val startedAt = System.nanoTime()
        val expiredChats = chatService.findTimedOutChats()
        var succeeded = 0
        var skipped = 0
        var failed = 0

        expiredChats.forEach { chat ->
            try {
                val changed = chatService.endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.EXPIRED,
                    endedReason = ChatEndReason.ABSOLUTE_TIMEOUT
                )
                if (changed) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "ChatTimeoutJob - failed to expire chat={}",
                    chat.id,
                    ex
                )
            }
        }

        log.logJobSummary(
            jobName = "ChatTimeoutJob",
            summary = JobRunSummary(
                processed = expiredChats.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
