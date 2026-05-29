package com.reals.backend.scheduler

import com.reals.backend.domain.Chat
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
    @param:Value("\${scheduler.inactivity-check-job.inactivity-threshold-minutes:30}")
    private val inactivityThresholdMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString =
            "\${scheduler.inactivity-check-job.fixed-delay}"
    )
    @SchedulerLock(name = "InactivityCheckJob", lockAtLeastFor = "PT15s", lockAtMostFor = "PT1M")
    fun run() {

        val inactiveChats =
            chatService.findInactiveChats(
                inactivityThresholdMinutes
            )

        val threshold = OffsetDateTime.now().minusMinutes(inactivityThresholdMinutes)

        if (inactiveChats.isEmpty()) return

        var succeeded = 0
        var failed = 0

        inactiveChats.forEach { chat: Chat ->
            try {
                val abandonedUserIds: List<UUID> = if (chat.chatType == ChatType.SECOND_CHAT) {
                    resolveInactiveUsers(chat.id, chat.connectionId, threshold)
                } else {
                    emptyList()
                }

                chatService.endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.ABANDONED,
                    abandonedUserIds = abandonedUserIds
                )
                succeeded += 1
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "InactivityCheckJob - failed to abandon chat={}",
                    chat.id,
                    ex
                )
            }
        }

        log.info(
            "InactivityCheckJob - processed={} succeeded={} failed={}",
            inactiveChats.size,
            succeeded,
            failed
        )
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
