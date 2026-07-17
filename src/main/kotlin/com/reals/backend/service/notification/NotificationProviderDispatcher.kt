package com.reals.backend.service.notification

import com.reals.backend.service.notification.sender.PushNotificationSender
import com.reals.backend.service.notification.sender.PushSendResult
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class NotificationProviderDispatcher(
    private val pushNotificationSender: PushNotificationSender
) : PushNotificationProviderDispatcher {

    override fun send(command: PreparedPushCommand): PushSendResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Push notification provider calls must run outside active database transactions"
        }

        return pushNotificationSender.sendToTokens(
            tokens = command.tokens,
            notification = command.notification
        )
    }
}

interface PushNotificationProviderDispatcher {
    fun send(command: PreparedPushCommand): PushSendResult
}
