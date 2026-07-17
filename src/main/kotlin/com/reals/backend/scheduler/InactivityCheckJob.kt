package com.reals.backend.scheduler

import com.reals.backend.domain.Chat
import com.reals.backend.domain.ChatEndReason
import com.reals.backend.domain.ChatStatus
import com.reals.backend.domain.ChatType
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.*

@Component
class InactivityCheckJob(
    private val chatService: ChatService,
    private val chatMessageRepository: ChatMessageRepository,
    private val connectionRepository: ConnectionRepository,
    @param:Value("\${chat.first-chat.inactivity-threshold-minutes:5}")
    private val inactivityThresholdMinutes: Long,
    @param:Value("\${scheduler.inactivity-check-job.batch-size:100}")
    private val batchSize: Int = 100
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
                fetchedCandidates = chatService.findInactiveChatIds(
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
                val chat: Chat = chatService.findByIdOrThrow(chatId)
                val abandonedUserIds: List<UUID> = if (chat.chatType == ChatType.SECOND_CHAT) {
                    resolveInactiveUsers(chat.id, chat.connectionId, threshold)
                } else {
                    emptyList()
                }

                val changed = chatService.endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.ABANDONED,
                    endedReason = ChatEndReason.INACTIVITY_TIMEOUT,
                    abandonedUserIds = abandonedUserIds
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
            startedAt = startedAt
        )
        return summary
    }

    private fun resolveInactiveUsers(chatId: UUID, connectionId: UUID?, threshold: OffsetDateTime): List<UUID> {
        if (connectionId == null) return emptyList()
        val connection = connectionRepository.findById(connectionId).orElse(null) ?: return emptyList()
        val userIds = listOf(connection.userAId, connection.userBId)
        val recentSenders = chatMessageRepository.findByChatSessionIdAndSentAtAfter(chatId, threshold)
            .map { it.senderId }
            .toSet()
        return userIds.filter { it !in recentSenders }
    }
}
