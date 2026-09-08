package com.reals.backend.scheduler

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.ChatAccessService
import com.reals.backend.service.ChatLifecycleService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class InactivityCheckJob(
    private val chatAccessService: ChatAccessService,
    private val chatLifecycleService: ChatLifecycleService,
    @param:Value("\${chat.first-chat.inactivity-threshold-minutes:5}")
    private val inactivityThresholdMinutes: Long,
    @param:Value("\${scheduler.inactivity-check-job.batch-size:100}")
    private val batchSize: Int = 100,
    private val schedulerMetrics: SchedulerMetrics = SchedulerMetrics.noop()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString =
            "\${scheduler.inactivity-check-job.fixed-delay}"
    )
    @SchedulerLock(name = "InactivityCheckJob", lockAtLeastFor = "PT15s", lockAtMostFor = "PT5M")
    fun run() {
        processInactiveChats()
    }

    internal fun processInactiveChats(): JobRunSummary {
        require(batchSize > 0) { "scheduler.inactivity-check-job.batch-size must be positive" }
        val startedAt = System.nanoTime()

        val threshold = OffsetDateTime.now().minusMinutes(inactivityThresholdMinutes)
        val batch =
            boundedSchedulerBatch(
                fetchedCandidates = chatLifecycleService.findInactiveChatIds(
                    threshold = threshold,
                    limit = batchSize + 1
                ),
                batchSize = batchSize
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

        batch.items.forEach { chatId ->
            try {
                val chat: Chat = chatAccessService.findByIdOrThrow(chatId)

                val changed = chatLifecycleService.endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.ABANDONED,
                    endedReason = ChatEndReason.INACTIVITY_TIMEOUT
                )
                if (changed) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "InactivityCheckJob - failed to abandon chat={}",
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
            jobName = "InactivityCheckJob",
            batchSize = batchSize,
            fetched = batch.fetched,
            backlogRemaining = batch.backlogRemaining
        )
        log.logJobSummary(
            jobName = "InactivityCheckJob",
            summary = summary,
            startedAt = startedAt,
            schedulerMetrics = schedulerMetrics,
            backlogRemaining = batch.backlogRemaining
        )
        return summary
    }
}
