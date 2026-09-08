package com.reals.backend.service.notification.sender

import java.util.UUID

data class PushNotification(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val androidTtlMillis: Long? = null,
    val androidNotificationTag: String? = null,
    val includeNotificationPayload: Boolean = true,
    val androidPriority: PushNotificationAndroidPriority? = null
)

enum class PushNotificationAndroidPriority {
    HIGH
}

data class PushNotificationToken(
    val id: UUID,
    val token: String
)

data class PushSendResult(
    val sent: Boolean,
    val providerMessageIds: List<String> = emptyList(),
    val invalidTokens: List<String> = emptyList(),
    val errorMessage: String? = null
)

interface PushNotificationSender {
    fun sendToTokens(
        tokens: List<PushNotificationToken>,
        notification: PushNotification
    ): PushSendResult
}
