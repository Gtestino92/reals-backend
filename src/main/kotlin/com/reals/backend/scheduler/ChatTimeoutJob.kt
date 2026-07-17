package com.reals.backend.scheduler

import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/* Expires active first-chat sessions that have exceeded their absolute timeoutAt deadline.
    Second-chat timeout/read-only cleanup is owned by SecondChatLifecycleJob.
    The client uses timeoutAt to display a countdown - this job marks first chats EXPIRED in the DB.
    Runs every minute - fast response needed so users don't wait on a dead session.
 */
@Component
class ChatTimeoutJob(
    private val chatService: ChatService,
    @param:Value("\${scheduler.chat-timeout-job.batch-size:100}")
    private val batchSize: Int = 100
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

    internal fun processTimedOutChats(): JobRunSummary {
        require(batchSize > 0) { "scheduler.chat-timeout-job.batch-size must be positive" }
        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()
        val batch =
            boundedSchedulerBatch(
                fetchedCandidates = chatService.findTimedOutChatIds(
                    now = now,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )
        var succeeded = 0
        var skipped = 0
        var failed = 0

        batch.items.forEach { chatId ->
            try {
                val changed = chatService.endChat(
                    chatId = chatId,
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
                    chatId,
                    ex
                )
            }
        }

        val summary =
            JobRunSummary(
                processed = batch.items.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            )
        log.logBatchComplete(
            jobName = "ChatTimeoutJob",
            batchSize = batchSize,
            fetched = batch.fetched,
            backlogRemaining = batch.backlogRemaining
        )
        log.logJobSummary(
            jobName = "ChatTimeoutJob",
            summary = summary,
            startedAt = startedAt
        )
        return summary
    }
}
