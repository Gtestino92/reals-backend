package com.reals.backend.service

import com.reals.backend.domain.NotificationPreferenceCategory
import com.reals.backend.domain.PushNotificationType
import com.reals.backend.repository.UserRepository
import com.reals.backend.repository.UserNotificationPreferenceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class NotificationPreferenceServiceTest {

    private val repository = Mockito.mock(UserNotificationPreferenceRepository::class.java)
    private val userRepository = Mockito.mock(UserRepository::class.java)
    private val service = NotificationPreferenceService(repository, userRepository)

    @Test
    fun `maps every current push notification type to an explicit category`() {
        val expected =
            mapOf(
                PushNotificationType.MATCH_FOUND to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.VISUAL_REVIEW_AVAILABLE to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.SCHEDULING_AVAILABLE to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.SCHEDULING_CONFIRMED to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.SECOND_CHAT_STARTED to NotificationPreferenceCategory.ACTIVITY,
                PushNotificationType.MATCHMAKING_AVAILABLE to NotificationPreferenceCategory.AVAILABILITY,
                PushNotificationType.VISUAL_REVIEW_REMINDER to NotificationPreferenceCategory.REMINDERS,
                PushNotificationType.SECOND_CHAT_REMINDER to NotificationPreferenceCategory.REMINDERS,
                PushNotificationType.MATCH_FOUND_INVALIDATED to NotificationPreferenceCategory.SYSTEM
            )

        assertEquals(PushNotificationType.entries.toSet(), expected.keys)
        expected.forEach { (type, category) ->
            assertEquals(category, service.categoryFor(type), type.name)
        }
    }
}
