package com.reals.backend.service.notification.sender

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("local-firebase", "dev", "prod")
class FirebasePushNotificationSender(
    private val firebaseMessaging: FirebaseMessaging
) : PushNotificationSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendToTokens(
        tokens: List<PushNotificationToken>,
        notification: PushNotification
    ): PushSendResult {
        if (tokens.isEmpty()) {
            return PushSendResult(sent = false, errorMessage = "No active push tokens")
        }

        val providerMessageIds = mutableListOf<String>()
        val invalidTokens = mutableListOf<String>()
        val errors = mutableListOf<String>()

        tokens.forEach { deviceToken ->
            try {
                val message = Message.builder()
                    .setToken(deviceToken.token)
                    .setNotification(
                        com.google.firebase.messaging.Notification.builder()
                            .setTitle(notification.title)
                            .setBody(notification.body)
                            .build()
                    )
                    .putAllData(notification.data)
                    .build()

                providerMessageIds += firebaseMessaging.send(message)
            } catch (ex: FirebaseMessagingException) {
                if (ex.isInvalidRegistrationToken()) {
                    invalidTokens += deviceToken.token
                }

                val error = ex.message
                    ?: ex.messagingErrorCode?.name
                    ?: ex.errorCode?.name
                    ?: ex.javaClass.simpleName
                errors += error
                log.warn(
                    "Firebase push send failed for token {}: {}",
                    deviceToken.id,
                    error
                )
            } catch (ex: Exception) {
                val error = ex.message ?: ex.javaClass.simpleName
                errors += error
                log.warn(
                    "Push send failed for token {}: {}",
                    deviceToken.id,
                    error
                )
            }
        }

        return PushSendResult(
            sent = providerMessageIds.isNotEmpty(),
            providerMessageIds = providerMessageIds,
            invalidTokens = invalidTokens,
            errorMessage = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
    }

    private fun FirebaseMessagingException.isInvalidRegistrationToken(): Boolean =
        messagingErrorCode == MessagingErrorCode.UNREGISTERED ||
            messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT
}
