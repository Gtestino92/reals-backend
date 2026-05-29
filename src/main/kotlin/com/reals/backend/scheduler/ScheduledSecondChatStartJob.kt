package com.reals.backend.scheduler

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Makes second chats available when their negotiated start time has arrived.
 *
 * Scheduling confirmation only reserves the second-chat slot and moves the
 * connection to SECOND_CHAT_SCHEDULED. This job creates a visible AVAILABLE
 * chat at confirmedDateTime; the chat becomes ACTIVE when a participant enters
 * it or sends the first message.
 */
@Component
class ScheduledSecondChatStartJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val connectionService: ConnectionService,
    private val chatService: ChatService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.scheduled-second-chat-start-job.fixed-delay}")
    @SchedulerLock(
        name = "ScheduledSecondChatStartJob",
        lockAtLeastFor = "PT15S",
        lockAtMostFor = "PT2M"
    )
    @Transactional
    fun run() {
        val due =
            negotiationRepository.findDueConfirmedNegotiations(
                status = NegotiationStatus.CONFIRMED,
                now = OffsetDateTime.now()
            )

        if (due.isEmpty()) {
            log.debug("ScheduledSecondChatStartJob - no due negotiations found")
            return
        }

        log.info(
            "ScheduledSecondChatStartJob - found {} due negotiation(s)",
            due.size
        )

        due.forEach { negotiation ->
            try {
                val connection =
                    connectionService.findByIdOrThrow(negotiation.connectionId)

                if (connection.state != ConnectionState.SECOND_CHAT_SCHEDULED) {
                    log.debug(
                        "ScheduledSecondChatStartJob - skipping connection={} state={}",
                        connection.id,
                        connection.state
                    )
                    return@forEach
                }

                chatService.startSecondChat(
                    matchId = connection.matchId,
                    connectionId = connection.id,
                    availableAt = checkNotNull(negotiation.confirmedDateTime) {
                        "Confirmed negotiation ${negotiation.id} has no confirmedDateTime"
                    }
                )
                connectionService.transitionToSecondChatAvailable(connection.id)

                log.info(
                    "ScheduledSecondChatStartJob - made second chat available for connection={}",
                    connection.id
                )
            } catch (ex: Exception) {
                log.error(
                    "ScheduledSecondChatStartJob - failed for connection={}",
                    negotiation.connectionId,
                    ex
                )
            }
        }
    }
}
