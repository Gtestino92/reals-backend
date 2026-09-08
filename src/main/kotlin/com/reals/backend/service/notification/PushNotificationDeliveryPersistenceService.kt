package com.reals.backend.service.notification

import com.reals.backend.domain.PushDeliveryStatus
import com.reals.backend.domain.PushNotificationDelivery
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.PushDeviceTokenRepository
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.service.notification.sender.PushNotificationToken
import com.reals.backend.service.notification.sender.PushSendResult
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

enum class DeliveryPersistenceOutcome {
    SAVED,
    DUPLICATE
}

@Service
class PushNotificationDeliveryPersistenceService(
    private val deliveryRepository: PushNotificationDeliveryRepository,
    private val tokenRepository: PushDeviceTokenRepository,
    private val transactionTemplate: TransactionTemplate,
    private val pushNotificationMetrics: PushNotificationMetrics = PushNotificationMetrics.noop()
) : PushNotificationResultPersistence {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun deliveryExists(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID
    ): Boolean =
        deliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
            userId = userId,
            notificationType = notificationType,
            aggregateId = aggregateId
        ) != null

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun activeTokenSnapshots(userId: UUID): List<PushNotificationToken> =
        tokenRepository.findByUserIdAndEnabledTrue(userId)
            .map { PushNotificationToken(id = it.id, token = it.token) }

    @Transactional(propagation = Propagation.MANDATORY)
    fun saveSkippedNoActiveTokenInCurrentTransaction(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        now: OffsetDateTime
    ) {
        deliveryRepository.saveAndFlush(
            delivery(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN,
                now = now
            )
        )
        recordDeliveryAfterCommit(
            notificationType = notificationType,
            status = PushDeliveryStatus.SKIPPED_NO_ACTIVE_TOKEN
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun saveSkippedAlreadyJoinedInCurrentTransaction(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        now: OffsetDateTime
    ) {
        deliveryRepository.saveAndFlush(
            delivery(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                status = PushDeliveryStatus.SKIPPED_ALREADY_JOINED,
                now = now
            )
        )
        recordDeliveryAfterCommit(
            notificationType = notificationType,
            status = PushDeliveryStatus.SKIPPED_ALREADY_JOINED
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun saveSkippedUserPreferenceInCurrentTransaction(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        now: OffsetDateTime
    ) {
        deliveryRepository.saveAndFlush(
            delivery(
                userId = userId,
                notificationType = notificationType,
                aggregateId = aggregateId,
                status = PushDeliveryStatus.SKIPPED_USER_PREFERENCE,
                now = now
            )
        )
        recordDeliveryAfterCommit(
            notificationType = notificationType,
            status = PushDeliveryStatus.SKIPPED_USER_PREFERENCE
        )
    }

    override fun persistSendResult(
        command: PreparedPushCommand,
        sendResult: PushSendResult,
        now: OffsetDateTime
    ): DeliveryPersistenceOutcome {
        val status =
            if (sendResult.sent) PushDeliveryStatus.SENT else PushDeliveryStatus.FAILED
        val sentAt = if (sendResult.sent) now else null
        val errorMessage =
            if (sendResult.sent) {
                sendResult.errorMessage
            } else {
                sendResult.errorMessage ?: "Push sender returned no successful deliveries"
            }

        return persistDeliveryResult(
            command = command,
            status = status,
            sentAt = sentAt,
            providerMessageId = sendResult.providerMessageIds.joinToString(",").ifBlank { null },
            errorMessage = errorMessage,
            invalidTokens = sendResult.invalidTokens,
            now = now
        )
    }

    override fun persistFailure(
        command: PreparedPushCommand,
        errorMessage: String,
        now: OffsetDateTime
    ): DeliveryPersistenceOutcome =
        persistDeliveryResult(
            command = command,
            status = PushDeliveryStatus.FAILED,
            sentAt = null,
            providerMessageId = null,
            errorMessage = errorMessage,
            invalidTokens = emptyList(),
            now = now
        )

    private fun persistDeliveryResult(
        command: PreparedPushCommand,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime?,
        providerMessageId: String?,
        errorMessage: String?,
        invalidTokens: List<String>,
        now: OffsetDateTime
    ): DeliveryPersistenceOutcome {
        return try {
            val result = persistDeliveryResultAttempt(
                command = command,
                status = status,
                sentAt = sentAt,
                providerMessageId = providerMessageId,
                errorMessage = errorMessage,
                invalidTokens = invalidTokens,
                now = now
            )
            pushNotificationMetrics.recordInvalidTokensDisabled(
                notificationType = command.notificationType,
                count = result.disabledInvalidTokens
            )
            pushNotificationMetrics.recordDelivery(
                notificationType = command.notificationType,
                status = status,
                persistenceOutcome = result.outcome
            )
            result.outcome
        } catch (ex: DataIntegrityViolationException) {
            val disabledInvalidTokens = disableInvalidTokensInNewTransaction(invalidTokens)
            log.info(
                "Push delivery already exists after provider call user={} type={} aggregate={}",
                command.userId,
                command.notificationType,
                command.aggregateId
            )
            pushNotificationMetrics.recordInvalidTokensDisabled(
                notificationType = command.notificationType,
                count = disabledInvalidTokens
            )
            pushNotificationMetrics.recordDelivery(
                notificationType = command.notificationType,
                status = status,
                persistenceOutcome = DeliveryPersistenceOutcome.DUPLICATE
            )
            DeliveryPersistenceOutcome.DUPLICATE
        }
    }

    private fun persistDeliveryResultAttempt(
        command: PreparedPushCommand,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime?,
        providerMessageId: String?,
        errorMessage: String?,
        invalidTokens: List<String>,
        now: OffsetDateTime
    ): DeliveryPersistenceAttemptResult =
        transactionTemplate.execute {
            val disabledInvalidTokens = disableInvalidTokens(invalidTokens)

            val existingDelivery =
                deliveryRepository.findByUserIdAndNotificationTypeAndAggregateId(
                    userId = command.userId,
                    notificationType = command.notificationType,
                    aggregateId = command.aggregateId
                )
            if (existingDelivery != null) {
                return@execute DeliveryPersistenceAttemptResult(
                    outcome = DeliveryPersistenceOutcome.DUPLICATE,
                    disabledInvalidTokens = disabledInvalidTokens
                )
            }

            deliveryRepository.saveAndFlush(
                delivery(
                    userId = command.userId,
                    notificationType = command.notificationType,
                    aggregateId = command.aggregateId,
                    status = status,
                    sentAt = sentAt,
                    providerMessageId = providerMessageId,
                    errorMessage = errorMessage,
                    now = now
                )
            )
            DeliveryPersistenceAttemptResult(
                outcome = DeliveryPersistenceOutcome.SAVED,
                disabledInvalidTokens = disabledInvalidTokens
            )
        }

    private fun disableInvalidTokensInNewTransaction(tokens: List<String>): Int {
        if (tokens.isEmpty()) {
            return 0
        }

        return transactionTemplate.execute {
            disableInvalidTokens(tokens)
        }
    }

    private fun disableInvalidTokens(tokens: List<String>): Int {
        val updatedAt = OffsetDateTime.now()
        val normalizedTokens = tokens
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return normalizedTokens.sumOf { token ->
            tokenRepository.disableByToken(
                token = token,
                updatedAt = updatedAt
            )
        }
    }

    private fun recordDeliveryAfterCommit(
        notificationType: PushNotificationType,
        status: PushDeliveryStatus
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pushNotificationMetrics.recordDelivery(
                notificationType = notificationType,
                status = status,
                persistenceOutcome = DeliveryPersistenceOutcome.SAVED
            )
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    pushNotificationMetrics.recordDelivery(
                        notificationType = notificationType,
                        status = status,
                        persistenceOutcome = DeliveryPersistenceOutcome.SAVED
                    )
                }
            }
        )
    }

    private fun delivery(
        userId: UUID,
        notificationType: PushNotificationType,
        aggregateId: UUID,
        status: PushDeliveryStatus,
        sentAt: OffsetDateTime? = null,
        providerMessageId: String? = null,
        errorMessage: String? = null,
        now: OffsetDateTime
    ): PushNotificationDelivery =
        PushNotificationDelivery(
            userId = userId,
            notificationType = notificationType,
            aggregateId = aggregateId,
            sentAt = sentAt,
            status = status,
            providerMessageId = providerMessageId,
            errorMessage = errorMessage,
            createdAt = now,
            updatedAt = now
        )
}

private data class DeliveryPersistenceAttemptResult(
    val outcome: DeliveryPersistenceOutcome,
    val disabledInvalidTokens: Int
)

interface PushNotificationResultPersistence {
    fun persistSendResult(
        command: PreparedPushCommand,
        sendResult: PushSendResult,
        now: OffsetDateTime = OffsetDateTime.now()
    ): DeliveryPersistenceOutcome

    fun persistFailure(
        command: PreparedPushCommand,
        errorMessage: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): DeliveryPersistenceOutcome
}
