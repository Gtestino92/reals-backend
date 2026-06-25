package com.reals.backend.service

import com.reals.backend.domain.PushDeviceToken

data class PushNotification(
    val title: String,
    val body: String,
    val data: Map<String, String>
)

data class PushSendResult(
    val sent: Boolean,
    val providerMessageIds: List<String> = emptyList(),
    val invalidTokens: List<String> = emptyList(),
    val errorMessage: String? = null
)

interface PushNotificationSender {
    fun sendToTokens(
        tokens: List<PushDeviceToken>,
        notification: PushNotification
    ): PushSendResult
}
