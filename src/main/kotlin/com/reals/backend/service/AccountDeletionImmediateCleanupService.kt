package com.reals.backend.service

import com.reals.backend.repository.ActiveEngagementLockRepository
import com.reals.backend.repository.ConnectionHomeDismissalRepository
import com.reals.backend.repository.MatchmakingQueueRepository
import com.reals.backend.repository.PushDeviceTokenRepository
import com.reals.backend.repository.PushNotificationDeliveryRepository
import com.reals.backend.repository.UserNotificationPreferenceRepository
import com.reals.backend.repository.UserHomeStatusRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AccountDeletionImmediateCleanupService(
    private val matchmakingQueueRepository: MatchmakingQueueRepository,
    private val activeEngagementLockRepository: ActiveEngagementLockRepository,
    private val pushDeviceTokenRepository: PushDeviceTokenRepository,
    private val pushNotificationDeliveryRepository: PushNotificationDeliveryRepository,
    private val userNotificationPreferenceRepository: UserNotificationPreferenceRepository,
    private val connectionHomeDismissalRepository: ConnectionHomeDismissalRepository,
    private val userHomeStatusRepository: UserHomeStatusRepository
) {

    fun deleteEphemeralOperationalData(userId: UUID) {
        matchmakingQueueRepository.deleteByUserId(userId)
        activeEngagementLockRepository.deleteByUserId(userId)
        pushDeviceTokenRepository.deleteByUserId(userId)
        pushNotificationDeliveryRepository.deleteByUserId(userId)
        userNotificationPreferenceRepository.deleteByUserId(userId)
        connectionHomeDismissalRepository.deleteByUserId(userId)
        userHomeStatusRepository.deleteByUserId(userId)
    }
}
