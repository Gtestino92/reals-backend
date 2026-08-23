package com.reals.backend.service.notification

import com.reals.backend.config.MatchmakingProperties
import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisode
import com.reals.backend.domain.MatchmakingAvailabilityNotificationEpisodeStatus
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.MatchmakingAvailabilityNotificationEpisodeRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.service.matching.MatchmakingAvailabilityService
import com.reals.backend.service.matching.VisualAdvancementCapService
import com.reals.backend.service.notification.sender.PushNotification
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class MatchmakingAvailabilityNotificationProcessingResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
) {
    operator fun plus(other: MatchmakingAvailabilityNotificationProcessingResult): MatchmakingAvailabilityNotificationProcessingResult =
        MatchmakingAvailabilityNotificationProcessingResult(
            succeeded = succeeded + other.succeeded,
            skipped = skipped + other.skipped,
            failed = failed + other.failed
        )
}

@Service
class MatchmakingAvailabilityNotificationService(
    private val episodeRepository: MatchmakingAvailabilityNotificationEpisodeRepository,
    private val visualAdvancementCapService: VisualAdvancementCapService,
    private val matchmakingAvailabilityService: MatchmakingAvailabilityService,
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val recipientPreparationService: PushRecipientPreparationService,
    private val preparedPushCommandProcessor: PreparedPushCommandProcessor,
    private val transactionTemplate: TransactionTemplate,
    private val matchmakingProperties: MatchmakingProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    fun reconcileAfterVisualAdvancementCreated(
        userIds: Collection<UUID>,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        userIds
            .distinct()
            .sorted()
            .forEach { userId -> reconcileUserInCurrentTransaction(userId = userId, now = now) }
    }

    fun discoverMissingOrStaleEpisodes(
        now: OffsetDateTime = OffsetDateTime.now(),
        maxUsers: Int
    ): Int {
        require(maxUsers > 0) {
            "scheduler.matchmaking-availability-notification-job.discovery-batch-size must be positive"
        }

        val limit = matchmakingProperties.visualAdvancement.maxPerWindow
        val cutoff = now.minusHours(matchmakingProperties.visualAdvancement.windowHours)
        var reconciled = 0
        var cursor: UUID? = null
        val pageSize = maxUsers + 1

        while (reconciled < maxUsers) {
            val page =
                if (cursor == null) {
                    episodeRepository.findUsersAtOrOverVisualAdvancementCap(
                        cutoff = cutoff,
                        limit = limit,
                        pageable = PageRequest.of(0, pageSize)
                    )
                } else {
                    episodeRepository.findUsersAtOrOverVisualAdvancementCapAfter(
                        cutoff = cutoff,
                        limit = limit,
                        cursorUserId = cursor,
                        pageable = PageRequest.of(0, pageSize)
                    )
                }.map { UUID.fromString(it) }

            if (page.isEmpty()) {
                return reconciled
            }

            for (userId in page.take(maxUsers - reconciled)) {
                transactionTemplate.executeWithoutResult {
                    reconcileUserInCurrentTransaction(userId = userId, now = now)
                }
                reconciled += 1
                cursor = userId
            }

            if (page.size < pageSize) {
                return reconciled
            }
        }

        return reconciled
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun findDueEpisodeIds(
        now: OffsetDateTime,
        batchSize: Int
    ): List<UUID> {
        require(batchSize > 0) {
            "scheduler.matchmaking-availability-notification-job.batch-size must be positive"
        }

        return episodeRepository.findDueEpisodeIds(
            status = MatchmakingAvailabilityNotificationEpisodeStatus.PENDING,
            now = now,
            pageable = PageRequest.of(0, batchSize)
        )
    }

    fun processDueEpisode(
        episodeId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MatchmakingAvailabilityNotificationProcessingResult {
        val prepared = prepareDueEpisode(episodeId = episodeId, now = now)
        val sent =
            prepared.commands
                .map { command -> sendAndResolve(command, now) }
                .fold(MatchmakingAvailabilityNotificationProcessingResult()) { total, result -> total + result }

        return sent + MatchmakingAvailabilityNotificationProcessingResult(skipped = prepared.skipped)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteByUserId(userId: UUID): Int =
        episodeRepository.deleteByUserId(userId)

    private fun reconcileUserInCurrentTransaction(
        userId: UUID,
        now: OffsetDateTime
    ) {
        val capStatus = visualAdvancementCapService.statusFor(userId = userId, now = now)
        if (!capStatus.blocked) {
            return
        }

        val nextCheckAt =
            capStatus.nextAvailableAt
                ?: run {
                    log.warn("Visual advancement cap blocked user {} without nextAvailableAt", userId)
                    return
                }

        val existing =
            episodeRepository.findByUserIdAndStatusForUpdate(
                userId = userId,
                status = MatchmakingAvailabilityNotificationEpisodeStatus.PENDING
            )

        if (existing != null) {
            existing.nextCheckAt = nextCheckAt
            existing.updatedAt = now
            episodeRepository.save(existing)
            return
        }

        try {
            episodeRepository.saveAndFlush(
                MatchmakingAvailabilityNotificationEpisode(
                    userId = userId,
                    nextCheckAt = nextCheckAt,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            val concurrent =
                episodeRepository.findByUserIdAndStatusForUpdate(
                    userId = userId,
                    status = MatchmakingAvailabilityNotificationEpisodeStatus.PENDING
                )
            if (concurrent == null) {
                throw ex
            }
            concurrent.nextCheckAt = nextCheckAt
            concurrent.updatedAt = now
            episodeRepository.save(concurrent)
        }
    }

    private fun prepareDueEpisode(
        episodeId: UUID,
        now: OffsetDateTime
    ): PreparedPushBatch =
        transactionTemplate.execute {
            val episode =
                episodeRepository.findByIdForUpdate(episodeId)
                    ?: return@execute PreparedPushBatch(skipped = 1)

            if (episode.status != MatchmakingAvailabilityNotificationEpisodeStatus.PENDING) {
                return@execute PreparedPushBatch(skipped = 1)
            }
            if (episode.nextCheckAt.isAfter(now)) {
                return@execute PreparedPushBatch(skipped = 1)
            }

            val capStatus = visualAdvancementCapService.statusFor(
                userId = episode.userId,
                now = now
            )
            if (capStatus.blocked) {
                val nextCheckAt =
                    capStatus.nextAvailableAt
                        ?: run {
                            log.warn("Due availability episode {} remained blocked without nextAvailableAt", episode.id)
                            return@execute PreparedPushBatch(skipped = 1)
                        }
                episode.nextCheckAt = nextCheckAt
                episode.updatedAt = now
                episodeRepository.save(episode)
                return@execute PreparedPushBatch(skipped = 1)
            }

            if (matchmakingQueueRepository.existsByUserId(episode.userId)) {
                terminal(
                    episode = episode,
                    status = MatchmakingAvailabilityNotificationEpisodeStatus.CANCELLED,
                    now = now
                )
                return@execute PreparedPushBatch(skipped = 1)
            }

            val availability = matchmakingAvailabilityService.availabilityForUserNotInQueue(
                userId = episode.userId,
                now = now
            )
            if (!availability.canSearch) {
                terminal(
                    episode = episode,
                    status = MatchmakingAvailabilityNotificationEpisodeStatus.CANCELLED,
                    now = now
                )
                return@execute PreparedPushBatch(skipped = 1)
            }

            val recipient =
                recipientPreparationService.prepareRecipient(
                    userId = episode.userId,
                    notificationType = PushNotificationType.MATCHMAKING_AVAILABLE,
                    aggregateId = episode.id,
                    now = now
                ) { matchmakingAvailableNotification() }

            if (recipient.skipped) {
                terminal(
                    episode = episode,
                    status = MatchmakingAvailabilityNotificationEpisodeStatus.HANDLED,
                    now = now
                )
                return@execute PreparedPushBatch(skipped = 1)
            }

            PreparedPushBatch(
                commands = listOfNotNull(recipient.command),
                skipped = 0
            )
        }

    private fun sendAndResolve(
        command: PreparedPushCommand,
        now: OffsetDateTime
    ): MatchmakingAvailabilityNotificationProcessingResult {
        val outcome = preparedPushCommandProcessor.process(command, now)

        transactionTemplate.executeWithoutResult {
            val episode = episodeRepository.findByIdForUpdate(command.aggregateId) ?: return@executeWithoutResult
            if (
                episode.status == MatchmakingAvailabilityNotificationEpisodeStatus.PENDING &&
                outcome != PreparedPushCommandOutcome.PROVIDER_EXCEPTION
            ) {
                terminal(
                    episode = episode,
                    status = MatchmakingAvailabilityNotificationEpisodeStatus.HANDLED,
                    now = now
                )
            }
        }

        return when (outcome) {
            PreparedPushCommandOutcome.SENT -> MatchmakingAvailabilityNotificationProcessingResult(succeeded = 1)
            PreparedPushCommandOutcome.NOT_SENT -> MatchmakingAvailabilityNotificationProcessingResult(failed = 1)
            PreparedPushCommandOutcome.PROVIDER_EXCEPTION -> MatchmakingAvailabilityNotificationProcessingResult(failed = 1)
        }
    }

    private fun terminal(
        episode: MatchmakingAvailabilityNotificationEpisode,
        status: MatchmakingAvailabilityNotificationEpisodeStatus,
        now: OffsetDateTime
    ) {
        episode.status = status
        episode.handledAt = now
        episode.updatedAt = now
        episodeRepository.save(episode)
    }

    private fun matchmakingAvailableNotification(): PushNotification =
        PushNotification(
            title = "Ya podés buscar de nuevo",
            body = "Cuando quieras, podés volver a buscar a alguien nuevo.",
            data = mapOf("type" to PushNotificationType.MATCHMAKING_AVAILABLE.name),
            androidTtlMillis = Duration.ofHours(1).toMillis(),
            androidNotificationTag = "matchmaking-availability"
        )
}
