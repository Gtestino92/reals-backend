package com.reals.backend.scheduler

import com.reals.backend.domain.ConnectionState
import com.reals.backend.domain.NegotiationStatus
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.service.ChatService
import com.reals.backend.service.ConnectionService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Makes second chats available when their negotiated start time has arrived.
 *
 * Scheduling confirmation only reserves the second-chat slot and moves the
 * connection to SECOND_CHAT_SCHEDULED. This job creates a visible AVAILABLE
 * chat inside the early-entry tolerance window; the chat becomes ACTIVE when a
 * participant enters it or sends the first message.
 */
@Component
class ScheduledSecondChatStartJob(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val connectionService: ConnectionService,
    private val chatService: ChatService,

    @param:Value("\${chat.second-chat.early-entry-tolerance-minutes:10}")
    private val earlyEntryToleranceMinutes: Long = 10
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.scheduled-second-chat-start-job.fixed-delay}")
    @SchedulerLock(
        name = "ScheduledSecondChatStartJob",
        lockAtLeastFor = "PT15S",
        lockAtMostFor = "PT2M"
    )
    fun run() {
        val startedAt = System.nanoTime()
        val now = OffsetDateTime.now()
        val availabilityCutoff = now.plusMinutes(earlyEntryToleranceMinutes)
        val due =
            negotiationRepository.findDueConfirmedNegotiations(
                status = NegotiationStatus.CONFIRMED,
                now = availabilityCutoff
            )

        var succeeded = 0
        var skipped = 0
        var failed = 0

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
                    skipped += 1
                    return@forEach
                }

                val availableAt = checkNotNull(negotiation.confirmedDateTime) {
                    "Confirmed negotiation ${negotiation.id} has no confirmedDateTime"
                }

                if (chatService.isSecondChatWindowExpired(availableAt, now)) {
                    if (
                        chatService.closeExpiredScheduledSecondChatWindow(
                            connectionId = connection.id,
                            confirmedDateTime = availableAt
                        )
                    ) {
                        log.info(
                            "ScheduledSecondChatStartJob - closed expired scheduled second-chat window for connection={}",
                            connection.id
                        )
                        succeeded += 1
                    } else {
                        skipped += 1
                    }
                    return@forEach
                }

                chatService.makeSecondChatAvailable(
                    matchId = connection.matchId,
                    connectionId = connection.id,
                    availableAt = availableAt
                )

                log.info(
                    "ScheduledSecondChatStartJob - made second chat available for connection={}",
                    connection.id
                )
                succeeded += 1
            } catch (ex: Exception) {
                log.error(
                    "ScheduledSecondChatStartJob - failed for connection={}",
                    negotiation.connectionId,
                    ex
                )
                failed += 1
            }
        }

        log.logJobSummary(
            jobName = "ScheduledSecondChatStartJob",
            summary = JobRunSummary(
                processed = due.size,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed
            ),
            startedAt = startedAt
        )
    }
}
