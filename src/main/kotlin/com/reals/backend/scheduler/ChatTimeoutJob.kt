package com.reals.backend.scheduler

import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/* Expires chat sessions that have exceeded their absolute timoutAt deadline
    The client uses timeoutAt to display a countdown - this jobs marks then EXPIRED in the DB
    Runs every 2 minutes - fast response needed so users don't wait on a dead session.
 */
@Component
@ConditionalOnBean(LockProvider::class)
class ChatTimeoutJob(
    private val chatService: ChatService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.chat-timeout-job.fixed-delay}")
    @SchedulerLock(name = "ChatTimeoutJob", lockAtLeastFor = "PT30s", lockAtMostFor = "PT2M")
    fun run() {
        val expiredChats = chatService.findTimedOutChats()
        if (expiredChats.isEmpty()) return
        log.info("ChatTimeoutJob expiring : ${expiredChats.size} chats")
        expiredChats.forEach { chat ->
            chatService.endChat(
                chatId = chat.id,
                finalStatus = ChatStatus.EXPIRED
            )
        }
    }
}
