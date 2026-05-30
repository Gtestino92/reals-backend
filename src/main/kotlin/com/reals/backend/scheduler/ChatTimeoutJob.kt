package com.reals.backend.scheduler

import com.reals.backend.domain.ChatStatus
import com.reals.backend.service.ChatService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/* Expires chat sessions that have exceeded their absolute timoutAt deadline
    The client uses timeoutAt to display a countdown - this jobs marks then EXPIRED in the DB
    Runs every 2 minutes - fast response needed so users don't wait on a dead session.
 */
@Component
class ChatTimeoutJob(
    private val chatService: ChatService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.chat-timeout-job.fixed-delay}")
    @SchedulerLock(name = "ChatTimeoutJob", lockAtLeastFor = "PT30s", lockAtMostFor = "PT2M")
    fun run() {
        processTimedOutChats()
    }

    fun runNowForDev() {
        processTimedOutChats()
    }

    private fun processTimedOutChats() {
        val expiredChats = chatService.findTimedOutChats()
        if (expiredChats.isEmpty()) return

        var succeeded = 0
        var failed = 0

        expiredChats.forEach { chat ->
            try {
                chatService.endChat(
                    chatId = chat.id,
                    finalStatus = ChatStatus.EXPIRED
                )
                succeeded += 1
            } catch (ex: Exception) {
                failed += 1
                log.error(
                    "ChatTimeoutJob - failed to expire chat={}",
                    chat.id,
                    ex
                )
            }
        }

        log.info(
            "ChatTimeoutJob - processed={} succeeded={} failed={}",
            expiredChats.size,
            succeeded,
            failed
        )
    }
}
