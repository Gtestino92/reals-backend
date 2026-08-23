package com.reals.backend.service

import com.reals.backend.domain.NotificationPreferenceCategory
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.domain.UserNotificationPreference
import com.reals.backend.repository.UserRepository
import com.reals.backend.repository.UserNotificationPreferenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class NotificationPreferenceSettings(
    val activityEnabled: Boolean,
    val remindersEnabled: Boolean,
    val availabilityEnabled: Boolean
)

@Service
class NotificationPreferenceService(
    private val preferenceRepository: UserNotificationPreferenceRepository,
    private val userRepository: UserRepository
) {

    @Transactional(readOnly = true)
    fun preferencesFor(userId: UUID): NotificationPreferenceSettings {
        val preferences =
            preferenceRepository
                .findByUserIdAndCategoryIn(userId, CONFIGURABLE_CATEGORIES)
                .associateBy { it.category }

        return NotificationPreferenceSettings(
            activityEnabled = preferences[NotificationPreferenceCategory.ACTIVITY]?.enabled ?: true,
            remindersEnabled = preferences[NotificationPreferenceCategory.REMINDERS]?.enabled ?: true,
            availabilityEnabled = preferences[NotificationPreferenceCategory.AVAILABILITY]?.enabled ?: true
        )
    }

    @Transactional
    fun updatePreferences(
        userId: UUID,
        input: NotificationPreferenceSettings,
        now: OffsetDateTime = OffsetDateTime.now()
    ): NotificationPreferenceSettings {
        if (userRepository.findAllByIdForUpdate(listOf(userId)).isEmpty()) {
            userRepository.findById(userId).orElseThrow()
        }

        upsertPreference(
            userId = userId,
            category = NotificationPreferenceCategory.ACTIVITY,
            enabled = input.activityEnabled,
            now = now
        )
        upsertPreference(
            userId = userId,
            category = NotificationPreferenceCategory.REMINDERS,
            enabled = input.remindersEnabled,
            now = now
        )
        upsertPreference(
            userId = userId,
            category = NotificationPreferenceCategory.AVAILABILITY,
            enabled = input.availabilityEnabled,
            now = now
        )

        return preferencesFor(userId)
    }

    private fun upsertPreference(
        userId: UUID,
        category: NotificationPreferenceCategory,
        enabled: Boolean,
        now: OffsetDateTime
    ) {
        val preference = preferenceRepository.findByUserIdAndCategory(
            userId = userId,
            category = category
        )

        if (preference == null) {
            preferenceRepository.save(
                UserNotificationPreference(
                    userId = userId,
                    category = category,
                    enabled = enabled,
                    createdAt = now,
                    updatedAt = now
                )
            )
            return
        }

        preference.enabled = enabled
        preference.updatedAt = now
        preferenceRepository.save(preference)
    }

    @Transactional(readOnly = true)
    fun isAllowed(
        userId: UUID,
        notificationType: PushNotificationType
    ): Boolean =
        when (categoryFor(notificationType)) {
            NotificationPreferenceCategory.SYSTEM -> true
            NotificationPreferenceCategory.ACTIVITY,
            NotificationPreferenceCategory.REMINDERS,
            NotificationPreferenceCategory.AVAILABILITY -> {
                val preferences = preferencesFor(userId)
                when (categoryFor(notificationType)) {
                    NotificationPreferenceCategory.ACTIVITY -> preferences.activityEnabled
                    NotificationPreferenceCategory.REMINDERS -> preferences.remindersEnabled
                    NotificationPreferenceCategory.AVAILABILITY -> preferences.availabilityEnabled
                    NotificationPreferenceCategory.SYSTEM -> true
                }
            }
        }

    fun categoryFor(notificationType: PushNotificationType): NotificationPreferenceCategory =
        when (notificationType) {
            PushNotificationType.MATCH_FOUND -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.VISUAL_REVIEW_AVAILABLE -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.SCHEDULING_AVAILABLE -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.SCHEDULING_CONFIRMED -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.SECOND_CHAT_STARTED -> NotificationPreferenceCategory.ACTIVITY
            PushNotificationType.MATCHMAKING_AVAILABLE -> NotificationPreferenceCategory.AVAILABILITY
            PushNotificationType.VISUAL_REVIEW_REMINDER -> NotificationPreferenceCategory.REMINDERS
            PushNotificationType.SECOND_CHAT_REMINDER -> NotificationPreferenceCategory.REMINDERS
            PushNotificationType.MATCH_FOUND_INVALIDATED -> NotificationPreferenceCategory.SYSTEM
        }

    companion object {
        val CONFIGURABLE_CATEGORIES =
            listOf(
                NotificationPreferenceCategory.ACTIVITY,
                NotificationPreferenceCategory.REMINDERS,
                NotificationPreferenceCategory.AVAILABILITY
            )
    }
}
